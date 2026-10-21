package com.zendesk.maxwell.replication;

import com.zendesk.maxwell.MaxwellContext;
import com.zendesk.maxwell.monitoring.MaxwellDiagnosticResult;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BinlogConnectorDiagnosticTest {

	private MaxwellContext stalledContext(HeartbeatNotifier notifier) throws Exception {
		MaxwellContext context = mock(MaxwellContext.class);
		when(context.getHeartbeatNotifier()).thenReturn(notifier);
		// heartbeat is written but never read back: replication is stalled
		when(context.heartbeat()).thenReturn(0L);
		return context;
	}

	@Test
	public void testObserversDoNotPileUpWhenReplicationIsStalled() throws Exception {
		HeartbeatNotifier notifier = new HeartbeatNotifier();
		BinlogConnectorDiagnostic diagnostic = new BinlogConnectorDiagnostic(stalledContext(notifier));

		for (int i = 0; i < 10; i++) {
			CompletableFuture<MaxwellDiagnosticResult.Check> check = diagnostic.check();
			try {
				check.get(10, TimeUnit.MILLISECONDS);
				fail("check should not complete while replication is stalled");
			} catch (TimeoutException e) {
				// same as DiagnosticHealthCheck on timeout
				check.cancel(true);
			}
		}

		assertThat(notifier.countObservers(), is(0));
	}
}
