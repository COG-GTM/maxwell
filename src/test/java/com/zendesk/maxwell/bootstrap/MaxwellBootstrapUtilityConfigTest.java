package com.zendesk.maxwell.bootstrap;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertThat;

public class MaxwellBootstrapUtilityConfigTest {
	private static final String[] BASE_ARGS = { "--host=db.example", "--database=shard_1", "--table=minimal" };

	private MaxwellBootstrapUtilityConfig config(String... extra) {
		String[] argv = Arrays.copyOf(BASE_ARGS, BASE_ARGS.length + extra.length);
		System.arraycopy(extra, 0, argv, BASE_ARGS.length, extra.length);
		return new MaxwellBootstrapUtilityConfig(argv);
	}

	private Set<String> params(String uri) {
		return new HashSet<>(Arrays.asList(uri.split("\\?")[1].split("&")));
	}

	@Test
	public void testSSLModeIsAppliedToBootstrapConnections() {
		MaxwellBootstrapUtilityConfig config = config("--ssl=VERIFY_IDENTITY");

		assertThat(config.getConnectionURI(), startsWith("jdbc:mysql://db.example:3306/maxwell?"));
		assertThat(params(config.getConnectionURI()), hasItem("sslMode=VERIFY_IDENTITY"));
		assertThat(params(config.getReplicationConnectionURI()), hasItem("sslMode=VERIFY_IDENTITY"));
		assertThat(params(config.getConnectionURI()), not(hasItem(startsWith("verifyServerCertificate="))));
	}

	@Test
	public void testSeparateReplicationHostInheritsSSLMode() {
		MaxwellBootstrapUtilityConfig config = config("--ssl=VERIFY_CA", "--replication_host=replica.example");
		assertThat(params(config.getReplicationConnectionURI()), hasItem("sslMode=VERIFY_CA"));

		config = config("--ssl=VERIFY_CA", "--replication_host=replica.example", "--replication_ssl=VERIFY_IDENTITY");
		assertThat(params(config.getReplicationConnectionURI()), hasItem("sslMode=VERIFY_IDENTITY"));
	}

	@Test
	public void testNoPublicKeyRetrievalByDefault() {
		MaxwellBootstrapUtilityConfig config = config();
		assertThat(params(config.getConnectionURI()), not(hasItem(startsWith("allowPublicKeyRetrieval="))));
		assertThat(params(config.getReplicationConnectionURI()), not(hasItem(startsWith("allowPublicKeyRetrieval="))));
	}
}
