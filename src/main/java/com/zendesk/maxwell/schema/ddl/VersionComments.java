package com.zendesk.maxwell.schema.ddl;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mirrors MySQL's handling of version-gated executable comments ({@code /*!NNNNN ... *}{@code /}):
 * the body is executed only when NNNNN is less than or equal to the server's version id
 * (major * 10000 + minor * 100 + patch).
 */
public final class VersionComments {
	private static final Pattern VERSIONED_COMMENT = Pattern.compile("/\\*!(\\d+).*?\\*/", Pattern.DOTALL);

	private VersionComments() { }

	public static boolean isInactive(String digits, Integer serverVersionId) {
		if ( serverVersionId == null || digits == null || digits.isEmpty() )
			return false;

		if ( digits.length() > 9 )
			return true;

		return Long.parseLong(digits) > serverVersionId;
	}

	public static String stripInactive(String sql, Integer serverVersionId) {
		if ( serverVersionId == null )
			return sql;

		Matcher m = VERSIONED_COMMENT.matcher(sql);
		StringBuilder sb = new StringBuilder();
		int last = 0;
		while ( m.find() ) {
			if ( isInactive(m.group(1), serverVersionId) ) {
				sb.append(sql, last, m.start()).append(' ');
				last = m.end();
			}
		}
		sb.append(sql, last, sql.length());
		return sb.toString();
	}
}
