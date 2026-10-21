package com.zendesk.maxwell.filtering;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Best-effort rejection of filter regexes prone to catastrophic backtracking.
 * <p>
 * Used for filters that arrive from untrusted sources (the http /config endpoint).
 * Rejects patterns that are longer than {@link #MAX_PATTERN_LENGTH}, that repeat a
 * group which itself contains a repeated element (<code>(a+)+</code>, <code>((ab)+){2,}</code>),
 * that place two unbounded quantifiers back to back (<code>a*a*</code>, <code>\\s*\\w+</code>),
 * or that use more than {@link #MAX_UNBOUNDED_QUANTIFIERS} unbounded quantifiers.
 * </p>
 */
public class FilterRegexGuard {
	public static final int MAX_PATTERN_LENGTH = 1024;
	public static final int MAX_UNBOUNDED_QUANTIFIERS = 3;

	public static void check(List<FilterPattern> patterns) throws InvalidFilterException {
		for ( FilterPattern p : patterns ) {
			check(p.getDatabasePattern());
			check(p.getTablePattern());
			if ( p instanceof FilterColumnPattern )
				check(((FilterColumnPattern) p).getColumnPattern());
		}
	}

	private static void check(Pattern pattern) throws InvalidFilterException {
		String regex = pattern.pattern();
		if ( regex.length() > MAX_PATTERN_LENGTH )
			throw new InvalidFilterException("pattern exceeds " + MAX_PATTERN_LENGTH + " characters");
		String risk = backtrackingRisk(regex);
		if ( risk != null )
			throw new InvalidFilterException("pattern contains " + risk + " (possible catastrophic backtracking): /" + regex + "/");
	}

	static boolean hasNestedQuantifier(String regex) {
		return "a nested quantifier".equals(backtrackingRisk(regex));
	}

	/* returns a description of the backtracking risk found in regex, or null */
	static String backtrackingRisk(String regex) {
		// per group level: [0] = contains a repeated element, [1] = previous element has an unbounded quantifier
		Deque<boolean[]> levels = new ArrayDeque<>();
		levels.push(new boolean[2]);
		int unboundedCount = 0;

		int i = 0;
		int len = regex.length();
		while ( i < len ) {
			char c = regex.charAt(i);
			int atomEnd;
			boolean innerRepeats = false;
			boolean groupClose = false;
			switch ( c ) {
				case '\\':
					if ( i + 1 < len && regex.charAt(i + 1) == 'Q' ) {
						int end = regex.indexOf("\\E", i + 2);
						atomEnd = end < 0 ? len : end + 2;
					} else {
						atomEnd = Math.min(i + 2, len);
					}
					break;
				case '[':
					atomEnd = skipCharClass(regex, i);
					break;
				case '(':
					levels.push(new boolean[2]);
					i++;
					continue;
				case ')':
					innerRepeats = levels.size() > 1 && levels.pop()[0];
					groupClose = true;
					atomEnd = i + 1;
					break;
				case '|':
					levels.peek()[1] = false;
					i++;
					continue;
				default:
					atomEnd = i + 1;
			}

			int quantEnd = quantifierEnd(regex, atomEnd);
			String quantifier = quantEnd > atomEnd ? regex.substring(atomEnd, quantEnd) : null;
			boolean repeating = quantifier != null && isRepeatingQuantifier(quantifier);
			boolean unbounded = quantifier != null && isUnboundedQuantifier(quantifier);

			if ( groupClose && innerRepeats && repeating )
				return "a nested quantifier";

			boolean[] level = levels.peek();
			if ( unbounded ) {
				if ( level[1] )
					return "adjacent unbounded quantifiers";
				if ( ++unboundedCount > MAX_UNBOUNDED_QUANTIFIERS )
					return "more than " + MAX_UNBOUNDED_QUANTIFIERS + " unbounded quantifiers";
			}
			level[1] = unbounded;
			if ( repeating || innerRepeats )
				level[0] = true;
			i = quantEnd;
		}
		return null;
	}

	private static int skipCharClass(String regex, int start) {
		int depth = 0;
		int i = start;
		while ( i < regex.length() ) {
			char c = regex.charAt(i);
			if ( c == '\\' ) {
				i += 2;
				continue;
			}
			if ( c == '[' ) {
				depth++;
			} else if ( c == ']' && i > start + 1 ) {
				depth--;
				if ( depth == 0 )
					return i + 1;
			}
			i++;
		}
		return regex.length();
	}

	/* returns the index just past a quantifier (incl. lazy/possessive suffix) starting at i, or i if none */
	private static int quantifierEnd(String regex, int i) {
		if ( i >= regex.length() )
			return i;
		char c = regex.charAt(i);
		int end;
		if ( c == '*' || c == '+' || c == '?' ) {
			end = i + 1;
		} else if ( c == '{' ) {
			int close = regex.indexOf('}', i);
			if ( close < 0 || !regex.substring(i + 1, close).matches("\\d+(,\\d*)?") )
				return i;
			end = close + 1;
		} else {
			return i;
		}
		if ( end < regex.length() && (regex.charAt(end) == '?' || regex.charAt(end) == '+') )
			end++;
		return end;
	}

	private static boolean isRepeatingQuantifier(String q) {
		char c = q.charAt(0);
		if ( c == '*' || c == '+' )
			return true;
		if ( c != '{' )
			return false;
		String body = q.substring(1, q.indexOf('}'));
		String[] parts = body.split(",", -1);
		if ( parts.length == 1 )
			return greaterThanOne(parts[0]);
		return parts[1].isEmpty() || greaterThanOne(parts[1]);
	}

	private static boolean isUnboundedQuantifier(String q) {
		char c = q.charAt(0);
		return c == '*' || c == '+' || (c == '{' && q.matches("\\{\\d+,\\}.*"));
	}

	private static boolean greaterThanOne(String digits) {
		String trimmed = digits.replaceFirst("^0+", "");
		return trimmed.length() > 1 || (trimmed.length() == 1 && trimmed.charAt(0) > '1');
	}
}
