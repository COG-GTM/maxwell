package com.zendesk.maxwell.filtering;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Best-effort rejection of filter regexes prone to catastrophic backtracking.
 * <p>
 * Used for filters that arrive from untrusted sources (the http /config endpoint).
 * Rejects patterns longer than {@link #MAX_PATTERN_LENGTH} and patterns where a
 * repeated group itself contains a repeated element, e.g. <code>(a+)+</code>,
 * <code>(a*b?)*</code> or <code>((ab)+){2,}</code>.
 * </p>
 */
public class FilterRegexGuard {
	public static final int MAX_PATTERN_LENGTH = 1024;

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
		if ( hasNestedQuantifier(regex) )
			throw new InvalidFilterException("pattern contains a nested quantifier (possible catastrophic backtracking): /" + regex + "/");
	}

	static boolean hasNestedQuantifier(String regex) {
		Deque<Boolean> groupHasRepeat = new ArrayDeque<>();
		groupHasRepeat.push(false);

		int i = 0;
		int len = regex.length();
		while ( i < len ) {
			char c = regex.charAt(i);
			switch ( c ) {
				case '\\':
					if ( i + 1 < len && regex.charAt(i + 1) == 'Q' ) {
						int end = regex.indexOf("\\E", i + 2);
						i = end < 0 ? len : end + 2;
					} else {
						i += 2;
					}
					break;
				case '[':
					i = skipCharClass(regex, i);
					break;
				case '(':
					groupHasRepeat.push(false);
					i++;
					break;
				case ')': {
					boolean innerRepeats = groupHasRepeat.size() > 1 ? groupHasRepeat.pop() : false;
					int quantEnd = quantifierEnd(regex, i + 1);
					boolean groupRepeated = quantEnd > i + 1 && isRepeatingQuantifier(regex.substring(i + 1, quantEnd));
					if ( innerRepeats && groupRepeated )
						return true;
					if ( innerRepeats || groupRepeated )
						markRepeat(groupHasRepeat);
					i = Math.max(quantEnd, i + 1);
					break;
				}
				case '*':
				case '+':
				case '{': {
					int quantEnd = quantifierEnd(regex, i);
					if ( quantEnd > i ) {
						if ( isRepeatingQuantifier(regex.substring(i, quantEnd)) )
							markRepeat(groupHasRepeat);
						i = quantEnd;
					} else {
						i++;
					}
					break;
				}
				default:
					i++;
			}
		}
		return false;
	}

	private static void markRepeat(Deque<Boolean> stack) {
		stack.pop();
		stack.push(true);
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

	private static boolean greaterThanOne(String digits) {
		String trimmed = digits.replaceFirst("^0+", "");
		return trimmed.length() > 1 || (trimmed.length() == 1 && trimmed.charAt(0) > '1');
	}
}
