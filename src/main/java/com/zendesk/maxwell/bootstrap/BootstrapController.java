package com.zendesk.maxwell.bootstrap;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Watches maxwell.bootstrap, starts and stops bootstrap tasks
 */
public class BootstrapController extends RunLoopProcess  {
	static final Logger LOGGER = LoggerFactory.getLogger(BootstrapController.class);
	private final long MAX_TX_ELEMENTS = 10000;

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

	static final int MAX_TASK_ATTEMPTS = 3;
	static final long POLL_INTERVAL_MILLIS = 1000;
	static final long MAX_RETRY_DELAY_MILLIS = 60000;

	// failed attempts per task id; only touched from the run loop thread
	private final Map<Long, Integer> taskFailures = new HashMap<>();
	// abandoned tasks whose bootstrap-complete was already pushed, so retries don't push it again
	private final Set<Long> abandonCompletionSent = new HashSet<>();
	private int consecutiveFailedPasses = 0;

	@Override
	protected void work() throws Exception {
		boolean failed;
		try {
			failed = doWork();
		} catch ( InterruptedException e ) {
			return;
		} catch ( SQLException e ) {
			LOGGER.error("got SQLException trying to bootstrap", e);
			failed = true;
		}

		try {
			sleep(nextDelayMillis(failed));
		} catch ( InterruptedException e ) {
		}
	}

	/**
	 * Run every incomplete task once.
	 *
	 * A failing task never stops the other tasks from running, and never escapes
	 * to the run loop (which would shut maxwell down).  After
	 * {@link #MAX_TASK_ATTEMPTS} failures the task is abandoned.
	 *
	 * @return whether any task failed during this pass
	 */
	private boolean doWork() throws Exception {
		boolean anyFailed = false;
		List<BootstrapTask> tasks = getIncompleteTasks();
		synchronized(bootstrapMutex) {
			for ( BootstrapTask task : tasks ) {
				if ( isExhausted(task) ) {
					if ( !abandonTask(task, "a previous attempt to abandon it failed") )
						anyFailed = true;
					continue;
				}

				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("starting bootstrap task: {}", task.logString());
				}
				synchronized(completionMutex) {
					activeTask = task;
				}

				try {
					bootstrapper.startBootstrap(task, producer, getCurrentSchemaID());
					taskFailures.remove(task.id);
				} catch ( InterruptedException e ) {
					throw e;
				} catch ( SynchronousBootstrapper.BootstrapProducerException e ) {
					// the producer is failing, not the task: retry without counting it against the task
					anyFailed = true;
					LOGGER.error("producer failed during bootstrap task {}, will retry", task.logString(), e.getCause());
				} catch ( Exception e ) {
					anyFailed = true;
					recordTaskFailure(task, e);
				} finally {
					synchronized(completionMutex) {
						try {
							pushSkippedRows();
						} finally {
							activeTask = null;
						}
					}
				}
			}
		}
		return anyFailed;
	}

	private boolean isExhausted(BootstrapTask task) {
		return taskFailures.getOrDefault(task.id, 0) >= MAX_TASK_ATTEMPTS;
	}

	private void recordTaskFailure(BootstrapTask task, Exception e) {
		int attempts = taskFailures.merge(task.id, 1, Integer::sum);
		if ( attempts >= MAX_TASK_ATTEMPTS ) {
			LOGGER.error("bootstrap task {} failed (attempt {}/{}), giving up", task.logString(), attempts, MAX_TASK_ATTEMPTS, e);
			abandonTask(task, String.format("failed %d times, last error: %s", attempts, e));
		} else {
			LOGGER.error("bootstrap task {} failed (attempt {}/{}), will retry", task.logString(), attempts, MAX_TASK_ATTEMPTS, e);
		}
	}

	private boolean abandonTask(BootstrapTask task, String reason) {
		try {
			if ( !abandonCompletionSent.contains(task.id) ) {
				bootstrapper.completeBootstrap(task, producer);
				abandonCompletionSent.add(task.id);
			}
			bootstrapper.abandonBootstrap(task, reason);
			taskFailures.remove(task.id);
			abandonCompletionSent.remove(task.id);
			return true;
		} catch ( Exception e ) {
			LOGGER.error("couldn't abandon bootstrap task {}, will try again", task.logString(), e);
			return false;
		}
	}

	/**
	 * How long to wait before the next pass: the normal poll interval, doubled
	 * for each consecutive pass that had a failure, up to {@link #MAX_RETRY_DELAY_MILLIS}.
	 */
	long nextDelayMillis(boolean failed) {
		if ( !failed ) {
			consecutiveFailedPasses = 0;
			return POLL_INTERVAL_MILLIS;
		}

		consecutiveFailedPasses = Math.min(consecutiveFailedPasses + 1, 16);
		return Math.min(POLL_INTERVAL_MILLIS << consecutiveFailedPasses, MAX_RETRY_DELAY_MILLIS);
	}

	protected void sleep(long millis) throws InterruptedException {
		Thread.sleep(millis);
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

	List<BootstrapTask> getIncompleteTasks() throws SQLException {
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
