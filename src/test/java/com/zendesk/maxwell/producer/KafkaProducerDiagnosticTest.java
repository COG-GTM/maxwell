package com.zendesk.maxwell.producer;

import com.zendesk.maxwell.MaxwellConfig;
import com.zendesk.maxwell.monitoring.MaxwellDiagnosticContext;
import com.zendesk.maxwell.row.RowMap;
import com.zendesk.maxwell.schema.PositionStoreThread;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class KafkaProducerDiagnosticTest {

	@Test
	public void testProbeIsSentToDiagnosticTopicNotCdcTopic() throws Exception {
		MaxwellConfig config = new MaxwellConfig();
		config.kafkaTopic = "maxwell";
		config.databaseName = "maxwell";
		config.diagnosticConfig = new MaxwellDiagnosticContext.Config();
		config.diagnosticConfig.kafkaTopic = "maxwell_health";

		MaxwellKafkaProducerWorker worker = mock(MaxwellKafkaProducerWorker.class);
		KafkaProducerDiagnostic diagnostic = new KafkaProducerDiagnostic(worker, config, mock(PositionStoreThread.class));

		diagnostic.getLatency();

		ArgumentCaptor<RowMap> row = ArgumentCaptor.forClass(RowMap.class);
		verify(worker).makeProducerRecord(row.capture());
		assertThat(row.getValue().getKafkaTopic(), is("maxwell_health"));
	}

	@Test
	public void testProbeDefaultsToDedicatedTopic() throws Exception {
		MaxwellConfig config = new MaxwellConfig();
		config.databaseName = "maxwell";

		KafkaProducerDiagnostic diagnostic = new KafkaProducerDiagnostic(
				mock(MaxwellKafkaProducerWorker.class), config, mock(PositionStoreThread.class));

		assertThat(diagnostic.diagnosticTopic(), is(MaxwellDiagnosticContext.Config.DEFAULT_KAFKA_TOPIC));
	}
}
