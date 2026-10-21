package com.zendesk.maxwell.producer;

import com.zendesk.maxwell.MaxwellConfig;
import com.zendesk.maxwell.MaxwellContext;
import com.zendesk.maxwell.monitoring.NoOpMetrics;
import com.zendesk.maxwell.replication.BinlogPosition;
import com.zendesk.maxwell.replication.Position;
import com.zendesk.maxwell.row.RowIdentity;
import com.zendesk.maxwell.row.RowMap;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class MaxwellKafkaProducerWorkerTest {
	@Test
	public void constructNewWorkerWithNullTopic() throws TimeoutException {
		MaxwellContext context = mock(MaxwellContext.class);
		MaxwellConfig config = new MaxwellConfig();
		when(context.getConfig()).thenReturn(config);
		when(context.getMetrics()).thenReturn(new NoOpMetrics());
		Properties kafkaProperties = new Properties();
		kafkaProperties.put("bootstrap.servers", "localhost:9092");
		String kafkaTopic = null;
		//shouldn't throw NPE
		MaxwellKafkaProducerWorker worker = new MaxwellKafkaProducerWorker(context, kafkaProperties, kafkaTopic, null);
		worker.close();
	}

	@Test
	public void fallbackPublishIsDeferred() {
		MaxwellContext context = mock(MaxwellContext.class);
		MaxwellConfig config = new MaxwellConfig();
		when(context.getConfig()).thenReturn(config);
		config.deadLetterTopic = "maxwell.errors";
		when(context.getMetrics()).thenReturn(new NoOpMetrics());
		Producer<String,String> producer = (Producer<String,String>) mock(Producer.class);
		KafkaCallback callback = mock(KafkaCallback.class);
		String kafkaTopic = "maxwell";
		RowIdentity rowId = new RowIdentity("MyDatabase", "MyTable", "insert", Collections.emptyList());
		MaxwellKafkaProducerWorker worker = new MaxwellKafkaProducerWorker(context, kafkaTopic, null, producer);

		worker.enqueueFallbackRow("maxwell.errors", rowId, callback, null, new Exception("The broker is grumpy"));
		verify(callback, never()).onCompletion(any(), isNotNull());
		verify(producer, never()).send(any(), any());

		worker.drainDeadLetterQueue();
		verify(producer, times(1)).send(any(), any());
		worker.close();
	}

	private static class Fixture {
		final MaxwellConfig config = new MaxwellConfig();
		final MaxwellContext context = mock(MaxwellContext.class);
		final Producer<String, String> producer = (Producer<String, String>) mock(Producer.class);
		final AbstractAsyncProducer.CallbackCompleter cc = mock(AbstractAsyncProducer.CallbackCompleter.class);

		Fixture() {
			when(context.getConfig()).thenReturn(config);
			when(context.getMetrics()).thenReturn(new NoOpMetrics());
			when(producer.partitionsFor(anyString())).thenReturn(Collections.singletonList(mock(PartitionInfo.class)));
		}

		MaxwellKafkaProducerWorker worker(String topicTemplate) {
			return new MaxwellKafkaProducerWorker(context, topicTemplate, null, producer);
		}
	}

	private static RowMap rowMap(String database, String table) {
		return new RowMap("insert", database, table, System.currentTimeMillis(), Collections.emptyList(),
				new Position(new BinlogPosition(3, "mysql.1"), 0L));
	}

	@Test
	public void tableNamesWithRegexMetacharactersBecomeLegalTopics() throws Exception {
		Fixture f = new Fixture();
		MaxwellKafkaProducerWorker worker = f.worker("namespace_%{database}_%{table}");

		worker.sendAsync(rowMap("db", "t$x"), f.cc);
		worker.sendAsync(rowMap("db", "a$1"), f.cc);
		worker.sendAsync(rowMap("db", "back\\slash"), f.cc);
		worker.sendAsync(rowMap("db", "caf\u00e9"), f.cc);

		ArgumentCaptor<ProducerRecord<String, String>> records = ArgumentCaptor.forClass(ProducerRecord.class);
		verify(f.producer, times(4)).send(records.capture(), any());
		assertEquals(
				Arrays.asList("namespace_db_t_x", "namespace_db_a_1", "namespace_db_back_slash", "namespace_db_caf_"),
				records.getAllValues().stream().map(ProducerRecord::topic).collect(Collectors.toList()));
		verify(f.context, never()).terminate(any());
	}

	@Test
	public void invalidTopicIsSkippedWhenIgnoringProducerErrors() throws Exception {
		Fixture f = new Fixture();
		when(f.producer.partitionsFor("bad_topic")).thenThrow(new InvalidTopicException("nope"));
		MaxwellKafkaProducerWorker worker = f.worker("%{table}");

		worker.sendAsync(rowMap("db", "bad_topic"), f.cc);

		verify(f.cc).markCompleted();
		verify(f.producer, never()).send(any(), any());
		verify(f.context, never()).terminate(any());
	}

	@Test
	public void overlongTopicIsSkippedWithoutAskingKafka() throws Exception {
		Fixture f = new Fixture();
		MaxwellKafkaProducerWorker worker = f.worker("%{database}_%{table}");
		String longName = String.join("", Collections.nCopies(64, "t"));

		worker.sendAsync(rowMap(longName, longName + longName + longName), f.cc);

		verify(f.cc).markCompleted();
		verify(f.producer, never()).partitionsFor(anyString());
		verify(f.producer, never()).send(any(), any());
	}

	@Test
	public void invalidTopicGoesToDeadLetterTopic() throws Exception {
		Fixture f = new Fixture();
		f.config.deadLetterTopic = "maxwell.errors";
		when(f.producer.partitionsFor("bad_topic")).thenThrow(new InvalidTopicException("nope"));
		MaxwellKafkaProducerWorker worker = f.worker("%{table}");

		worker.sendAsync(rowMap("db", "bad_topic"), f.cc);
		worker.drainDeadLetterQueue();

		ArgumentCaptor<ProducerRecord<String, String>> record = ArgumentCaptor.forClass(ProducerRecord.class);
		verify(f.producer).send(record.capture(), any());
		assertEquals("maxwell.errors", record.getValue().topic());
		verify(f.context, never()).terminate(any());
	}

	@Test
	public void invalidTopicTerminatesWhenNotIgnoringProducerErrors() throws Exception {
		Fixture f = new Fixture();
		f.config.ignoreProducerError = false;
		InvalidTopicException error = new InvalidTopicException("nope");
		when(f.producer.partitionsFor("bad_topic")).thenThrow(error);
		MaxwellKafkaProducerWorker worker = f.worker("%{table}");

		worker.sendAsync(rowMap("db", "bad_topic"), f.cc);

		verify(f.context).terminate(error);
		verify(f.cc, never()).markCompleted();
	}
}
