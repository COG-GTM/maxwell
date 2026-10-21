package com.zendesk.maxwell;

import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.github.shyiko.mysql.binlog.network.SSLMode;

import org.apache.http.client.utils.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration object describing a mysql connection
 */
public class MaxwellMysqlConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger(MaxwellMysqlConfig.class);

	public static final String JDBC_SSL_MODE = "sslMode";
	public static final String JDBC_ALLOW_PUBLIC_KEY_RETRIEVAL = "allowPublicKeyRetrieval";

	// pre-8.0.13 Connector/J flags superseded by sslMode
	private static final List<String> LEGACY_JDBC_SSL_OPTIONS = Arrays.asList("useSSL", "requireSSL", "verifyServerCertificate");

	private static final List<SSLMode> SSL_MODES_BY_STRENGTH = Arrays.asList(
		SSLMode.DISABLED, SSLMode.PREFERRED, SSLMode.REQUIRED, SSLMode.VERIFY_CA, SSLMode.VERIFY_IDENTITY
	);

	public String host;
	public Integer port;
	public String database;
	public String user;
	public String password;
	public SSLMode sslMode;
	/**
	 * determines whether we enable replication heartbeats.  Different from maxwell heartbeats
	 */
	public boolean enableHeartbeat;
	/**
	 * Hashmap of key/value JDBC driver options.  Not used for replication connections, generally.
	 */
	public Map<String, String> jdbcOptions;
	/**
	 * Connection timeout for JDBC connections
	 */
	public Integer connectTimeoutMS = 5000;

	/**
	 * Instantiate a default connection config
	 */
	public MaxwellMysqlConfig() {
		this.host = null;
		this.port = null;
		this.database = null;
		this.user = null;
		this.password = null;
		this.sslMode = null;
		this.enableHeartbeat = false;

		this.jdbcOptions = new HashMap<>();
		this.jdbcOptions.put("zeroDateTimeBehavior", "convertToNull");
		this.jdbcOptions.put("connectTimeout", String.valueOf(connectTimeoutMS));
	}

	/**
	 * Instantiate a mysql connection config
	 * @param host Mysql Host
	 * @param port Mysql port
	 * @param database Database name
	 * @param user User
	 * @param password Password
	 * @param sslMode SSL connection mode
	 * @param enableHeartbeat Replication heartbeats
	 */
	public MaxwellMysqlConfig(String host, Integer port, String database, String user, String password,
			SSLMode sslMode, boolean enableHeartbeat) {
		this();
		this.host = host;
		this.port = port;
		this.database = database;
		this.user = user;
		this.password = password;
		this.sslMode = sslMode;
		this.enableHeartbeat = enableHeartbeat;
	}

	/**
	 * Clone a mysql config
	 * @param c the config to clone
	 */
	public MaxwellMysqlConfig(MaxwellMysqlConfig c) {
		this();
		this.host = c.host;
		this.port = c.port;
		this.database = c.database;
		this.user = c.user;
		this.password = c.password;
		this.sslMode = c.sslMode;
	}

	/**
	 * Parse JDBC options from a key=val&amp;key2=val2 string
	 * @param opts string to parse
	 */
	public void setJDBCOptions(String opts) {
		if (opts == null)
			return;

		for ( String opt : opts.split("&") ) {
			String[] valueKeySplit = opt.trim().split("=", 2);
			if (valueKeySplit.length == 2) {
				this.jdbcOptions.put(valueKeySplit[0], valueKeySplit[1]);
			}
		}
	}

	/**
	 * JDBC driver options for a connection: jdbcOptions plus Connector/J's {@code sslMode},
	 * mapped one-to-one from {@link #sslMode}.  jdbcOptions itself is not modified.
	 *
	 * If jdbcOptions also sets {@code sslMode}, the stricter of the two modes wins.
	 * {@code allowPublicKeyRetrieval=true} is only passed through when the connection is
	 * guaranteed to use TLS; otherwise an on-path attacker could answer the driver's
	 * public key request with their own RSA key and recover the password.
	 *
	 * @return a new map of JDBC options
	 */
	public Map<String, String> getJDBCConnectionOptions() {
		Map<String, String> options = new LinkedHashMap<>(jdbcOptions);

		SSLMode effective = effectiveSSLMode(options.get(JDBC_SSL_MODE));
		if ( effective != null ) {
			LEGACY_JDBC_SSL_OPTIONS.forEach(options::remove);
			options.put(JDBC_SSL_MODE, effective.name());
		}

		if ( "true".equalsIgnoreCase(options.get(JDBC_ALLOW_PUBLIC_KEY_RETRIEVAL)) && !requiresTLS(effective) ) {
			LOGGER.warn("ignoring allowPublicKeyRetrieval=true for mysql {}:{}: it requires ssl=REQUIRED|VERIFY_CA|VERIFY_IDENTITY. "
				+ "Use serverRSAPublicKeyFile to authenticate with caching_sha2_password without TLS.", host, port);
			options.put(JDBC_ALLOW_PUBLIC_KEY_RETRIEVAL, "false");
		}

		return options;
	}

	private SSLMode effectiveSSLMode(String jdbcSSLMode) {
		SSLMode fromJdbc = parseSSLMode(jdbcSSLMode);
		if ( sslMode == null )
			return fromJdbc;
		if ( fromJdbc == null )
			return sslMode;
		return SSL_MODES_BY_STRENGTH.indexOf(fromJdbc) > SSL_MODES_BY_STRENGTH.indexOf(sslMode) ? fromJdbc : sslMode;
	}

	private static SSLMode parseSSLMode(String mode) {
		if ( mode == null )
			return null;
		for ( SSLMode m : SSLMode.values() ) {
			if ( m.name().equalsIgnoreCase(mode.trim()) )
				return m;
		}
		return null;
	}

	private static boolean requiresTLS(SSLMode mode) {
		return mode == SSLMode.REQUIRED || mode == SSLMode.VERIFY_CA || mode == SSLMode.VERIFY_IDENTITY;
	}

	/**
	 * Build a connection URI from the config
	 * @param includeDatabase whether to include the database name in th euri
	 * @return a connection URI string
	 * @throws URISyntaxException if we have problems building the URI
	 */
	public String getConnectionURI(boolean includeDatabase) throws URISyntaxException {
		URIBuilder uriBuilder = new URIBuilder();

		uriBuilder.setScheme("jdbc:mysql");
		uriBuilder.setHost(host);
		uriBuilder.setPort(port);

		if (database != null && includeDatabase) {
			uriBuilder.setPath("/" + database);
		}

		for (Map.Entry<String, String> jdbcOption : getJDBCConnectionOptions().entrySet()) {
			uriBuilder.addParameter(jdbcOption.getKey(), jdbcOption.getValue());
		}

		// added by d8888 2018/09/10, force JDBC to use UTF-8 to support using non-english db, table & column names
		uriBuilder.addParameter("characterEncoding", "UTF-8");
		uriBuilder.addParameter("tinyInt1isBit", "false");

		return uriBuilder.build().toString();
	}

	/**
	 * Build a connection URI from the config
	 * @return a connection URI
	 * @throws URISyntaxException if we have problems
	 */
	public String getConnectionURI() throws URISyntaxException { return getConnectionURI(true); }

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		MaxwellMysqlConfig that = (MaxwellMysqlConfig) o;
		return Objects.equals(host, that.host) &&
				Objects.equals(port, that.port) &&
				Objects.equals(database, that.database) &&
				Objects.equals(user, that.user) &&
				Objects.equals(password, that.password) &&
				sslMode == that.sslMode &&
				Objects.equals(jdbcOptions, that.jdbcOptions) &&
				Objects.equals(connectTimeoutMS, that.connectTimeoutMS);
	}

	public boolean sameServerAs(MaxwellMysqlConfig other) {
		return Objects.equals(host, other.host) &&
			Objects.equals(port, other.port);
	}

	@Override
	public int hashCode() {
		return Objects
				.hash(host, port, database, user, password, sslMode, jdbcOptions, connectTimeoutMS);
	}
}
