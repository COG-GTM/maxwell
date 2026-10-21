package com.zendesk.maxwell.producer;

import com.zendesk.maxwell.MaxwellConfig;
import com.zendesk.maxwell.monitoring.MaxwellDiagnostic;
import com.zendesk.maxwell.monitoring.MaxwellDiagnosticContext;
import com.zendesk.maxwell.monitoring.MaxwellDiagnosticResult;
import com.zendesk.maxwell.row.RowMap;
import com.zendesk.maxwell.schema.PositionStoreThread;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class KafkaProducerDiagnostic implements MaxwellDiagnostic {

	private static final Logger LOGGER = LoggerFactory.getLogger(KafkaProducerDiagnostic.class);

	private final MaxwellKafkaProducerWorker producer;
	private final MaxwellConfig config;
	private final PositionStoreThread positionStoreThread;

	public KafkaProducerDiagnostic(MaxwellKafkaProducerWorker producer, MaxwellConfig config, PositionStoreThread positionStoreThread) {
		this.producer = producer;
		this.config = config;
		this.positionStoreThread = positionStoreThread;
	}

	@Override
	public String getName() {
		return "kafka-producer";
	}

	@Override
	public CompletableFuture<MaxwellDiagnosticResult.Check> check() {
		return getLatency().thenApply(this::normalResult).exceptionally(this::exceptionResult);
	}

	@Override
	public boolean isMandatory() {
		return true;
	}

	@Override
	public String getResource() {
		return config.getKafkaProperties().getProperty("bootstrap.servers");
	}

	public CompletableFuture<Long> getLatency() {
		DiagnosticCallback callback = new DiagnosticCallback();
		try {
			RowMap rowMap = new RowMap("insert", config.databaseName, "dummy", System.currentTimeMillis(),
					new ArrayList<>(), positionStoreThread.getPosition());
			rowMap.setTXCommit();
			rowMap.setKafkaTopic(diagnosticTopic());
			ProducerRecord<String, String> record = producer.makeProducerRecord(rowMap);
			producer.sendAsync(record, callback);
		} catch (Exception e) {
			callback.latency.completeExceptionally(e);
		}
		return callback.latency;
	}

	String diagnosticTopic() {
		if (config.diagnosticConfig == null || config.diagnosticConfig.kafkaTopic == null) {
			return MaxwellDiagnosticContext.Config.DEFAULT_KAFKA_TOPIC;
		}
		return config.diagnosticConfig.kafkaTopic;
	}

	private MaxwellDiagnosticResult.Check normalResult(Long latency) {
		Map<String, String> info = new HashMap<>();
		info.put("message", "Kafka producer acknowledgement lag is " + latency.toString() + "ms");
		return new MaxwellDiagnosticResult.Check(this, true, info);
	}

	private MaxwellDiagnosticResult.Check exceptionResult(Throwable e) {
		Throwable cause = (e instanceof CompletionException && e.getCause() != null) ? e.getCause() : e;
		LOGGER.warn("kafka-producer diagnostic failed", cause);
		Map<String, String> info = new HashMap<>();
		info.put("error", cause.toString());
		return new MaxwellDiagnosticResult.Check(this, false, info);
	}

	static class DiagnosticCallback implements Callback {
		final CompletableFuture<Long> latency;
		final long sendTime;

		DiagnosticCallback() {
			latency = new CompletableFuture<>();
			sendTime = System.currentTimeMillis();
		}

		@Override
		public void onCompletion(final RecordMetadata metadata, final Exception exception) {
			if (exception == null) {
				latency.complete(System.currentTimeMillis() - sendTime);
			} else {
				latency.completeExceptionally(exception);
			}
		}
	}
}
