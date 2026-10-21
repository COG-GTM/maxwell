package com.zendesk.maxwell.monitoring;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Rejects requests that don't carry "Authorization: Bearer &lt;token&gt;" matching the
 * configured http_auth_token.
 */
public class HttpAuthTokenFilter implements Filter {
	private static final String BEARER_PREFIX = "Bearer ";
	private final byte[] expectedToken;

	public HttpAuthTokenFilter(String token) {
		if ( token == null || token.trim().isEmpty() )
			throw new IllegalArgumentException("http auth token must not be empty");
		this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
		HttpServletRequest req = (HttpServletRequest) request;
		HttpServletResponse resp = (HttpServletResponse) response;

		if ( isAuthorized(req.getHeader("Authorization")) ) {
			chain.doFilter(request, response);
			return;
		}

		resp.setHeader("WWW-Authenticate", "Bearer realm=\"maxwell\"");
		resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
	}

	boolean isAuthorized(String header) {
		if ( header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length()) )
			return false;
		byte[] supplied = header.substring(BEARER_PREFIX.length()).trim().getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(expectedToken, supplied);
	}
}
