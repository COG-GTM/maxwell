package com.zendesk.maxwell.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Runs the registered {@link MaxwellDiagnostic}s. Diagnostics have side effects (heartbeat writes,
 * kafka probe messages), so:
 * <ul>
 *   <li>callers must present {@code http_diagnostic_token}; when no token is configured only loopback
 *   callers are served, and they get a redacted result</li>
 *   <li>runs are single-flight, bounded by {@code http_diagnostic_timeout}, and cached for
 *   {@code http_diagnostic_min_interval}</li>
 * </ul>
 */
public class DiagnosticHealthCheck extends HttpServlet {

	private static final Logger LOGGER = LoggerFactory.getLogger(DiagnosticHealthCheck.class);

	private static final String CONTENT_TYPE = "text/json";
	private static final String CACHE_CONTROL = "Cache-Control";
	private static final String NO_CACHE = "must-revalidate,no-cache,no-store";
	private static final String BEARER_PREFIX = "Bearer ";
	static final long RUN_GRACE_MS = 1000L;

	private final MaxwellDiagnosticContext diagnosticContext;
	private final LongSupplier nanoTime;
	// at most one run at a time: while a run is stuck (e.g. blocked on a MySQL write) new runs are refused
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final Object runLock = new Object();
	private Runnable abortActiveRun;
	private boolean destroyed;
	protected transient ObjectMapper mapper;

	private final Object lock = new Object();
	private CompletableFuture<MaxwellDiagnosticResult> inFlight;
	private MaxwellDiagnosticResult lastResult;
	private long lastResultAtNanos;

	public DiagnosticHealthCheck(MaxwellDiagnosticContext diagnosticContext) {
		this(diagnosticContext, System::nanoTime);
	}

	DiagnosticHealthCheck(MaxwellDiagnosticContext diagnosticContext, LongSupplier nanoTime) {
		this.diagnosticContext = diagnosticContext;
		this.nanoTime = nanoTime;
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);

		this.mapper = new ObjectMapper().registerModule(new DiagnosticHealthCheckModule());
	}

	@Override
	public void destroy() {
		synchronized (runLock) {
			destroyed = true;
			if (abortActiveRun != null) {
				abortActiveRun.run();
			}
		}
		super.destroy();
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		resp.setHeader(CACHE_CONTROL, NO_CACHE);
		resp.setContentType(CONTENT_TYPE);

		boolean tokenRequired = diagnosticContext.config.isTokenRequired();
		if (tokenRequired) {
			if (!isAuthorized(req)) {
				resp.setHeader("WWW-Authenticate", "Bearer realm=\"maxwell-diagnostic\"");
				resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				return;
			}
		} else if (!isLoopback(req)) {
			resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
			return;
		}

		MaxwellDiagnosticResult result = currentResult();

		if (result.isSuccess()) {
			resp.setStatus(HttpServletResponse.SC_OK);
		} else if (result.isMandatoryFailed()) {
			resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		} else {
			resp.setStatus(299);
		}

		try (PrintWriter output = resp.getWriter()) {
			mapper.writer().writeValue(output, tokenRequired ? result : result.redacted());
		}
	}

	private boolean isAuthorized(HttpServletRequest req) {
		String header = req.getHeader("Authorization");
		if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			return false;
		}
		byte[] presented = header.substring(BEARER_PREFIX.length()).trim().getBytes(StandardCharsets.UTF_8);
		byte[] expected = diagnosticContext.config.token.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(presented, expected);
	}

	private static boolean isLoopback(HttpServletRequest req) {
		String remote = req.getRemoteAddr();
		if (remote == null || remote.isEmpty()) {
			return false;
		}
		try {
			// getRemoteAddr is an IP literal, so this does not hit DNS
			return InetAddress.getByName(remote).isLoopbackAddress();
		} catch (UnknownHostException e) {
			return false;
		}
	}

	MaxwellDiagnosticResult currentResult() {
		CompletableFuture<MaxwellDiagnosticResult> pending;
		boolean leader = false;
		synchronized (lock) {
			long minIntervalNanos = TimeUnit.MILLISECONDS.toNanos(diagnosticContext.config.minInterval);
			if (lastResult != null && nanoTime.getAsLong() - lastResultAtNanos < minIntervalNanos) {
				return lastResult;
			}
			if (inFlight == null) {
				inFlight = new CompletableFuture<>();
				leader = true;
			}
			pending = inFlight;
		}

		if (leader) {
			MaxwellDiagnosticResult result = runBounded();
			synchronized (lock) {
				lastResult = result;
				lastResultAtNanos = nanoTime.getAsLong();
				pending.complete(result);
				inFlight = null;
			}
			return result;
		}

		try {
			return pending.get(runDeadlineMs(), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return failedResult("diagnostic run was interrupted");
		} catch (ExecutionException | TimeoutException e) {
			return failedResult("check did not return after " + diagnosticContext.config.timeout + " ms");
		}
	}

	private long runDeadlineMs() {
		return diagnosticContext.config.timeout + RUN_GRACE_MS;
	}

	private MaxwellDiagnosticResult runBounded() {
		if (!running.compareAndSet(false, true)) {
			LOGGER.warn("previous diagnostic run is still in progress, not starting a new one");
			return failedResult("previous diagnostic run is still in progress");
		}
		AtomicBoolean abandoned = new AtomicBoolean(false);
		CompletableFuture<MaxwellDiagnosticResult> run = new CompletableFuture<>();
		Thread thread = new Thread(() -> {
			MaxwellDiagnosticResult result = null;
			Throwable error = null;
			try {
				// a run given up on before this thread got scheduled must not start probing afterwards
				if (!abandoned.get() && !Thread.currentThread().isInterrupted()) {
					result = runChecks();
				}
			} catch (Throwable t) {
				error = t;
			} finally {
				synchronized (runLock) {
					abortActiveRun = null;
				}
				// cleared before the result is published, so a completed run never blocks the next one
				running.set(false);
			}
			if (error != null) {
				run.completeExceptionally(error);
			} else if (result != null) {
				run.complete(result);
			} else {
				run.cancel(false);
			}
		}, "maxwell-diagnostic");
		thread.setDaemon(true);
		Runnable abort = () -> {
			abandoned.set(true);
			thread.interrupt();
		};
		synchronized (runLock) {
			if (destroyed) {
				running.set(false);
				return failedResult("diagnostic endpoint is shutting down");
			}
			abortActiveRun = abort;
			thread.start();
		}

		try {
			return run.get(runDeadlineMs(), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			abort.run();
			return failedResult("diagnostic run was interrupted");
		} catch (TimeoutException | CancellationException e) {
			abort.run();
			LOGGER.warn("diagnostic run did not return after " + runDeadlineMs() + " ms");
			return failedResult("check did not return after " + diagnosticContext.config.timeout + " ms");
		} catch (ExecutionException e) {
			LOGGER.warn("diagnostic run failed", e.getCause());
			return failedResult("diagnostic run failed");
		}
	}

	private MaxwellDiagnosticResult runChecks() {
		long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(diagnosticContext.config.timeout);
		long deadline = nanoTime.getAsLong() + timeoutNanos;

		Map<MaxwellDiagnostic, CompletableFuture<MaxwellDiagnosticResult.Check>> futureChecks = diagnosticContext.diagnostics.stream()
				.collect(Collectors.toMap(diagnostic -> diagnostic, MaxwellDiagnostic::check));

		List<MaxwellDiagnosticResult.Check> checks = futureChecks.entrySet().stream().map(future -> {
			CompletableFuture<MaxwellDiagnosticResult.Check> futureCheck = future.getValue();
			try {
				long remaining = Math.max(0L, deadline - nanoTime.getAsLong());
				return futureCheck.get(remaining, TimeUnit.NANOSECONDS);
			} catch (InterruptedException | ExecutionException | TimeoutException e) {
				if (e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				futureCheck.cancel(true);
				MaxwellDiagnostic diagnostic = future.getKey();
				LOGGER.warn("diagnostic " + diagnostic.getName() + " did not return after " + diagnosticContext.config.timeout + " ms");
				return timeoutCheck(diagnostic);
			}
		}).collect(Collectors.toList());

		return new MaxwellDiagnosticResult(checks);
	}

	private MaxwellDiagnosticResult.Check timeoutCheck(MaxwellDiagnostic diagnostic) {
		Map<String, String> info = new HashMap<>();
		info.put("message", "check did not return after " + diagnosticContext.config.timeout + " ms");
		return new MaxwellDiagnosticResult.Check(diagnostic, false, info);
	}

	private MaxwellDiagnosticResult failedResult(String message) {
		List<MaxwellDiagnosticResult.Check> checks = diagnosticContext.diagnostics.stream().map(diagnostic -> {
			Map<String, String> info = new HashMap<>();
			info.put("message", message);
			return new MaxwellDiagnosticResult.Check(diagnostic, false, info);
		}).collect(Collectors.toList());
		return new MaxwellDiagnosticResult(checks);
	}
}
