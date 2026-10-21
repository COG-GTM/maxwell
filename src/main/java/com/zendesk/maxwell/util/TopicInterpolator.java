package com.zendesk.maxwell.util;

import com.zendesk.maxwell.row.RowIdentity;
import com.zendesk.maxwell.row.RowMap;

import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * An utility class to interpolate subscription, channel templates.
 * <p>
 * An input string can contains any combination of:
 * - %{db}
 * - %{table}
 * - %{type}
 */
public class TopicInterpolator {
	private static final Pattern LEGACY_BACKSLASH_ESCAPE = Pattern.compile("\\\\(.)", Pattern.DOTALL);

	private final String inputString;

	private final boolean isInterpolated;

	public TopicInterpolator(final String templateString) {
		this.inputString = templateString;
		this.isInterpolated = templateString.contains("%{");
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
			return interpolate(r.getDatabase(), table, type, true).replaceAll("\\s+", "");
		else
			return this.inputString;
	}

	/**
	 * Interpolate a kafka topic name based on {{{@link RowMap}}}.  Characters in the substituted
	 * values that kafka does not allow in topic names are replaced with '_'.
	 *
	 * @param r the rowMap
	 * @return the interpolated topic name
	 */
	public String generateKafkaTopicFromRowMap(RowMap r) {
		if (this.isInterpolated)
			return interpolate(r.getDatabase(), r.getTable(), r.getRowType(), TopicInterpolator::cleanupIllegalKafkaTopicCharacters);
		else
			return this.inputString;
	}

	/**
	 * Interpolate a kafka topic name based on {{{@link RowIdentity}}}.  Characters in the substituted
	 * values that kafka does not allow in topic names are replaced with '_'.
	 *
	 * @param pk the rowIdentity
	 * @return the interpolated topic name
	 */
	public String generateKafkaTopicFromRowIdentity(RowIdentity pk) {
		if (this.isInterpolated)
			return interpolate(pk.getDatabase(), pk.getTable(), null, TopicInterpolator::cleanupIllegalKafkaTopicCharacters);
		else
			return this.inputString;
	}

	protected String interpolate(final String database, final String table, final String type, final Boolean cleanUpIllegalCharacters) {
		if (cleanUpIllegalCharacters)
			return interpolate(database, table, type, TopicInterpolator::cleanupIllegalCharacters);
		else
			return interpolate(database, table, type, UnaryOperator.identity());
	}

	/*
	 * Substitutions use String.replace, which is literal: database and table names may legally contain
	 * '$' and '\', which String.replaceAll would interpret as regex replacement metacharacters.
	 */
	private String interpolate(final String database, final String table, final String type, final UnaryOperator<String> cleanup) {
		if (this.isInterpolated) {
			return this.inputString
					.replace("%{database}", cleanup.apply(emptyStringOnNull(database)))
					.replace("%{table}", cleanup.apply(emptyStringOnNull(table)))
					.replace("%{type}", cleanup.apply(emptyStringOnNull(type)));
		} else {
			return this.inputString;
		}
	}

	private static String emptyStringOnNull(final String value) {
		if (value == null) {
			return "";
		} else {
			return value;
		}
	}

	private static String cleanupIllegalCharacters(final String value) {
		return value.replaceAll("([^A-Za-z0-9])", "_");
	}

	/*
	 * Topics used to be built with String.replaceAll, which dropped a backslash and kept the character after it
	 * (table `a\b` -> topic `ab`).  Keep doing that so existing topics don't move; a trailing backslash used to
	 * throw and now becomes '_' like any other illegal character.
	 */
	private static String cleanupIllegalKafkaTopicCharacters(final String value) {
		return LEGACY_BACKSLASH_ESCAPE.matcher(value).replaceAll("$1").replaceAll("[^A-Za-z0-9._-]", "_");
	}
}
