package com.zendesk.maxwell.bootstrap;

import com.zendesk.maxwell.producer.AbstractProducer;
import com.zendesk.maxwell.row.RowMap;
import org.junit.Before;
import org.junit.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class BootstrapControllerTest {
	private SynchronousBootstrapper bootstrapper;
	private AbstractProducer producer;
	private TestController controller;

	private static class TestController extends BootstrapController {
		List<BootstrapTask> tasks = new ArrayList<>();
		SQLException taskListError;
		final List<Long> sleeps = new ArrayList<>();

		TestController(AbstractProducer producer, SynchronousBootstrapper bootstrapper) {
			super(null, producer, bootstrapper, "maxwell", false, 1L);
		}

		@Override
		List<BootstrapTask> getIncompleteTasks() throws SQLException {
			if ( taskListError != null )
				throw taskListError;
			return tasks;
		}

		@Override
		protected void sleep(long millis) {
			sleeps.add(millis);
		}
	}

	private static BootstrapTask task(long id, String database, String table) {
		BootstrapTask t = new BootstrapTask();
		t.id = id;
		t.database = database;
		t.table = table;
		return t;
	}

	private static RowMap liveRow(String database, String table) {
		return new RowMap("insert", database, table, System.currentTimeMillis(), new ArrayList<>(), null);
	}

	@Before
	public void setUp() {
		bootstrapper = mock(SynchronousBootstrapper.class);
		producer = mock(AbstractProducer.class);
		controller = new TestController(producer, bootstrapper);
	}

	@Test
	public void testFailingTaskDoesNotEscapeAndReleasesBufferedRows() throws Exception {
		BootstrapTask t = task(1, "shard_1", "minimal");
		controller.tasks = Arrays.asList(t);
		RowMap buffered = liveRow("shard_1", "minimal");

		doAnswer(inv -> {
			assertThat(controller.shouldSkip(buffered), is(true));
			throw new NullPointerException("boom");
		}).when(bootstrapper).startBootstrap(eq(t), any(), any());

		controller.work();

		verify(producer).push(buffered);
		assertThat(controller.shouldSkip(liveRow("shard_1", "minimal")), is(false));
	}

	@Test
	public void testTaskIsAbandonedAfterMaxAttempts() throws Exception {
		BootstrapTask t = task(1, "shard_1", "minimal");
		controller.tasks = Arrays.asList(t);
		doThrow(new SQLException("You have an error in your SQL syntax"))
			.when(bootstrapper).startBootstrap(eq(t), any(), any());

		for ( int i = 0; i < BootstrapController.MAX_TASK_ATTEMPTS - 1; i++ )
			controller.work();
		verify(bootstrapper, never()).abandonBootstrap(any(), anyString());

		controller.work();
		verify(bootstrapper, times(BootstrapController.MAX_TASK_ATTEMPTS)).startBootstrap(eq(t), any(), any());
		verify(bootstrapper, times(1)).abandonBootstrap(eq(t), anyString());
	}

	@Test
	public void testSuccessResetsFailureCount() throws Exception {
		BootstrapTask t = task(1, "shard_1", "minimal");
		controller.tasks = Arrays.asList(t);
		doThrow(new RuntimeException("transient"))
			.doNothing()
			.doThrow(new RuntimeException("transient"))
			.doThrow(new RuntimeException("transient"))
			.doNothing()
			.when(bootstrapper).startBootstrap(eq(t), any(), any());

		for ( int i = 0; i < 5; i++ )
			controller.work();

		verify(bootstrapper, never()).abandonBootstrap(any(), anyString());
	}

	@Test
	public void testFailingTaskDoesNotStarveOtherTasks() throws Exception {
		BootstrapTask bad = task(1, "shard_1", "minimal");
		BootstrapTask good = task(2, "shard_1", "other");
		controller.tasks = Arrays.asList(bad, good);
		doThrow(new SQLException("permission denied")).when(bootstrapper).startBootstrap(eq(bad), any(), any());

		controller.work();

		verify(bootstrapper).startBootstrap(eq(good), any(), any());
	}

	@Test
	public void testAbandonIsRetriedWithoutRerunningTask() throws Exception {
		BootstrapTask t = task(1, "shard_1", "minimal");
		controller.tasks = Arrays.asList(t);
		doThrow(new SQLException("bad where clause")).when(bootstrapper).startBootstrap(eq(t), any(), any());
		doThrow(new SQLException("maxwell db unavailable"))
			.doNothing()
			.when(bootstrapper).abandonBootstrap(eq(t), anyString());

		for ( int i = 0; i < BootstrapController.MAX_TASK_ATTEMPTS; i++ )
			controller.work();
		controller.work();

		verify(bootstrapper, times(BootstrapController.MAX_TASK_ATTEMPTS)).startBootstrap(eq(t), any(), any());
		verify(bootstrapper, times(2)).abandonBootstrap(eq(t), anyString());
	}

	@Test
	public void testBacksOffAfterFailedPasses() throws Exception {
		BootstrapTask t = task(1, "shard_1", "minimal");
		controller.tasks = Arrays.asList(t);
		doThrow(new RuntimeException("x"))
			.doThrow(new RuntimeException("x"))
			.doNothing()
			.when(bootstrapper).startBootstrap(eq(t), any(), any());

		controller.work();
		controller.work();
		controller.work();

		assertThat(controller.sleeps, is(Arrays.asList(2000L, 4000L, 1000L)));
	}

	@Test
	public void testBackoffIsCapped() {
		long delay = 0;
		for ( int i = 0; i < 40; i++ )
			delay = controller.nextDelayMillis(true);
		assertThat(delay, is(BootstrapController.MAX_RETRY_DELAY_MILLIS));
		assertThat(controller.nextDelayMillis(false), is(BootstrapController.POLL_INTERVAL_MILLIS));
	}

	@Test
	public void testTaskListErrorBacksOff() throws Exception {
		controller.taskListError = new SQLException("maxwell db unavailable");

		controller.work();
		controller.work();

		assertThat(controller.sleeps, is(Arrays.asList(2000L, 4000L)));
		verify(bootstrapper, never()).startBootstrap(any(), any(), any());
	}
}
