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
	public boolean isMariaDB = false;

	public MysqlVersion(int major, int minor) {
		this.major = major;
		this.minor = minor;
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

	private static final Pattern PRODUCT_VERSION = Pattern.compile("^(?:5\\.5\\.5-)?(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

	/**
	 * Capture the server's version id in the form MySQL uses for executable comments,
	 * major * 10000 + minor * 100 + patch (e.g. 80035 for 8.0.35, 100612 for MariaDB 10.6.12).
	 */
	public static int captureVersionId(Connection c) throws SQLException {
		DatabaseMetaData meta = c.getMetaData();
		return versionId(meta.getDatabaseProductVersion(), meta.getDatabaseMajorVersion(), meta.getDatabaseMinorVersion());
	}

	public static int versionId(String productVersion, int fallbackMajor, int fallbackMinor) {
		if ( productVersion != null ) {
			Matcher m = PRODUCT_VERSION.matcher(productVersion.trim());
			if ( m.find() ) {
				int major = Integer.parseInt(m.group(1));
				int minor = Integer.parseInt(m.group(2));
				// unknown patch level: assume the highest so versioned comments are not wrongly ignored
				int patch = m.group(3) == null ? 99 : Math.min(Integer.parseInt(m.group(3)), 99);
				return major * 10000 + minor * 100 + patch;
			}
		}
		return fallbackMajor * 10000 + fallbackMinor * 100 + 99;
	}

	public static MysqlVersion capture(Connection c) throws SQLException {
		DatabaseMetaData meta = c.getMetaData();
		return new MysqlVersion(meta.getDatabaseMajorVersion(), meta.getDatabaseMinorVersion());
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
