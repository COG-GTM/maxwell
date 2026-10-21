package com.zendesk.maxwell.monitoring;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.zendesk.maxwell.MaxwellContext;
import com.zendesk.maxwell.filtering.InvalidFilterException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * An HTTP servlet which allows for the live reconfiguration of maxwell. If
 * property provided for update is null, it will be ignored.
 */
public class MaxwellConfigServlet extends HttpServlet {
	private static final java.lang.String CONTENT_TYPE = "application/json";
	private static final ObjectMapper mapper = new ObjectMapper();
	static final int MAX_BODY_BYTES = 64 * 1024;
	static final Logger LOGGER = LoggerFactory.getLogger(MaxwellConfigServlet.class);
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

	private void handleConfigUpdateRequest(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (req.getContentLengthLong() > MAX_BODY_BYTES) {
			writeError(resp, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "request body too large");
			return;
		}

		SerializedConfig sconfig;
		try {
			String reqbody = readBody(req);
			if (reqbody == null) {
				writeError(resp, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "request body too large");
				return;
			}
			sconfig = mapper.readValue(reqbody, SerializedConfig.class);
		} catch (Exception e) {
			resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			resp.setContentType(CONTENT_TYPE);
			try (PrintWriter writer = resp.getWriter()) {
				writer.println(ErrorResponse.getErrorResponseString(HttpServletResponse.SC_BAD_REQUEST,
						String.format("error processing body: %s", e.getMessage())));
			}
			return;
		}

		try {
			applySerializedConfig(sconfig);

			resp.setStatus(HttpServletResponse.SC_OK);
			resp.setContentType(CONTENT_TYPE);
			try (PrintWriter writer = resp.getWriter()) {
				writer.println(getSerializedConfig());
			}
		} catch (InvalidFilterException e) {
			resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			resp.setContentType(CONTENT_TYPE);
			try (PrintWriter writer = resp.getWriter()) {
				writer.println(ErrorResponse.getErrorResponseString(HttpServletResponse.SC_BAD_REQUEST,
						String.format("invalid filter: %s", e.getMessage())));
			}
		}
	}

	private static String readBody(HttpServletRequest req) throws IOException {
		StringBuilder sb = new StringBuilder();
		char[] buf = new char[4096];
		BufferedReader reader = req.getReader();
		int n;
		while ((n = reader.read(buf)) != -1) {
			if (sb.length() + n > MAX_BODY_BYTES)
				return null;
			sb.append(buf, 0, n);
		}
		return sb.toString();
	}

	private static void writeError(HttpServletResponse resp, int code, String message) throws IOException {
		resp.setStatus(code);
		resp.setContentType(CONTENT_TYPE);
		try (PrintWriter writer = resp.getWriter()) {
			writer.println(ErrorResponse.getErrorResponseString(code, message));
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

	private void applySerializedConfig(SerializedConfig sconfig) throws InvalidFilterException {
		if (sconfig.filter != null) {
			context.getFilter().set(sconfig.filter);
			LOGGER.info("updated filter: " + sconfig.filter.replaceAll("[\\r\\n]", "_"));
		}
	}
}
