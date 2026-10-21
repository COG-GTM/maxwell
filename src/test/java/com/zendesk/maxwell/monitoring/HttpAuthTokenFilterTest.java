package com.zendesk.maxwell.monitoring;

import org.junit.Test;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class HttpAuthTokenFilterTest {
	private final HttpAuthTokenFilter filter = new HttpAuthTokenFilter("s3cret");

	private void assertRejected(String header) throws Exception {
		HttpServletRequest req = mock(HttpServletRequest.class);
		HttpServletResponse resp = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		when(req.getHeader("Authorization")).thenReturn(header);

		filter.doFilter(req, resp, chain);

		verify(chain, never()).doFilter(any(), any());
		verify(resp).sendError(HttpServletResponse.SC_UNAUTHORIZED);
	}

	@Test
	public void rejectsMissingOrWrongToken() throws Exception {
		assertRejected(null);
		assertRejected("");
		assertRejected("Bearer");
		assertRejected("Bearer wrong");
		assertRejected("Bearer s3cret-extra");
		assertRejected("Basic czNjcmV0");
		assertRejected("s3cret");
	}

	@Test
	public void passesCorrectToken() throws Exception {
		HttpServletRequest req = mock(HttpServletRequest.class);
		HttpServletResponse resp = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		when(req.getHeader("Authorization")).thenReturn("Bearer s3cret");

		filter.doFilter(req, resp, chain);

		verify(chain).doFilter(req, resp);
		verify(resp, never()).sendError(anyInt());
	}

	@Test(expected = IllegalArgumentException.class)
	public void refusesEmptyToken() {
		new HttpAuthTokenFilter(" ");
	}

	@Test
	public void bindsLoopbackByDefaultForSensitiveEndpoints() throws Exception {
		assertEquals("127.0.0.1", MaxwellHTTPServer.resolveBindAddress(null, true));
		assertNull(MaxwellHTTPServer.resolveBindAddress(null, false));
		assertEquals("0.0.0.0", MaxwellHTTPServer.resolveBindAddress("0.0.0.0", true));
	}

	@Test(expected = java.io.IOException.class)
	public void requiresTokenForSensitiveEndpoints() throws Exception {
		MaxwellHTTPServer.requireAuthToken(null);
	}
}
