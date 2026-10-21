package com.zendesk.maxwell.bootstrap;

import com.github.shyiko.mysql.binlog.network.SSLMode;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertThat;

public class MaxwellBootstrapUtilityConfigTest {

	@Test
	public void testSSLAppliedToBothConnectionURIs() {
		MaxwellBootstrapUtilityConfig config = new MaxwellBootstrapUtilityConfig(new String[] {
			"--host=maxwell-host", "--ssl=VERIFY_IDENTITY", "--database=shard_1", "--table=minimal"
		});

		assertThat(config.getConnectionURI(), startsWith("jdbc:mysql://maxwell-host:3306/maxwell?"));
		assertThat(config.getConnectionURI(), containsString("sslMode=VERIFY_IDENTITY"));
		assertThat(config.getReplicationConnectionURI(), startsWith("jdbc:mysql://maxwell-host:3306/shard_1?"));
		assertThat(config.getReplicationConnectionURI(), containsString("sslMode=VERIFY_IDENTITY"));
	}

	@Test
	public void testReplicationSSLDefaultsToMaxwellSSL() {
		MaxwellBootstrapUtilityConfig config = new MaxwellBootstrapUtilityConfig(new String[] {
			"--host=maxwell-host", "--ssl=VERIFY_CA",
			"--replication_host=repl-host", "--database=shard_1", "--table=minimal"
		});

		assertThat(config.replicationMysql.sslMode, is(SSLMode.VERIFY_CA));
		assertThat(config.getReplicationConnectionURI(), startsWith("jdbc:mysql://repl-host:3306/shard_1?"));
		assertThat(config.getReplicationConnectionURI(), containsString("sslMode=VERIFY_CA"));
	}

	@Test
	public void testReplicationSSLOverride() {
		MaxwellBootstrapUtilityConfig config = new MaxwellBootstrapUtilityConfig(new String[] {
			"--host=maxwell-host", "--ssl=VERIFY_IDENTITY",
			"--replication_host=repl-host", "--replication_ssl=REQUIRED", "--database=shard_1", "--table=minimal"
		});

		assertThat(config.getConnectionURI(), containsString("sslMode=VERIFY_IDENTITY"));
		assertThat(config.getReplicationConnectionURI(), containsString("sslMode=REQUIRED"));
	}

	@Test
	public void testReplicationSSLOverrideOnSameServer() {
		MaxwellBootstrapUtilityConfig config = new MaxwellBootstrapUtilityConfig(new String[] {
			"--host=maxwell-host", "--ssl=REQUIRED", "--replication_ssl=VERIFY_IDENTITY", "--database=shard_1", "--table=minimal"
		});

		assertThat(config.mysql.sslMode, is(SSLMode.REQUIRED));
		assertThat(config.getConnectionURI(), containsString("sslMode=REQUIRED"));
		assertThat(config.getReplicationConnectionURI(), startsWith("jdbc:mysql://maxwell-host:3306/shard_1?"));
		assertThat(config.getReplicationConnectionURI(), containsString("sslMode=VERIFY_IDENTITY"));
	}

	@Test
	public void testNoSSLOptionLeavesDriverDefault() {
		MaxwellBootstrapUtilityConfig config = new MaxwellBootstrapUtilityConfig(new String[] {
			"--host=maxwell-host", "--database=shard_1", "--table=minimal"
		});

		assertThat(config.getConnectionURI(), not(containsString("sslMode=")));
	}
}
