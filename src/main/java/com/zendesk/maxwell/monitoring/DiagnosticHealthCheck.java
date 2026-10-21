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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Runs the registered {@link MaxwellDiagnostic}s. Diagnostics have side effects (heartbeat writes,
 * kafka probe messages), so runs are single-flight and cached for {@code http_diagnostic_min_interval},
 * and callers without the configured {@code http_diagnostic_token} are rejected (or, when no token is
 * configured, get a redacted result).
 */
public class DiagnosticHealthCheck extends HttpServlet {

	private static final Logger LOGGER = LoggerFactory.getLogger(DiagnosticHealthCheck.class);

	private static final String CONTENT_TYPE = "text/json";
	private static final String CACHE_CONTROL = "Cache-Control";
	private static final String NO_CACHE = "must-revalidate,no-cache,no-store";
	private static final String BEARER_PREFIX = "Bearer ";

	private final MaxwellDiagnosticContext diagnosticContext;
	private final Clock clock;
	protected transient ObjectMapper mapper;

	private final Object lock = new Object();
	private CompletableFuture<MaxwellDiagnosticResult> inFlight;
	private MaxwellDiagnosticResult lastResult;
	private long lastResultAt;

	public DiagnosticHealthCheck(MaxwellDiagnosticContext diagnosticContext) {
		this(diagnosticContext, Clock.systemUTC());
	}

	DiagnosticHealthCheck(MaxwellDiagnosticContext diagnosticContext, Clock clock) {
		this.diagnosticContext = diagnosticContext;
		this.clock = clock;
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);

		this.mapper = new ObjectMapper().registerModule(new DiagnosticHealthCheckModule());
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		resp.setHeader(CACHE_CONTROL, NO_CACHE);
		resp.setContentType(CONTENT_TYPE);

		boolean tokenRequired = diagnosticContext.config.isTokenRequired();
		if (tokenRequired && !isAuthorized(req)) {
			resp.setHeader("WWW-Authenticate", "Bearer realm=\"maxwell-diagnostic\"");
			resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
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

	MaxwellDiagnosticResult currentResult() {
		CompletableFuture<MaxwellDiagnosticResult> pending;
		boolean leader = false;
		synchronized (lock) {
			if (lastResult != null && clock.millis() - lastResultAt < diagnosticContext.config.minInterval) {
				return lastResult;
			}
			if (inFlight == null) {
				inFlight = new CompletableFuture<>();
				leader = true;
			}
			pending = inFlight;
		}

		if (leader) {
			MaxwellDiagnosticResult result = null;
			try {
				result = runChecks();
				return result;
			} finally {
				synchronized (lock) {
					if (result != null) {
						lastResult = result;
						lastResultAt = clock.millis();
					}
					inFlight = null;
				}
				if (result != null) {
					pending.complete(result);
				} else {
					pending.completeExceptionally(new IllegalStateException("diagnostic run failed"));
				}
			}
		}

		try {
			return pending.get(diagnosticContext.config.timeout, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return failedResult("diagnostic run was interrupted");
		} catch (ExecutionException | TimeoutException e) {
			return failedResult("check did not return after " + diagnosticContext.config.timeout + " ms");
		}
	}

	private MaxwellDiagnosticResult runChecks() {
		Map<MaxwellDiagnostic, CompletableFuture<MaxwellDiagnosticResult.Check>> futureChecks = diagnosticContext.diagnostics.stream()
				.collect(Collectors.toMap(diagnostic -> diagnostic, MaxwellDiagnostic::check));

		List<MaxwellDiagnosticResult.Check> checks = futureChecks.entrySet().stream().map(future -> {
			CompletableFuture<MaxwellDiagnosticResult.Check> futureCheck = future.getValue();
			try {
				return futureCheck.get(diagnosticContext.config.timeout, TimeUnit.MILLISECONDS);
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
