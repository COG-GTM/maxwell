package com.zendesk.maxwell.bootstrap;

import com.zendesk.maxwell.producer.AbstractProducer;
import com.zendesk.maxwell.row.RowMap;
import com.zendesk.maxwell.util.ConnectionPool;
import org.junit.Before;
import org.junit.Test;

import java.sql.SQLException;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class BootstrapControllerTest {
	private SynchronousBootstrapper bootstrapper;
	private AbstractProducer producer;
	private BootstrapController controller;
	private BootstrapTask task;

	@Before
	public void setUp() {
		bootstrapper = mock(SynchronousBootstrapper.class);
		producer = mock(AbstractProducer.class);
		controller = new BootstrapController(mock(ConnectionPool.class), producer, bootstrapper, "maxwell", false, 1L);

		task = new BootstrapTask();
		task.id = 1L;
		task.database = "shard_1";
		task.table = "minimal";
		task.whereClause = "1=";
	}

	private RowMap liveRow() {
		return new RowMap("insert", "shard_1", "minimal", System.currentTimeMillis(), Collections.emptyList(), null);
	}

	@Test
	public void testFailingTaskIsAbortedAfterBoundedRetries() throws Exception {
		doThrow(new SQLException("You have an error in your SQL syntax"))
			.when(bootstrapper).startBootstrap(eq(task), any(), anyLong());

		for ( int i = 1; i < BootstrapController.MAX_TASK_ATTEMPTS; i++ ) {
			controller.runTask(task);
			verify(bootstrapper, never()).abortBootstrap(any(), anyString());
		}

		controller.runTask(task);
		verify(bootstrapper, times(1)).abortBootstrap(eq(task), anyString());
	}

	@Test
	public void testUnexpectedRuntimeExceptionDoesNotEscape() throws Exception {
		doThrow(new NullPointerException())
			.when(bootstrapper).startBootstrap(eq(task), any(), anyLong());

		for ( int i = 0; i < BootstrapController.MAX_TASK_ATTEMPTS; i++ )
			controller.runTask(task);

		verify(bootstrapper, times(1)).abortBootstrap(eq(task), anyString());
	}

	@Test
	public void testSuccessResetsFailureCount() throws Exception {
		doThrow(new SQLException("transient"))
			.doNothing()
			.doThrow(new SQLException("transient"))
			.when(bootstrapper).startBootstrap(eq(task), any(), anyLong());

		controller.runTask(task);
		controller.runTask(task);
		for ( int i = 1; i < BootstrapController.MAX_TASK_ATTEMPTS; i++ )
			controller.runTask(task);

		verify(bootstrapper, never()).abortBootstrap(any(), anyString());
	}

	@Test
	public void testBufferedLiveRowsAreReleasedWhenTaskFails() throws Exception {
		RowMap row = liveRow();
		doAnswer(invocation -> {
			assertTrue(controller.shouldSkip(row));
			throw new SQLException("permission denied");
		}).when(bootstrapper).startBootstrap(eq(task), any(), anyLong());

		controller.runTask(task);

		verify(producer).push(row);
		assertFalse(controller.shouldSkip(liveRow()));
	}

	@Test
	public void testBufferedLiveRowsAreReleasedWhenTaskSucceeds() throws Exception {
		RowMap row = liveRow();
		doAnswer(invocation -> {
			assertTrue(controller.shouldSkip(row));
			return null;
		}).when(bootstrapper).startBootstrap(eq(task), any(), anyLong());

		controller.runTask(task);

		verify(producer).push(row);
		assertFalse(controller.shouldSkip(liveRow()));
	}

	@Test(expected = InterruptedException.class)
	public void testInterruptionIsPropagated() throws Exception {
		doThrow(new InterruptedException())
			.when(bootstrapper).startBootstrap(eq(task), any(), anyLong());

		controller.runTask(task);
	}

	@Test
	public void testDeliveryFailurePropagatesWithoutAbortingTask() throws Exception {
		BootstrapDeliveryException deliveryError = new BootstrapDeliveryException(new java.io.IOException("disk full"));
		doThrow(deliveryError).when(bootstrapper).startBootstrap(eq(task), any(), anyLong());

		for ( int i = 0; i < BootstrapController.MAX_TASK_ATTEMPTS + 1; i++ ) {
			try {
				controller.runTask(task);
				org.junit.Assert.fail("expected BootstrapDeliveryException");
			} catch ( BootstrapDeliveryException e ) {
				assertTrue(e == deliveryError);
			}
		}
		verify(bootstrapper, never()).abortBootstrap(any(), anyString());
	}
}
