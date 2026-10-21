package com.zendesk.maxwell.monitoring;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MaxwellDiagnosticContextTest {

	private static MaxwellDiagnosticContext.Config config(String kafkaTopic) {
		MaxwellDiagnosticContext.Config config = new MaxwellDiagnosticContext.Config();
		config.kafkaTopic = kafkaTopic;
		return config;
	}

	@Test
	public void testLiteralCdcTopic() {
		assertTrue(config("maxwell").kafkaTopicMayMatch("maxwell"));
		assertFalse(config("maxwell_diagnostic").kafkaTopicMayMatch("maxwell"));
		assertFalse(config("maxwell_diagnostic").kafkaTopicMayMatch(null));
	}

	@Test
	public void testInterpolatedCdcTopic() {
		assertTrue(config("cdc_orders").kafkaTopicMayMatch("cdc_%{database}"));
		assertTrue(config("maxwell_diagnostic").kafkaTopicMayMatch("%{db}_%{table}"));
		assertFalse(config("health.maxwell").kafkaTopicMayMatch("cdc_%{database}"));
		assertFalse(config("maxwell-diagnostic").kafkaTopicMayMatch("%{db}_%{table}"));
	}

	@Test
	public void testRegexCharactersInTemplateAreLiteral() {
		assertFalse(config("cdcXorders").kafkaTopicMayMatch("cdc.%{table}"));
		assertTrue(config("cdc.orders").kafkaTopicMayMatch("cdc.%{table}"));
	}
}
