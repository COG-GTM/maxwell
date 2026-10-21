package com.zendesk.maxwell.monitoring;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.zendesk.maxwell.MaxwellContext;
import com.zendesk.maxwell.filtering.FilterParser;
import com.zendesk.maxwell.filtering.FilterRegexGuard;
import com.zendesk.maxwell.filtering.InvalidFilterException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * An HTTP servlet which allows for the live reconfiguration of maxwell. If
 * property provided for update is null, it will be ignored.
 * <p>
 * Every request must carry <code>Authorization: Bearer &lt;http_config_secret&gt;</code>.
 * </p>
 */
public class MaxwellConfigServlet extends HttpServlet {
	private static final java.lang.String CONTENT_TYPE = "application/json";
	private static final ObjectMapper mapper = new ObjectMapper();
	static final Logger LOGGER = LoggerFactory.getLogger(MaxwellConfigServlet.class);
	static final int MAX_BODY_LENGTH = 64 * 1024;
	static final int MAX_FILTER_LENGTH = 8 * 1024;
	private static final String BEARER_PREFIX = "Bearer ";
	private final MaxwellContext context;

	public MaxwellConfigServlet(MaxwellContext context) {
		super();
		this.context = context;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private static class SerializedConfig {
		public String filter;
	}

	private static class ErrorResponse {
		public int code;
		public String message;

		public ErrorResponse(int code, String message) {
			this.code = code;
			this.message = message;
		}

		public static String getErrorResponseString(int code, String message) throws JsonProcessingException {
			ErrorResponse errResponse = new ErrorResponse(code, message);
			return String.format("{\"error\":%s}", mapper.writeValueAsString(errResponse));
		}
	}

	private static class BodyTooLargeException extends IOException {
		BodyTooLargeException() {
			super("request body exceeds " + MAX_BODY_LENGTH + " characters");
		}
	}

	private void writeError(HttpServletResponse resp, int code, String message) throws IOException {
		resp.setStatus(code);
		resp.setContentType(CONTENT_TYPE);
		try (PrintWriter writer = resp.getWriter()) {
			writer.println(ErrorResponse.getErrorResponseString(code, message));
		}
	}

	private boolean isAuthorized(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		String secret = this.context.getConfig().httpConfigSecret;
		if ( secret == null || secret.isEmpty() ) {
			LOGGER.warn("rejecting /config request from {}: http_config_secret is not configured", req.getRemoteAddr());
			writeError(resp, HttpServletResponse.SC_FORBIDDEN, "http_config_secret is not configured");
			return false;
		}

		String header = req.getHeader("Authorization");
		String presented = null;
		if ( header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length()) )
			presented = header.substring(BEARER_PREFIX.length()).trim();

		if ( presented == null || !secretsMatch(presented, secret) ) {
			LOGGER.warn("rejecting unauthenticated /config {} request from {}", sanitizeForLog(req.getMethod()), req.getRemoteAddr());
			resp.setHeader("WWW-Authenticate", "Bearer realm=\"maxwell\"");
			writeError(resp, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized");
			return false;
		}
		return true;
	}

	static boolean secretsMatch(String presented, String expected) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] a = digest.digest(presented.getBytes(StandardCharsets.UTF_8));
			byte[] b = digest.digest(expected.getBytes(StandardCharsets.UTF_8));
			return MessageDigest.isEqual(a, b);
		} catch ( NoSuchAlgorithmException e ) {
			throw new IllegalStateException(e);
		}
	}

	private static String readBody(HttpServletRequest req) throws IOException {
		if ( req.getContentLengthLong() > MAX_BODY_LENGTH )
			throw new BodyTooLargeException();

		StringBuilder sb = new StringBuilder();
		char[] buf = new char[4096];
		BufferedReader reader = req.getReader();
		int n;
		while ( (n = reader.read(buf)) != -1 ) {
			sb.append(buf, 0, n);
			if ( sb.length() > MAX_BODY_LENGTH )
				throw new BodyTooLargeException();
		}
		return sb.toString();
	}

	private void handleConfigUpdateRequest(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if ( !isAuthorized(req, resp) )
			return;

		SerializedConfig sconfig;
		try {
			String reqbody = readBody(req);
			sconfig = mapper.readValue(reqbody, SerializedConfig.class);
		} catch (BodyTooLargeException e) {
			writeError(resp, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, e.getMessage());
			return;
		} catch (Exception e) {
			writeError(resp, HttpServletResponse.SC_BAD_REQUEST, String.format("error processing body: %s", e.getMessage()));
			return;
		}

		try {
			applySerializedConfig(sconfig, req.getRemoteAddr());

			resp.setStatus(HttpServletResponse.SC_OK);
			resp.setContentType(CONTENT_TYPE);
			try (PrintWriter writer = resp.getWriter()) {
				writer.println(getSerializedConfig());
			}
		} catch (InvalidFilterException e) {
			writeError(resp, HttpServletResponse.SC_BAD_REQUEST, String.format("invalid filter: %s", e.getMessage()));
		}
	}

	@Override
	protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		this.handleConfigUpdateRequest(req, resp);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		this.handleConfigUpdateRequest(req, resp);
	}

	protected void doPatch(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		this.handleConfigUpdateRequest(req, resp);
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if ( !isAuthorized(req, resp) )
			return;

		resp.setStatus(HttpServletResponse.SC_OK);
		resp.setContentType(CONTENT_TYPE);
		try (PrintWriter writer = resp.getWriter()) {
			writer.println(getSerializedConfig());
		}
	}

	@Override
	public void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		if (request.getMethod().equalsIgnoreCase("PATCH")) {
			doPatch(request, response);
		} else {
			super.service(request, response);
		}
	}

	private String getSerializedConfig() throws JsonProcessingException {
		SerializedConfig sconfig = new SerializedConfig();
		String filterstring = this.context.getFilter().toString();
		if (!filterstring.equals("")) {
			sconfig.filter = filterstring;
		}
		return mapper.writeValueAsString(sconfig);
	}

	private void applySerializedConfig(SerializedConfig sconfig, String remoteAddr) throws InvalidFilterException {
		if (sconfig.filter != null) {
			if ( sconfig.filter.length() > MAX_FILTER_LENGTH )
				throw new InvalidFilterException("filter exceeds " + MAX_FILTER_LENGTH + " characters");

			FilterRegexGuard.check(new FilterParser(sconfig.filter).parse());
			context.getFilter().set(sconfig.filter);
			LOGGER.info("updated filter via /config from {}: {}", remoteAddr, sanitizeForLog(sconfig.filter));
		}
	}

	static String sanitizeForLog(String s) {
		if ( s == null )
			return null;
		StringBuilder sb = new StringBuilder(s.length());
		for ( int i = 0; i < s.length(); i++ ) {
			char c = s.charAt(i);
			if ( c == '\n' ) {
				sb.append("\\n");
			} else if ( c == '\r' ) {
				sb.append("\\r");
			} else if ( c == '\t' ) {
				sb.append("\\t");
			} else if ( Character.isISOControl(c) || c == '\u2028' || c == '\u2029' ) {
				sb.append(String.format("\\u%04x", (int) c));
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}
}
