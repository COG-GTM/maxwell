package com.zendesk.maxwell;

import com.github.shyiko.mysql.binlog.network.SSLMode;
import org.junit.Test;

import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertThat;

public class MaxwellMysqlConfigTest {

	private MaxwellMysqlConfig buildConfig(SSLMode sslMode) {
		MaxwellMysqlConfig config = new MaxwellMysqlConfig();
		config.host = "localhost";
		config.port = 3306;
		config.user = "maxwell";
		config.password = "maxwell";
		config.database = "maxwell";
		config.sslMode = sslMode;
		return config;
	}

	private Set<String> uriParams(MaxwellMysqlConfig config) throws URISyntaxException {
		return new HashSet<>(Arrays.asList(config.getConnectionURI().split("\\?")[1].split("&")));
	}

	@Test
	public void testBasicUri() throws URISyntaxException {
		MaxwellMysqlConfig config = buildConfig(SSLMode.DISABLED);
		config.setJDBCOptions("autoReconnect=true&initialTimeout=2&maxReconnects=10");

		assertThat(config.getConnectionURI(), is(equalTo("jdbc:mysql://localhost:3306/maxwell?connectTimeout=5000&zeroDateTimeBehavior=convertToNull&initialTimeout=2&autoReconnect=true&maxReconnects=10&sslMode=DISABLED&characterEncoding=UTF-8&tinyInt1isBit=false")));
	}

	@Test
	public void testSSLModeIsPassedThroughOneToOne() throws URISyntaxException {
		for ( SSLMode mode : SSLMode.values() ) {
			Set<String> params = uriParams(buildConfig(mode));
			assertThat(params, hasItem("sslMode=" + mode.name()));
			assertThat(params, not(hasItem(startsWith("useSSL="))));
			assertThat(params, not(hasItem(startsWith("requireSSL="))));
			assertThat(params, not(hasItem(startsWith("verifyServerCertificate="))));
		}
	}

	@Test
	public void testNullSSLModeLeavesDriverDefault() throws URISyntaxException {
		assertThat(uriParams(buildConfig(null)), not(hasItem(startsWith("sslMode="))));
	}

	@Test
	public void testLegacySSLFlagsAreDroppedWhenSSLModeIsSet() throws URISyntaxException {
		MaxwellMysqlConfig config = buildConfig(SSLMode.VERIFY_CA);
		config.setJDBCOptions("useSSL=false&requireSSL=false&verifyServerCertificate=false");

		Set<String> params = uriParams(config);
		assertThat(params, hasItem("sslMode=VERIFY_CA"));
		assertThat(params, not(hasItem(startsWith("useSSL="))));
		assertThat(params, not(hasItem(startsWith("requireSSL="))));
		assertThat(params, not(hasItem(startsWith("verifyServerCertificate="))));
	}

	@Test
	public void testStricterJdbcSSLModeWins() throws URISyntaxException {
		MaxwellMysqlConfig config = buildConfig(SSLMode.DISABLED);
		config.setJDBCOptions("sslMode=verify_identity");
		assertThat(uriParams(config), hasItem("sslMode=VERIFY_IDENTITY"));
	}

	@Test
	public void testWeakerJdbcSSLModeDoesNotDowngrade() throws URISyntaxException {
		MaxwellMysqlConfig config = buildConfig(SSLMode.VERIFY_IDENTITY);
		config.setJDBCOptions("sslMode=DISABLED");
		assertThat(uriParams(config), hasItem("sslMode=VERIFY_IDENTITY"));
	}

	@Test
	public void testPublicKeyRetrievalIsNotEnabledByDefault() throws URISyntaxException {
		for ( SSLMode mode : SSLMode.values() ) {
			assertThat(uriParams(buildConfig(mode)), not(hasItem(startsWith("allowPublicKeyRetrieval="))));
		}
		assertThat(uriParams(buildConfig(null)), not(hasItem(startsWith("allowPublicKeyRetrieval="))));
	}

	@Test
	public void testPublicKeyRetrievalIsRefusedWithoutRequiredSSL() throws URISyntaxException {
		for ( SSLMode mode : new SSLMode[] { null, SSLMode.DISABLED, SSLMode.PREFERRED } ) {
			MaxwellMysqlConfig config = buildConfig(mode);
			config.setJDBCOptions("allowPublicKeyRetrieval=true");
			Set<String> params = uriParams(config);
			assertThat(params, hasItem("allowPublicKeyRetrieval=false"));
			assertThat(params, not(hasItem("allowPublicKeyRetrieval=true")));
		}
	}

	@Test
	public void testPublicKeyRetrievalIsAllowedWithRequiredSSL() throws URISyntaxException {
		for ( SSLMode mode : new SSLMode[] { SSLMode.REQUIRED, SSLMode.VERIFY_CA, SSLMode.VERIFY_IDENTITY } ) {
			MaxwellMysqlConfig config = buildConfig(mode);
			config.setJDBCOptions("allowPublicKeyRetrieval=true");
			assertThat(uriParams(config), hasItem("allowPublicKeyRetrieval=true"));
		}

		MaxwellMysqlConfig config = buildConfig(SSLMode.DISABLED);
		config.setJDBCOptions("sslMode=REQUIRED&allowPublicKeyRetrieval=true");
		assertThat(uriParams(config), hasItem("allowPublicKeyRetrieval=true"));
	}

	@Test
	public void testBuildingTheUriDoesNotMutateJdbcOptions() throws URISyntaxException {
		MaxwellMysqlConfig config = buildConfig(SSLMode.VERIFY_CA);
		config.setJDBCOptions("allowPublicKeyRetrieval=true");
		MaxwellMysqlConfig before = buildConfig(SSLMode.VERIFY_CA);
		before.setJDBCOptions("allowPublicKeyRetrieval=true");

		config.getConnectionURI();
		assertThat(config.jdbcOptions, is(equalTo(before.jdbcOptions)));
	}
}
