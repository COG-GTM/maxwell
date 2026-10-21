package com.zendesk.maxwell.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

	private static class MutableClock extends Clock {
		long millis = 1_000_000L;

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return Instant.ofEpochMilli(millis);
		}

		@Override
		public long millis() {
			return millis;
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

	private static DiagnosticHealthCheck servlet(MaxwellDiagnosticContext.Config config, MaxwellDiagnostic diagnostic, Clock clock) throws Exception {
		DiagnosticHealthCheck servlet = new DiagnosticHealthCheck(
				new MaxwellDiagnosticContext(config, Collections.singletonList(diagnostic)), clock);
		servlet.init(servlet);
		return servlet;
	}

	private static HttpServletRequest request(String authorization) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getHeader("Authorization")).thenReturn(authorization);
		return request;
	}

	@Test
	public void testAnonymousResponseIsRedactedWhenNoTokenConfigured() throws Exception {
		FakeDiagnostic diagnostic = new FakeDiagnostic(true);
		DiagnosticHealthCheck servlet = servlet(config(null, 0), diagnostic, Clock.systemUTC());

		Response response = new Response();
		servlet.doGet(request(null), response.servletResponse);

		verify(response.servletResponse).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		JsonNode check = response.check();
		assertThat(check.get("name").asText(), is("fake"));
		assertFalse(check.has("resource"));
		assertThat(check.get("error").asText(), is("check failed, see maxwell logs for details"));
		assertFalse(response.body.toString().contains("10.0.0.5"));
		assertFalse(response.body.toString().contains("db-internal"));
	}

	@Test
	public void testMissingOrWrongTokenIsRejectedWithoutRunningChecks() throws Exception {
		FakeDiagnostic diagnostic = new FakeDiagnostic(false);
		DiagnosticHealthCheck servlet = servlet(config("s3cret-token", 0), diagnostic, Clock.systemUTC());

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
		DiagnosticHealthCheck servlet = servlet(config("s3cret-token", 0), diagnostic, Clock.systemUTC());

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
		MutableClock clock = new MutableClock();
		DiagnosticHealthCheck servlet = servlet(config(null, 5000), diagnostic, clock);

		for (int i = 0; i < 20; i++) {
			Response response = new Response();
			servlet.doGet(request(null), response.servletResponse);
			verify(response.servletResponse).setStatus(HttpServletResponse.SC_OK);
			assertThat(response.check().get("message").asText(), is("lag is 3ms"));
		}
		assertThat(diagnostic.runs.get(), is(1));

		clock.millis += 4999;
		servlet.currentResult();
		assertThat(diagnostic.runs.get(), is(1));

		clock.millis += 1;
		servlet.currentResult();
		assertThat(diagnostic.runs.get(), is(2));
	}

	@Test
	public void testConcurrentRequestsShareOneRun() throws Exception {
		FakeDiagnostic diagnostic = new FakeDiagnostic(false);
		CompletableFuture<MaxwellDiagnosticResult.Check> gate = new CompletableFuture<>();
		diagnostic.next = gate;
		DiagnosticHealthCheck servlet = servlet(config(null, 5000), diagnostic, Clock.systemUTC());

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
