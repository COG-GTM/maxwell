package com.zendesk.maxwell.bootstrap;

import com.zendesk.maxwell.MaxwellConfig;
import com.zendesk.maxwell.MaxwellContext;
import com.zendesk.maxwell.monitoring.NoOpMetrics;
import com.zendesk.maxwell.producer.AbstractProducer;
import com.zendesk.maxwell.row.RowMap;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BootstrapStartTest {
	private MaxwellContext context;
	private AbstractProducer producer;
	private BootstrapTask task;

	@Before
	public void setUp() {
		context = mock(MaxwellContext.class);
		when(context.getConfig()).thenReturn(new MaxwellConfig());
		when(context.getMetrics()).thenReturn(new NoOpMetrics());
		producer = mock(AbstractProducer.class);

		task = new BootstrapTask();
		task.id = 1L;
		task.database = "shard_1";
		task.table = "minimal";
	}

	private RowMap row(String type) {
		return new RowMap(type, "shard_1", "minimal", System.currentTimeMillis(), Collections.emptyList(), null);
	}

	private SynchronousBootstrapper bootstrapperThat(boolean pushStart, Exception failure) {
		return new SynchronousBootstrapper(context) {
			@Override
			public void performBootstrap(BootstrapTask t, AbstractProducer p, Long schemaID) throws Exception {
				if ( pushStart ) {
					p.push(row("bootstrap-start"));
					p.push(row("bootstrap-insert"));
				}
				throw failure;
			}
		};
	}

	private List<RowMap> pushedRows(int count) throws Exception {
		ArgumentCaptor<RowMap> captor = ArgumentCaptor.forClass(RowMap.class);
		verify(producer, times(count)).push(captor.capture());
		return captor.getAllValues();
	}

	@Test
	public void testFailureAfterStartClosesSnapshotWithAbortEvent() throws Exception {
		SQLException failure = new SQLException("lost connection mid-stream");
		try {
			bootstrapperThat(true, failure).startBootstrap(task, producer, 1L);
			fail("expected SQLException");
		} catch ( SQLException e ) {
			assertTrue(e == failure);
		}

		List<RowMap> rows = pushedRows(3);
		assertThat(rows.get(0).getRowType(), is("bootstrap-start"));
		assertThat(rows.get(1).getRowType(), is("bootstrap-insert"));
		assertThat(rows.get(2).getRowType(), is("bootstrap-abort"));
	}

	@Test
	public void testFailureBeforeStartEmitsNothing() throws Exception {
		try {
			bootstrapperThat(false, new SQLException("boom")).startBootstrap(task, producer, 1L);
			fail("expected SQLException");
		} catch ( SQLException e ) {
		}

		pushedRows(0);
	}

	@Test
	public void testProducerFailureIsReportedAsDeliveryFailure() throws Exception {
		IOException ioError = new IOException("disk full");
		doThrow(ioError).when(producer).push(any());

		try {
			bootstrapperThat(true, new SQLException("unreachable")).startBootstrap(task, producer, 1L);
			fail("expected BootstrapDeliveryException");
		} catch ( BootstrapDeliveryException e ) {
			assertTrue(e.getCause() == ioError);
		}

		verify(producer, atLeastOnce()).push(any());
		pushedRows(1);
	}
}
