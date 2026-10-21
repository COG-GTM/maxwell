package com.zendesk.maxwell.bootstrap;

import com.zendesk.maxwell.MaxwellContext;
import com.zendesk.maxwell.producer.AbstractProducer;
import com.zendesk.maxwell.row.RowMap;

/**
 * Wraps the real producer for the duration of one bootstrap attempt: tags
 * delivery failures as {@link BootstrapDeliveryException} and remembers whether
 * a "bootstrap-start" event has been published.
 */
class BootstrapOutputProducer extends AbstractProducer {
	private final AbstractProducer delegate;
	private boolean started = false;

	BootstrapOutputProducer(MaxwellContext context, AbstractProducer delegate) {
		super(context);
		this.delegate = delegate;
	}

	@Override
	public void push(RowMap r) throws Exception {
		try {
			delegate.push(r);
		} catch ( InterruptedException e ) {
			throw e;
		} catch ( Exception e ) {
			throw new BootstrapDeliveryException(e);
		}

		if ( "bootstrap-start".equals(r.getRowType()) )
			started = true;
	}

	boolean hasStarted() {
		return started;
	}
}
