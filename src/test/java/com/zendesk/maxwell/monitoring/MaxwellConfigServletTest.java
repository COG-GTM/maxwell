package com.zendesk.maxwell.monitoring;

import com.zendesk.maxwell.MaxwellConfig;
import com.zendesk.maxwell.MaxwellContext;
import com.zendesk.maxwell.filtering.Filter;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class MaxwellConfigServletTest {
	private MaxwellConfig config;
	private Filter filter;
	private MaxwellConfigServlet servlet;
	private StringWriter output;
	private HttpServletResponse resp;

	@Before
	public void setUp() throws Exception {
		config = new MaxwellConfig();
		filter = new Filter("exclude: *.*");
		MaxwellContext context = mock(MaxwellContext.class);
		when(context.getConfig()).thenReturn(config);
		when(context.getFilter()).thenReturn(filter);
		servlet = new MaxwellConfigServlet(context);

		output = new StringWriter();
		resp = mock(HttpServletResponse.class);
		when(resp.getWriter()).thenReturn(new PrintWriter(output));
	}

	private HttpServletRequest request(String method, String body, String authorization) throws Exception {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getMethod()).thenReturn(method);
		when(req.getHeader("Authorization")).thenReturn(authorization);
		when(req.getContentLengthLong()).thenReturn(body == null ? -1L : (long) body.length());
		when(req.getReader()).thenReturn(new BufferedReader(new StringReader(body == null ? "" : body)));
		return req;
	}

	@Test(timeout = 5000)
	public void testUnterminatedRegexpReturns400() throws Exception {
		servlet.service(request("POST", "{\"filter\":\"include: a./x\"}", null), resp);
		verify(resp).setStatus(400);
		assertTrue(output.toString().contains("invalid filter: unterminated regexp"));
		assertEquals("exclude: *.*", filter.toString());
	}

	@Test
	public void testValidUpdateWithoutTokenConfigured() throws Exception {
		servlet.service(request("PATCH", "{\"filter\":\"include: db./ab\\\\/c/\"}", null), resp);
		verify(resp).setStatus(200);
		assertEquals("include: db./ab\\/c/", filter.toString());
	}

	@Test
	public void testOversizedBodyReturns413() throws Exception {
		StringBuilder sb = new StringBuilder("{\"filter\":\"include: db./");
		while ( sb.length() <= MaxwellConfigServlet.MAX_BODY_LENGTH )
			sb.append('a');
		sb.append("/\"}");
		HttpServletRequest req = request("PUT", sb.toString(), null);
		when(req.getContentLengthLong()).thenReturn(-1L);
		servlet.service(req, resp);
		verify(resp).setStatus(413);
		assertEquals("exclude: *.*", filter.toString());
	}

	@Test
	public void testTokenRequiredWhenConfigured() throws Exception {
		config.httpConfigToken = "s3cret-token";

		servlet.service(request("POST", "{\"filter\":\"include: a.b\"}", null), resp);
		verify(resp).setStatus(401);
		assertEquals("exclude: *.*", filter.toString());

		HttpServletResponse wrongResp = mock(HttpServletResponse.class);
		when(wrongResp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
		servlet.service(request("GET", null, "Bearer wrong"), wrongResp);
		verify(wrongResp).setStatus(401);

		HttpServletResponse okResp = mock(HttpServletResponse.class);
		when(okResp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
		servlet.service(request("POST", "{\"filter\":\"include: a.b\"}", "Bearer s3cret-token"), okResp);
		verify(okResp).setStatus(200);
		assertEquals("include: a.b", filter.toString());
	}
}
