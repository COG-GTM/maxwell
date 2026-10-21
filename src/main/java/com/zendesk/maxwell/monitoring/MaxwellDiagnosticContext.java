package com.zendesk.maxwell.monitoring;

import java.util.List;

public class MaxwellDiagnosticContext {

	public final Config config;
	public final List<MaxwellDiagnostic> diagnostics;

	public MaxwellDiagnosticContext(Config config, List<MaxwellDiagnostic> diagnostics) {
		this.config = config;
		this.diagnostics = diagnostics;
	}

	public static class Config {
		public static final long DEFAULT_MIN_INTERVAL_MS = 5000L;
		public static final String DEFAULT_KAFKA_TOPIC = "maxwell_diagnostic";

		public boolean enable;
		public long timeout;

		/**
		 * bearer token required to call /diagnostic. When null, anonymous callers get
		 * a redacted response (no resources or error details).
		 */
		public String token;

		/**
		 * minimum time between two diagnostic runs; requests in between get the cached result
		 */
		public long minInterval = DEFAULT_MIN_INTERVAL_MS;

		/**
		 * kafka topic the producer diagnostic probe is sent to (never the CDC topic)
		 */
		public String kafkaTopic = DEFAULT_KAFKA_TOPIC;

		public boolean isTokenRequired() {
			return token != null && !token.isEmpty();
		}
	}
}
