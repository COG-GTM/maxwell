package com.zendesk.maxwell.monitoring;

import com.zendesk.maxwell.MaxwellConfig;
import com.zendesk.maxwell.MaxwellContext;
import com.zendesk.maxwell.filtering.Filter;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MaxwellConfigServletTest {
	private static final String SECRET = "unit-test-secret-0123456789";

	private Server server;
	private MaxwellConfig config;
	private Filter filter;
	private URI uri;
	private final HttpClient client = HttpClient.newHttpClient();

	@Before
	public void setUp() throws Exception {
		config = new MaxwellConfig();
		config.enableHttpConfig = true;
		config.httpConfigSecret = SECRET;
		filter = new Filter("exclude: secrets_db.*");

		MaxwellContext context = mock(MaxwellContext.class);
		when(context.getConfig()).thenReturn(config);
		when(context.getFilter()).thenReturn(filter);

		server = new Server(new InetSocketAddress("127.0.0.1", 0));
		ServletContextHandler handler = new ServletContextHandler(server, "/");
		handler.addServlet(new ServletHolder(new MaxwellConfigServlet(context)), "/config");
		server.start();
		int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
		uri = URI.create("http://127.0.0.1:" + port + "/config");
	}

	@After
	public void tearDown() throws Exception {
		server.stop();
	}

	private HttpResponse<String> send(String method, String body, String authorization) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri);
		if ( body == null )
			builder.method(method, HttpRequest.BodyPublishers.noBody());
		else
			builder.method(method, HttpRequest.BodyPublishers.ofString(body));
		if ( authorization != null )
			builder.header("Authorization", authorization);
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private String filterBody(String filter) {
		return "{\"filter\":\"" + filter.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r") + "\"}";
	}

	@Test
	public void testRejectsMissingCredentialsForEveryMethod() throws Exception {
		for ( String method : new String[] { "GET", "PUT", "POST", "PATCH" } ) {
			HttpResponse<String> resp = send(method, method.equals("GET") ? null : filterBody("exclude: *.*"), null);
			assertEquals(method, 401, resp.statusCode());
			assertTrue(resp.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"));
			assertFalse(resp.body().contains("secrets_db"));
		}
		assertEquals("exclude: secrets_db.*", filter.toString());
	}

	@Test
	public void testRejectsWrongSecret() throws Exception {
		assertEquals(401, send("PUT", filterBody("exclude: *.*"), "Bearer not-the-secret").statusCode());
		assertEquals(401, send("PUT", filterBody("exclude: *.*"), "Basic " + SECRET).statusCode());
		assertEquals(401, send("PUT", filterBody("exclude: *.*"), SECRET).statusCode());
		assertEquals("exclude: secrets_db.*", filter.toString());
	}

	@Test
	public void testFailsClosedWithoutConfiguredSecret() throws Exception {
		config.httpConfigSecret = null;
		assertEquals(403, send("GET", null, "Bearer ").statusCode());
		assertEquals(403, send("PUT", filterBody("exclude: *.*"), "Bearer null").statusCode());
		assertEquals("exclude: secrets_db.*", filter.toString());
	}

	@Test
	public void testAuthorizedGetAndUpdate() throws Exception {
		HttpResponse<String> resp = send("GET", null, "Bearer " + SECRET);
		assertEquals(200, resp.statusCode());
		assertEquals("{\"filter\":\"exclude: secrets_db.*\"}\n", resp.body());

		for ( String method : new String[] { "PUT", "POST", "PATCH" } ) {
			resp = send(method, filterBody("exclude: *.*, include: db_" + method + ".*"), "bearer " + SECRET);
			assertEquals(method, 200, resp.statusCode());
			assertEquals("exclude: *.*, include: db_" + method + ".*", filter.toString());
		}
	}

	@Test
	public void testRejectsCatastrophicRegex() throws Exception {
		HttpResponse<String> resp = send("PUT", filterBody("exclude: db.tbl.col = /^(\\w+\\s?)*$/"), "Bearer " + SECRET);
		assertEquals(400, resp.statusCode());
		assertTrue(resp.body().contains("nested quantifier"));
		assertEquals("exclude: secrets_db.*", filter.toString());
	}

	@Test
	public void testRejectsOversizedFilterAndBody() throws Exception {
		StringBuilder longFilter = new StringBuilder("include: db.t");
		while ( longFilter.length() <= MaxwellConfigServlet.MAX_FILTER_LENGTH )
			longFilter.append('x');
		assertEquals(400, send("PUT", filterBody(longFilter.toString()), "Bearer " + SECRET).statusCode());

		StringBuilder hugeBody = new StringBuilder("{\"filter\":\"exclude: *.*\",\"pad\":\"");
		while ( hugeBody.length() <= MaxwellConfigServlet.MAX_BODY_LENGTH )
			hugeBody.append('x');
		hugeBody.append("\"}");
		assertEquals(413, send("PUT", hugeBody.toString(), "Bearer " + SECRET).statusCode());
		assertEquals("exclude: secrets_db.*", filter.toString());
	}

	@Test
	public void testSanitizeForLog() {
		assertEquals("include: db./x\\r\\n2026 INFO forged/", MaxwellConfigServlet.sanitizeForLog("include: db./x\r\n2026 INFO forged/"));
		assertEquals("a\\u0000b\\u2028c", MaxwellConfigServlet.sanitizeForLog("a\u0000b\u2028c"));
	}

	@Test
	public void testSecretsMatch() {
		assertTrue(MaxwellConfigServlet.secretsMatch(SECRET, SECRET));
		assertFalse(MaxwellConfigServlet.secretsMatch(SECRET + "x", SECRET));
		assertFalse(MaxwellConfigServlet.secretsMatch("", SECRET));
	}
}
