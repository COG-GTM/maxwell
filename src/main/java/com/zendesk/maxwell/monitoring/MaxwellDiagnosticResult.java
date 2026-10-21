package com.zendesk.maxwell.monitoring;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class MaxwellDiagnosticResult {

	private final boolean success;
	private final boolean mandatoryFailed;
	private final List<Check> checks;

	public MaxwellDiagnosticResult(List<Check> checks) {
		success = checks.stream().allMatch(Check::isSuccess);
		mandatoryFailed = checks.stream().anyMatch(check -> !check.success && check.mandatory);
		this.checks = checks;
	}

	public boolean isSuccess() {
		return success;
	}

	public boolean isMandatoryFailed() {
		return mandatoryFailed;
	}

	public List<Check> getChecks() {
		return checks;
	}

	/**
	 * @return a copy safe to return to unauthenticated callers: no resources, no raw error text
	 */
	public MaxwellDiagnosticResult redacted() {
		return new MaxwellDiagnosticResult(checks.stream().map(Check::redacted).collect(Collectors.toList()));
	}

	public static class Check {
		private final String name;
		private final boolean success;
		private final boolean mandatory;
		private final String resource;
		private final Map<String, String> info;

		public Check(MaxwellDiagnostic diagnostic, boolean success, Map<String, String> info) {
			this(diagnostic.getName(), success, diagnostic.isMandatory(), diagnostic.getResource(), info);
		}

		public Check(String name, boolean success, boolean mandatory, String resource, Map<String, String> info) {
			this.name = name;
			this.success = success;
			this.mandatory = mandatory;
			this.resource = resource;
			this.info = info;
		}

		public Check redacted() {
			Map<String, String> redactedInfo = new HashMap<>();
			if (info != null) {
				if (info.containsKey("message")) {
					redactedInfo.put("message", info.get("message"));
				}
				if (info.containsKey("error")) {
					redactedInfo.put("error", "check failed, see maxwell logs for details");
				}
			}
			return new Check(name, success, mandatory, null, redactedInfo);
		}

		public String getName() {
			return name;
		}

		public boolean isSuccess() {
			return success;
		}

		public boolean isMandatory() {
			return mandatory;
		}

		public String getResource() {
			return resource;
		}

		public Map<String, String> getInfo() {
			return info;
		}
	}
}
