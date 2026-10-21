package com.zendesk.maxwell;

import com.github.shyiko.mysql.binlog.network.SSLMode;
import org.junit.Test;

import java.net.URISyntaxException;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class MaxwellMysqlConfigTest {

	@Test
	public void testBasicUri() {

		MaxwellMysqlConfig config = new MaxwellMysqlConfig();

		config.host = "localhost";
		config.port = 3306;
		config.user = "maxwell";
		config.password = "maxwell";
		config.database = "maxwell";
		config.sslMode = SSLMode.DISABLED;
		config.setJDBCOptions("autoReconnect=true&initialTimeout=2&maxReconnects=10");

		try {
			final String uri = config.getConnectionURI();
			assertThat(uri, is(equalTo("jdbc:mysql://localhost:3306/maxwell?allowPublicKeyRetrieval=true&connectTimeout=5000&zeroDateTimeBehavior=convertToNull&initialTimeout=2&autoReconnect=true&maxReconnects=10&sslMode=DISABLED&characterEncoding=UTF-8&tinyInt1isBit=false")));
		}
		catch (URISyntaxException e) {
			fail();
		}

	}

	private static MaxwellMysqlConfig configWithSSL(SSLMode mode) {
		MaxwellMysqlConfig config = new MaxwellMysqlConfig();
		config.host = "localhost";
		config.port = 3306;
		config.user = "maxwell";
		config.password = "maxwell";
		config.database = "maxwell";
		config.sslMode = mode;
		return config;
	}

	private static void assertSSLMode(SSLMode mode, String expectedSslMode) throws URISyntaxException {
		String uri = configWithSSL(mode).getConnectionURI();
		assertThat(uri, containsString("sslMode=" + expectedSslMode));
		assertThat(uri, not(containsString("useSSL=")));
		assertThat(uri, not(containsString("requireSSL=")));
		assertThat(uri, not(containsString("verifyServerCertificate=")));
	}

	@Test
	public void testSSLDisabled() throws URISyntaxException {
		assertSSLMode(SSLMode.DISABLED, "DISABLED");
	}

	@Test
	public void testSSLPreferred() throws URISyntaxException {
		assertSSLMode(SSLMode.PREFERRED, "PREFERRED");
	}

	@Test
	public void testSSLRequired() throws URISyntaxException {
		assertSSLMode(SSLMode.REQUIRED, "REQUIRED");
	}

	@Test
	public void testSSLVerifyCA() throws URISyntaxException {
		assertSSLMode(SSLMode.VERIFY_CA, "VERIFY_CA");
	}

	@Test
	public void testSSLVerifyId() throws URISyntaxException {
		assertSSLMode(SSLMode.VERIFY_IDENTITY, "VERIFY_IDENTITY");
	}

	@Test
	public void testNullSSLModeLeavesDriverDefault() throws URISyntaxException {
		String uri = configWithSSL(null).getConnectionURI();
		assertThat(uri, not(containsString("sslMode=")));
		assertThat(uri, not(containsString("useSSL=")));
	}

	@Test
	public void testJdbcSslModeCannotWeakenSSLOption() throws URISyntaxException {
		MaxwellMysqlConfig config = configWithSSL(SSLMode.VERIFY_IDENTITY);
		config.setJDBCOptions("sslMode=REQUIRED");
		String uri = config.getConnectionURI();
		assertThat(uri, containsString("sslMode=VERIFY_IDENTITY"));
		assertThat(uri, not(containsString("sslMode=REQUIRED")));
	}

	@Test
	public void testStricterJdbcSslModeIsKept() throws URISyntaxException {
		MaxwellMysqlConfig config = configWithSSL(SSLMode.DISABLED);
		config.setJDBCOptions("sslMode=verify_ca");
		assertThat(config.getConnectionURI(), containsString("sslMode=VERIFY_CA"));
	}

	@Test
	public void testConnectionURIDoesNotMutateJdbcOptions() throws URISyntaxException {
		MaxwellMysqlConfig config = configWithSSL(SSLMode.VERIFY_CA);
		config.getConnectionURI();
		assertThat(config.jdbcOptions.containsKey("sslMode"), is(false));
		assertThat(config.jdbcOptions.containsKey("useSSL"), is(false));
	}

}
