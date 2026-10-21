package com.zendesk.maxwell.util;

import com.zendesk.maxwell.row.RowIdentity;
import com.zendesk.maxwell.row.RowMap;

import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An utility class to interpolate subscription, channel templates.
 * <p>
 * An input string can contains any combination of:
 * - %{database}
 * - %{table}
 * - %{type}
 * <p>
 * All placeholders are substituted in a single pass, so a substituted value can never introduce
 * a new placeholder.
 * <p>
 * When constructed with {@code escapeIdentifiers = true}, substituted values are encoded reversibly so
 * that distinct (database, table, type) tuples always yield distinct strings: every character of a
 * value that also appears as a separator in the template (and the escape character itself) is written
 * as the escape character followed by the two-digit upper-case hex code of each of its UTF-8 bytes.
 * The escape character is the first of {@code - _ .} that does not appear in the template.
 * For example with the template {@code ns_%{database}_%{table}}, database {@code shop_eu} and table
 * {@code orders} becomes {@code ns_shop-5Feu_orders}, while database {@code shop} and table
 * {@code eu_orders} becomes {@code ns_shop_eu-5Forders}.
 */
public class TopicInterpolator {
	private static final Pattern PLACEHOLDER = Pattern.compile("%\\{(database|table|type)\\}");

	private static final char[] ESCAPE_CANDIDATES = {'-', '_', '.'};

	private static final char[] HEX = "0123456789ABCDEF".toCharArray();

	private final String inputString;

	private final boolean isInterpolated;

	private final boolean escapeIdentifiers;

	private final Template rawTemplate;

	private final Template cleanTemplate;

	public TopicInterpolator(final String templateString) {
		this(templateString, false);
	}

	/**
	 * @param templateString    the template
	 * @param escapeIdentifiers encode substituted values so that the generated string is unambiguous
	 * @throws IllegalArgumentException if escapeIdentifiers is set and the template cannot be made unambiguous
	 */
	public TopicInterpolator(final String templateString, final boolean escapeIdentifiers) {
		this.inputString = templateString;
		this.isInterpolated = templateString.contains("%{");
		this.escapeIdentifiers = escapeIdentifiers;

		if (this.isInterpolated) {
			this.rawTemplate = new Template(templateString, escapeIdentifiers, false);
			this.cleanTemplate = new Template(templateString.replaceAll("\\s+", ""), escapeIdentifiers, true);
			if (escapeIdentifiers)
				this.rawTemplate.validate();
		} else {
			this.rawTemplate = null;
			this.cleanTemplate = null;
		}
	}

	/**
	 * Fail fast if this template is used with {@link #generateFromRowMapAndCleanUpIllegalCharacters}
	 * and escaping is enabled but the whitespace-stripped template is ambiguous.
	 *
	 * @return this
	 * @throws IllegalArgumentException if the cleaned-up template cannot be made unambiguous
	 */
	public TopicInterpolator validateForCleanUp() {
		if (this.isInterpolated && this.escapeIdentifiers)
			this.cleanTemplate.validate();
		return this;
	}

	/**
	 * Interpolate the input string based on {{{@link RowIdentity}}}
	 * <p>
	 * If your inputString contains a %{type} it will not be interpolated because we can't get type from RowIdentity.
	 *
	 * @param pk the rowIdentity
	 * @return the interpollated string
	 */
	public String generateFromRowIdentity(RowIdentity pk) {
		String table = pk.getTable();

		if (this.isInterpolated)
			return interpolate(pk.getDatabase(), table, null, false);
		else
			return this.inputString;
	}

	/**
	 * Interpolate the input string based on {{{@link RowMap}}}
	 *
	 * @param r the rowMap
	 * @return the interpollated string
	 */
	public String generateFromRowMap(RowMap r) {
		String table = r.getTable();

		String type = r.getRowType();

		if (this.isInterpolated)
			return interpolate(r.getDatabase(), table, type, false);
		else
			return this.inputString;
	}

	/**
	 * Interpolate the input string based on {{{@link RowMap}}} and clean up all non alpha-numeric characters
	 *
	 * @param r the rowMap
	 * @return the interpollated string
	 */
	public String generateFromRowMapAndCleanUpIllegalCharacters(RowMap r) {
		String table = r.getTable();
		String type = r.getRowType();

		if (this.isInterpolated)
			return interpolate(r.getDatabase(), table, type, true);
		else
			return this.inputString;
	}

	protected String interpolate(final String database, final String table, final String type, final Boolean cleanUpIllegalCharacters) {
		if (!this.isInterpolated)
			return this.inputString;

		final Template template = cleanUpIllegalCharacters ? this.cleanTemplate : this.rawTemplate;
		if (this.escapeIdentifiers)
			template.validate();

		final Matcher m = PLACEHOLDER.matcher(template.template);
		final StringBuffer sb = new StringBuffer();
		while (m.find()) {
			final String value;
			switch (m.group(1)) {
				case "database":
					value = database;
					break;
				case "table":
					value = table;
					break;
				default:
					value = type;
			}
			m.appendReplacement(sb, Matcher.quoteReplacement(template.encode(emptyStringOnNull(value))));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	private static String emptyStringOnNull(final String value) {
		if (value == null) {
			return "";
		} else {
			return value;
		}
	}

	private static boolean isAlphaNumeric(final int c) {
		return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
	}

	private static final class Template {
		private final String template;
		private final boolean escape;
		private final boolean cleanUp;
		private final BitSet separators = new BitSet(128);
		private final char escapeChar;
		private final String error;

		Template(final String template, final boolean escape, final boolean cleanUp) {
			this.template = template;
			this.escape = escape;
			this.cleanUp = cleanUp;

			final String literal = PLACEHOLDER.matcher(template).replaceAll("");
			literal.chars().filter(c -> !isAlphaNumeric(c)).forEach(separators::set);

			char chosen = 0;
			for (char c : ESCAPE_CANDIDATES) {
				if (!separators.get(c)) {
					chosen = c;
					break;
				}
			}
			this.escapeChar = chosen;
			this.error = escape ? findAmbiguity(chosen) : null;
		}

		private String findAmbiguity(final char chosen) {
			if (chosen == 0)
				return "template '" + template + "' uses all of '-', '_' and '.', leaving no escape character for topic_escape_identifiers";

			final Matcher m = PLACEHOLDER.matcher(template);
			while (m.find()) {
				final int next = m.end();
				if (next < template.length() && (isAlphaNumeric(template.charAt(next)) || PLACEHOLDER.matcher(template).region(next, template.length()).lookingAt()))
					return "template '" + template + "' is ambiguous: %{" + m.group(1)
						+ "} must be followed by a non-alphanumeric separator or the end of the template when topic_escape_identifiers is enabled";
			}
			return null;
		}

		void validate() {
			if (error != null)
				throw new IllegalArgumentException(error);
		}

		String encode(final String value) {
			if (!escape) {
				return cleanUp ? value.replaceAll("[^A-Za-z0-9]", "_") : value;
			}

			final StringBuilder sb = new StringBuilder(value.length());
			for (int i = 0; i < value.length(); ) {
				final int cp = value.codePointAt(i);
				i += Character.charCount(cp);

				final boolean mustEscape = cleanUp ? !isAlphaNumeric(cp) : (cp == escapeChar || (cp < 128 && separators.get(cp)));
				if (mustEscape) {
					for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
						sb.append(escapeChar).append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
					}
				} else {
					sb.appendCodePoint(cp);
				}
			}
			return sb.toString();
		}
	}
}
