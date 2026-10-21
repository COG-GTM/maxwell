package com.zendesk.maxwell.schema.ddl;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL executable comments: {@code /*! body *&#47;} always runs, while
 * {@code /*!NNNNN body *&#47;} only runs when the server version (in {@code Mmmpp} form)
 * is at least NNNNN; otherwise the whole comment is ignored by the server.
 * Maxwell has to make the same decision or it parses a different statement than MySQL ran.
 */
public final class ExecutableComment {
	/** server version id that treats every executable comment as active */
	public static final int ALL_VERSIONS = Integer.MAX_VALUE;

	private static final int OPENER_LENGTH = "/*!".length();
	private static final Pattern VERSIONED_COMMENT = Pattern.compile("/\\*!(\\d{5,})(.*?)\\*/", Pattern.DOTALL);

	private ExecutableComment() { }

	/**
	 * @param opener the comment opener as lexed, e.g. {@code /*!80016}
	 * @param serverVersionId server version in {@code Mmmpp} form
	 * @return whether MySQL executes the body of this comment
	 */
	public static boolean isActive(String opener, int serverVersionId) {
		Integer version = versionOf(opener.substring(Math.min(OPENER_LENGTH, opener.length())));
		return version == null || version <= serverVersionId;
	}

	/**
	 * Remove executable comments the server would not run, so that regex-based
	 * statement classification sees the same statement as MySQL.
	 */
	public static String stripInactive(String sql, int serverVersionId) {
		if ( serverVersionId == ALL_VERSIONS || sql.indexOf("/*!") < 0 )
			return sql;

		Matcher m = VERSIONED_COMMENT.matcher(sql);
		StringBuffer sb = new StringBuffer();
		while ( m.find() ) {
			String replacement = isActive("/*!" + m.group(1), serverVersionId) ? m.group() : " ";
			m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	/* MySQL needs exactly five digits for a version; six-digit versions (MariaDB 10+, MySQL 10+) are also accepted. */
	private static Integer versionOf(String digits) {
		if ( digits.length() < 5 )
			return null;
		return Integer.valueOf(digits.substring(0, Math.min(6, digits.length())));
	}
}
