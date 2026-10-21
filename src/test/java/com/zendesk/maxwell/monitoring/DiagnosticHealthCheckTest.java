package com.zendesk.maxwell.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DiagnosticHealthCheckTest {

	private static class FakeDiagnostic implements MaxwellDiagnostic {
		final AtomicInteger runs = new AtomicInteger();
		volatile CompletableFuture<MaxwellDiagnosticResult.Check> next;
		final boolean fail;

		FakeDiagnostic(boolean fail) {
			this.fail = fail;
		}

		@Override
		public String getName() {
			return "fake";
		}

		@Override
		public boolean isMandatory() {
			return true;
		}

		@Override
		public String getResource() {
			return "db-internal.example:3306";
		}

		@Override
		public CompletableFuture<MaxwellDiagnosticResult.Check> check() {
			runs.incrementAndGet();
			if (next != null) {
				return next;
			}
			Map<String, String> info = new HashMap<>();
			if (fail) {
				info.put("error", "java.sql.SQLException: Access denied for user 'maxwell'@'10.0.0.5'");
			} else {
				info.put("message", "lag is 3ms");
			}
			return CompletableFuture.completedFuture(new MaxwellDiagnosticResult.Check(this, !fail, info));
		}
	}

	private static class Ticker implements LongSupplier {
		volatile long nanos = 1_000_000_000L;

		@Override
		public long getAsLong() {
			return nanos;
		}

		void advanceMillis(long millis) {
			nanos += TimeUnit.MILLISECONDS.toNanos(millis);
		}
	}

	private static class Response {
		final HttpServletResponse servletResponse = mock(HttpServletResponse.class);
		final StringWriter body = new StringWriter();

		Response() throws Exception {
			when(servletResponse.getWriter()).thenReturn(new PrintWriter(body));
		}

		JsonNode check() throws Exception {
			return new ObjectMapper().readTree(body.toString()).path("checks").get(0);
		}
	}

	private static MaxwellDiagnosticContext.Config config(String token, long minInterval) {
		MaxwellDiagnosticContext.Config config = new MaxwellDiagnosticContext.Config();
		config.timeout = 2000;
		config.token = token;
		config.minInterval = minInterval;
		return config;
	}

	private static DiagnosticHealthCheck servlet(MaxwellDiagnosticContext.Config config, MaxwellDiagnostic diagnostic, LongSupplier ticker) throws Exception {
		DiagnosticHealthCheck servlet = new DiagnosticHealthCheck(
				new MaxwellDiagnosticContext(config, Collections.singletonList(diagnostic)), ticker);
		servlet.init(servlet);
		return servlet;
	}

	private static HttpServletRequest request(String authorization) {
		return request(authorization, "10.1.2.3");
	}

	private static HttpServletRequest request(String authorization, String remoteAddr) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getHeader("Authorization")).thenReturn(authorization);
		when(request.getRemoteAddr()).thenReturn(remoteAddr);
		return request;
	}

	@Test
	public void testLoopbackResponseIsRedactedWhenNoTokenConfigured() throws Exception {
		FakeDiagnostic diagnostic = new FakeDiagnostic(true);
		DiagnosticHealthCheck servlet = servlet(config(null, 0), diagnostic, System::nanoTime);

		for (String loopback : new String[] { "127.0.0.1", "0:0:0:0:0:0:0:1" }) {
			Response response = new Response();
			servlet.doGet(request(null, loopback), response.servletResponse);

			verify(response.servletResponse).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			JsonNode check = response.check();
			assertThat(check.get("name").asText(), is("fake"));
			assertFalse(check.has("resource"));
			assertFalse(check.has("error"));
			assertThat(check.get("message").asText(), is("check failed, see maxwell logs for details"));
			assertFalse(response.body.toString().contains("10.0.0.5"));
			assertFalse(response.body.toString().contains("db-internal"));
		}
	}

	@Test
	public void testRedactionDropsCheckMessages() throws Exception {
		Map<String, String> info = new HashMap<>();
		info.put("message", "connected to db-internal.example:3306 as maxwell");
		MaxwellDiagnosticResult.Check check = new MaxwellDiagnosticResult.Check("fake", true, true, "db-internal.example:3306", info);

		MaxwellDiagnosticResult.Check redacted = check.redacted();

		assertThat(redacted.getInfo().get("message"), is("ok"));
		assertThat(redacted.getInfo().size(), is(1));
		assertThat(redacted.getResource(), is((String) null));
	}

	@Test
	public void testRemoteAnonymousCallerIsRejectedWhenNoTokenConfigured() throws Exception {
		FakeDiagnostic diagnostic = new FakeDiagnostic(false);
		DiagnosticHealthCheck servlet = servlet(config(null, 0), diagnostic, System::nanoTime);

		for (String remote : new String[] { "10.1.2.3", "192.168.1.10", "", null }) {
			Response response = new Response();
			servlet.doGet(request(null, remote), response.servletResponse);
			verify(response.servletResponse).setStatus(HttpServletResponse.SC_FORBIDDEN);
			verify(response.servletResponse, never()).getWriter();
		}
		assertThat(diagnostic.runs.get(), is(0));
	}

	@Test
	public void testMissingOrWrongTokenIsRejectedWithoutRunningChecks() throws Exception {
		FakeDiagnostic diagnostic = new FakeDiagnostic(false);
		DiagnosticHealthCheck servlet = servlet(config("s3cret-token", 0), diagnostic, System::nanoTime);

		for (String header : new String[] { null, "Bearer wrong", "Basic s3cret-token", "Bearer s3cret-token-longer" }) {
			Response response = new Response();
			servlet.doGet(request(header), response.servletResponse);
			verify(response.servletResponse).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			verify(response.servletResponse, never()).getWriter();
		}
		assertThat(diagnostic.runs.get(), is(0));
	}

	@Test
	public void testValidTokenGetsFullDetails() throws Exception {
		FakeDiagnostic diagnostic = new FakeDiagnostic(true);
		DiagnosticHealthCheck servlet = servlet(config("s3cret-token", 0), diagnostic, System::nanoTime);

		Response response = new Response();
		servlet.doGet(request("Bearer s3cret-token"), response.servletResponse);

		verify(response.servletResponse).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		JsonNode check = response.check();
		assertThat(check.get("resource").asText(), is("db-internal.example:3306"));
		assertTrue(check.get("error").asText().contains("Access denied"));
		assertThat(diagnostic.runs.get(), is(1));
	}

	@Test
	public void testResultsAreCachedForMinInterval() throws Exception {
		FakeDiagnostic diagnostic = new FakeDiagnostic(false);
		Ticker ticker = new Ticker();
		DiagnosticHealthCheck servlet = servlet(config("s3cret-token", 5000), diagnostic, ticker);

		for (int i = 0; i < 20; i++) {
			Response response = new Response();
			servlet.doGet(request("Bearer s3cret-token"), response.servletResponse);
			verify(response.servletResponse).setStatus(HttpServletResponse.SC_OK);
			assertThat(response.check().get("message").asText(), is("lag is 3ms"));
		}
		assertThat(diagnostic.runs.get(), is(1));

		ticker.advanceMillis(4999);
		servlet.currentResult();
		assertThat(diagnostic.runs.get(), is(1));

		ticker.advanceMillis(1);
		servlet.currentResult();
		assertThat(diagnostic.runs.get(), is(2));
	}

	@Test
	public void testBackToBackRunsWithZeroIntervalAllSucceed() throws Exception {
		FakeDiagnostic diagnostic = new FakeDiagnostic(false);
		DiagnosticHealthCheck servlet = servlet(config(null, 0), diagnostic, System::nanoTime);

		for (int i = 0; i < 200; i++) {
			assertTrue(servlet.currentResult().isSuccess());
		}
		assertThat(diagnostic.runs.get(), is(200));
	}

	@Test
	public void testDestroyInterruptsActiveRun() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		MaxwellDiagnostic blocking = new FakeDiagnostic(false) {
			@Override
			public CompletableFuture<MaxwellDiagnosticResult.Check> check() {
				started.countDown();
				try {
					Thread.sleep(TimeUnit.MINUTES.toMillis(1));
				} catch (InterruptedException e) {
					interrupted.countDown();
				}
				return super.check();
			}
		};
		DiagnosticHealthCheck servlet = servlet(config(null, 0), blocking, System::nanoTime);

		new Thread(servlet::currentResult).start();
		assertTrue(started.await(5, TimeUnit.SECONDS));
		servlet.destroy();
		assertTrue(interrupted.await(5, TimeUnit.SECONDS));
	}

	@Test
	public void testNoRunStartsAfterDestroy() throws Exception {
		FakeDiagnostic diagnostic = new FakeDiagnostic(false);
		DiagnosticHealthCheck servlet = servlet(config(null, 0), diagnostic, System::nanoTime);
		servlet.destroy();

		assertFalse(servlet.currentResult().isSuccess());
		assertThat(diagnostic.runs.get(), is(0));
	}

	@Test
	public void testWallClockRollbackDoesNotFreezeCache() throws Exception {
		FakeDiagnostic diagnostic = new FakeDiagnostic(false);
		Ticker ticker = new Ticker();
		DiagnosticHealthCheck servlet = servlet(config(null, 5000), diagnostic, ticker);

		servlet.currentResult();
		ticker.advanceMillis(5000);
		servlet.currentResult();
		assertThat(diagnostic.runs.get(), is(2));
	}

	@Test
	public void testStuckRunDoesNotBlockLaterRequestsAndIsNotRestarted() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger blockingRuns = new AtomicInteger();
		MaxwellDiagnostic blocking = new FakeDiagnostic(false) {
			@Override
			public CompletableFuture<MaxwellDiagnosticResult.Check> check() {
				blockingRuns.incrementAndGet();
				try {
					// e.g. a synchronous MySQL write that hangs; ignores interrupts like a stuck socket would
					while (!release.await(10, TimeUnit.SECONDS)) { }
				} catch (InterruptedException e) {
					try { release.await(); } catch (InterruptedException ignored) { }
				}
				return super.check();
			}
		};
		MaxwellDiagnosticContext.Config config = config(null, 0);
		config.timeout = 100;
		DiagnosticHealthCheck servlet = servlet(config, blocking, System::nanoTime);

		long start = System.nanoTime();
		MaxwellDiagnosticResult first = servlet.currentResult();
		assertFalse(first.isSuccess());
		assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));

		MaxwellDiagnosticResult second = servlet.currentResult();
		assertFalse(second.isSuccess());
		assertThat(second.getChecks().get(0).getInfo().get("message"), is("previous diagnostic run is still in progress"));
		assertThat(blockingRuns.get(), is(1));

		release.countDown();
		Thread.sleep(200);
		servlet.currentResult();
		assertThat(blockingRuns.get(), is(2));
	}

	@Test
	public void testConcurrentRequestsShareOneRun() throws Exception {
		FakeDiagnostic diagnostic = new FakeDiagnostic(false);
		CompletableFuture<MaxwellDiagnosticResult.Check> gate = new CompletableFuture<>();
		diagnostic.next = gate;
		DiagnosticHealthCheck servlet = servlet(config(null, 5000), diagnostic, System::nanoTime);

		int callers = 8;
		CountDownLatch done = new CountDownLatch(callers);
		List<MaxwellDiagnosticResult> results = Collections.synchronizedList(new ArrayList<>());
		for (int i = 0; i < callers; i++) {
			new Thread(() -> {
				results.add(servlet.currentResult());
				done.countDown();
			}).start();
		}

		Thread.sleep(200);
		Map<String, String> info = new HashMap<>();
		info.put("message", "lag is 1ms");
		gate.complete(new MaxwellDiagnosticResult.Check(diagnostic, true, info));

		assertTrue(done.await(5, TimeUnit.SECONDS));
		assertThat(diagnostic.runs.get(), is(1));
		assertThat(results.size(), is(callers));
		for (MaxwellDiagnosticResult result : results) {
			assertTrue(result.isSuccess());
		}
	}
}
