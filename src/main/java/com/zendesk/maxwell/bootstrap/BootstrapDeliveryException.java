package com.zendesk.maxwell.bootstrap;

/**
 * The producer failed to deliver a bootstrap row.  This is a problem with the
 * output, not with the bootstrap task, so it must never cause the task to be
 * abandoned.
 */
public class BootstrapDeliveryException extends Exception {
	public BootstrapDeliveryException(Throwable cause) {
		super("failed to deliver bootstrap row: " + cause, cause);
	}
}
