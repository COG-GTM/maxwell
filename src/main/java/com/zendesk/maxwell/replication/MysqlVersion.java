package com.zendesk.maxwell.replication;

import org.apache.arrow.flatbuf.Int;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MysqlVersion {
	private final int major;
	private final int minor;
	private final int versionId;
	public boolean isMariaDB = false;

	private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
	private static final String MARIADB_REPLICATION_PREFIX = "5.5.5-";
	/* when the patch level is unknown, assume the highest so no comment the server ran is skipped */
	private static final int UNKNOWN_PATCH = 99;

	public MysqlVersion(int major, int minor) {
		this(major, minor, toVersionId(major, minor, UNKNOWN_PATCH));
	}

	private MysqlVersion(int major, int minor, int versionId) {
		this.major = major;
		this.minor = minor;
		this.versionId = versionId;
	}

	public boolean atLeast(int major, int minor) {
		return (this.major > major) || (this.major == major && this.minor >= minor);
	}

	public boolean atLeast(MysqlVersion version) {
		return atLeast(version.major, version.minor);
	}

	public boolean lessThan(int major, int minor) {
		return (this.major < major) || (this.major == major & this.minor < minor);
	}

	public static MysqlVersion capture(Connection c) throws SQLException {
		DatabaseMetaData meta = c.getMetaData();
		int major = meta.getDatabaseMajorVersion();
		int minor = meta.getDatabaseMinorVersion();
		return new MysqlVersion(major, minor, parseVersionId(meta.getDatabaseProductVersion(), major, minor));
	}

	/**
	 * @return the server version in the {@code Mmmpp} form MySQL uses for {@code /*!NNNNN ... *&#47;} comments
	 */
	public int getVersionId() {
		return this.versionId;
	}

	/**
	 * Parse a server version string (e.g. "8.0.36-0ubuntu0.22.04.1", "5.5.5-10.6.12-MariaDB")
	 * into MySQL's {@code Mmmpp} version id, falling back to major/minor when it can't be parsed.
	 */
	static int parseVersionId(String productVersion, int fallbackMajor, int fallbackMinor) {
		if ( productVersion != null ) {
			String v = productVersion.trim();
			if ( v.toLowerCase().contains("mariadb") && v.startsWith(MARIADB_REPLICATION_PREFIX) )
				v = v.substring(MARIADB_REPLICATION_PREFIX.length());

			Matcher m = VERSION_PATTERN.matcher(v);
			if ( m.find() ) {
				try {
					int patch = m.group(3) == null ? UNKNOWN_PATCH : Integer.parseInt(m.group(3));
					return toVersionId(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), patch);
				} catch ( NumberFormatException e ) {
					// fall through
				}
			}
		}
		return toVersionId(fallbackMajor, fallbackMinor, UNKNOWN_PATCH);
	}

	private static int toVersionId(int major, int minor, int patch) {
		return major * 10000 + Math.min(Math.max(minor, 0), 99) * 100 + Math.min(Math.max(patch, 0), 99);
	}

	public int getMajor() {
		return this.major;
	}

	public int getMinor() {
		return this.minor;
	}

	public static MysqlVersion parse(String versionString) {
		if ( versionString.equals("mariadb") ) {
			MysqlVersion v = new MysqlVersion(0, 0);
			v.isMariaDB = true;
			return v;
		}


		String[] split = versionString.split("\\.");
		if ( split.length < 2 ) {
			throw new IllegalArgumentException("Invalid version string: " + versionString + ". Expected at least major and minor versions separated by a dot.");
		}
		return new MysqlVersion(Integer.parseInt(split[0]), Integer.parseInt(split[1]));
	}

}
