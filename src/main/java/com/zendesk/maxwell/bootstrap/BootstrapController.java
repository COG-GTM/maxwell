package com.zendesk.maxwell.bootstrap;

import com.zendesk.maxwell.errors.DuplicateProcessException;
import com.zendesk.maxwell.producer.AbstractProducer;
import com.zendesk.maxwell.row.RowMap;
import com.zendesk.maxwell.row.RowMapBuffer;
import com.zendesk.maxwell.util.ConnectionPool;
import com.zendesk.maxwell.util.RunLoopProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches maxwell.bootstrap, starts and stops bootstrap tasks
 */
public class BootstrapController extends RunLoopProcess  {
	static final Logger LOGGER = LoggerFactory.getLogger(BootstrapController.class);
	private final long MAX_TX_ELEMENTS = 10000;
	static final int MAX_TASK_ATTEMPTS = 3;
	static final long RETRY_BACKOFF_MILLIS = 1000;

	private final ConnectionPool maxwellConnectionPool;
	private final SynchronousBootstrapper bootstrapper;
	private final AbstractProducer producer;
	private final String clientID;
	private final boolean syncMode;
	private Long currentSchemaID;

	/**
	 * Instantiate a controller
	 * @param maxwellConnectionPool maxwell connection pool
	 * @param producer where to write rows
	 * @param bootstrapper the "actor" that actually does work
	 * @param clientID current client ID
	 * @param syncMode whether to stop replication while we bootstrap
	 * @param currentSchemaID initial value for schema_id
	 */
	public BootstrapController(
		ConnectionPool maxwellConnectionPool,
		AbstractProducer producer,
		SynchronousBootstrapper bootstrapper,
		String clientID,
		boolean syncMode,
		Long currentSchemaID
	) {
		this.maxwellConnectionPool = maxwellConnectionPool;
		this.producer = producer;
		this.bootstrapper = bootstrapper;
		this.clientID = clientID;
		this.syncMode = syncMode;
		this.currentSchemaID = currentSchemaID;
	}

	// this mutex is used to block rows from being produced while a "synchronous"
	// bootstrap is run
	private Object bootstrapMutex = new Object();

	// this one is used to protect against races in an async producer.
	private Object completionMutex = new Object();
	private BootstrapTask activeTask;
	private RowMapBuffer skippedRows = new RowMapBuffer(MAX_TX_ELEMENTS);
	private final Map<Long, Integer> taskFailures = new HashMap<>();

	@Override
	protected void work() throws Exception {
		try {
			doWork();
		} catch ( InterruptedException e ) {
		} catch ( SQLException e ) {
			LOGGER.error("got SQLException trying to bootstrap", e);
			Thread.sleep(RETRY_BACKOFF_MILLIS);
		}
	}

	private void doWork() throws Exception {
		List<BootstrapTask> tasks = getIncompleteTasks();
		synchronized(bootstrapMutex) {
			for ( BootstrapTask task : tasks ) {
				runTask(task);
			}
		}

		Thread.sleep(RETRY_BACKOFF_MILLIS);
	}

	/**
	 * Run a single bootstrap task.  A task-specific failure never escapes this
	 * method: buffered live rows are always released, and after MAX_TASK_ATTEMPTS
	 * consecutive failures the task is aborted (marked complete) so that one
	 * bad maxwell.bootstrap row can't stop replication or starve other tasks.
	 * Producer delivery failures are not the task's fault and are rethrown.
	 *
	 * @param task the task to run
	 * @throws Exception on interruption, a duplicate maxwell process, a producer delivery failure, or a failure to release buffered rows
	 */
	void runTask(BootstrapTask task) throws Exception {
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("starting bootstrap task: {}", task.logString());
		}
		synchronized(completionMutex) {
			activeTask = task;
		}

		try {
			bootstrapper.startBootstrap(task, producer, getCurrentSchemaID());
			taskFailures.remove(task.id);
		} catch ( InterruptedException | DuplicateProcessException | BootstrapDeliveryException e ) {
			throw e;
		} catch ( Exception e ) {
			handleTaskFailure(task, e);
		} finally {
			synchronized(completionMutex) {
				activeTask = null;
				pushSkippedRows();
			}
		}
	}

	private void handleTaskFailure(BootstrapTask task, Exception e) throws Exception {
		int failures = taskFailures.merge(task.id, 1, Integer::sum);
		LOGGER.error("bootstrap task {} failed (attempt {} of {})", task.logString(), failures, MAX_TASK_ATTEMPTS, e);

		if ( failures < MAX_TASK_ATTEMPTS )
			return;

		try {
			bootstrapper.abortBootstrap(task, "giving up after " + failures + " failed attempts: " + e);
			taskFailures.remove(task.id);
		} catch ( InterruptedException | DuplicateProcessException | BootstrapDeliveryException abortError ) {
			throw abortError;
		} catch ( Exception abortError ) {
			LOGGER.error("could not mark bootstrap task {} as aborted", task.logString(), abortError);
		}
	}

	private synchronized Long getCurrentSchemaID() {
		return this.currentSchemaID;
	}

	/**
	 * setup a value for outputting as "schema_id".
	 *
	 * Note that this is laughably unreliable, as there's totally no way of
	 * syncing the bootstrap's work with the replicators'.  But one of my great
	 * talents as an engineer has been ignoring stuff that's sublty wrong but
	 * functionally useful.
	 * @param schemaID the totally disconnected from reality schema_id
	 */
	public synchronized void setCurrentSchemaID(Long schemaID) {
		this.currentSchemaID = schemaID;
	}

	private List<BootstrapTask> getIncompleteTasks() throws SQLException {
		ArrayList<BootstrapTask> list = new ArrayList<>();
		try ( Connection cx = maxwellConnectionPool.getConnection();
			  PreparedStatement s = cx.prepareStatement("select * from bootstrap where is_complete = 0 and client_id = ? and (started_at is null or started_at <= now()) order by isnull(started_at), started_at asc, id asc") ) {
			s.setString(1, this.clientID);

			try ( ResultSet rs = s.executeQuery() ) {
				while (rs.next()) {
					list.add(BootstrapTask.valueOf(rs));
				}
			}
		}
		return list;
	}

	/**
	 * If a bootstrap is active for a table, buffer the row for later.
	 *
	 * At the end of a bootstrap we will output the buffered rows.
	 * This allows us to output a consistant snapshot of table, first
	 * doing the SELECT * and then outputting deltas.
	 * @param row a row to possibly buffer
	 * @return whether the row was buffered
	 * @throws IOException if there was a problem buffering the row
	 */
	public boolean shouldSkip(RowMap row) throws IOException {
		if ( syncMode )
			synchronized(bootstrapMutex) { return false; }
		else {
			synchronized (completionMutex) {
				if (activeTask == null)
					return false;

				// async mode with an active task
				if (activeTask.matches(row)) {
					skippedRows.add(row);
					return true;
				} else
					return false;
			}
		}
	}

	private void pushSkippedRows() throws Exception {
		skippedRows.flushToDisk();
		while ( skippedRows.size() > 0 ) {
			RowMap row = skippedRows.removeFirst();
			producer.push(row);
		}
	}

}
