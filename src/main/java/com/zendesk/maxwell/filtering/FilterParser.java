package com.zendesk.maxwell.filtering;

import com.amazonaws.util.StringInputStream;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StreamTokenizer;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static java.io.StreamTokenizer.*;

public class FilterParser {
	private static final String LITERAL_START = "\\A", LITERAL_END = "\\z";
	private static final String REGEX_METACHARS = "\\.[]{}()<>*+-=!?^$|&#~,:/ ";
	private static final Pattern BARE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_\\u00a0-\\u00ff]+");

	private StreamTokenizer tokenizer;
	private InputStreamReader inputStream;
	private final String input;

	public FilterParser(String input) {
		this.input = input;
	}

	public List<FilterPattern> parse() throws InvalidFilterException {
		try {
			this.inputStream = new InputStreamReader(new StringInputStream(input));
		} catch ( UnsupportedEncodingException e ) {
			throw new InvalidFilterException(e.getMessage());
		}

		this.tokenizer = new StreamTokenizer(inputStream);
		try {
			return doParse();
		} catch ( IOException | PatternSyntaxException e ) {
			throw new InvalidFilterException(e.getMessage());
		}
	}

	private List<FilterPattern> doParse() throws IOException {
		ArrayList<FilterPattern> patterns = new ArrayList<>();
		tokenizer.ordinaryChar('.');
		tokenizer.ordinaryChar('/');
		tokenizer.wordChars('_', '_');

		tokenizer.ordinaryChars('0', '9');
		tokenizer.wordChars('0', '9');

		tokenizer.quoteChar('`');
		tokenizer.quoteChar('\'');
		tokenizer.quoteChar('"');

		tokenizer.nextToken();

		FilterPattern p;
		while ( (p = parseFilterPattern()) != null )
			patterns.add(p);

		return patterns;
	}

	private void skipToken(char token) throws IOException {
		if ( tokenizer.ttype != token ) {
			throw new IOException("Expected '" + token + "', saw: " + tokenizer.toString());
		}
		tokenizer.nextToken();
	}


	private FilterPattern parseFilterPattern() throws IOException {
		FilterPatternType type;
		FilterPattern ret;

		if ( tokenizer.ttype == TT_EOF )
			return null;

		if ( tokenizer.ttype != TT_WORD )
			throw new IOException("expected [include, exclude, blacklist] in filter definition.");

		switch(tokenizer.sval.toLowerCase()) {
			case "include":
				type = FilterPatternType.INCLUDE;
				break;
			case "exclude":
				type = FilterPatternType.EXCLUDE;
				break;
			case "blacklist":
				type = FilterPatternType.BLACKLIST;
				break;
			default:
				throw new IOException("Unknown filter keyword: " + tokenizer.sval);
		}
		tokenizer.nextToken();


		skipToken(':');
		Pattern dbPattern = parsePattern();
		skipToken('.');
		Pattern tablePattern = parsePattern();

		if ( tokenizer.ttype == '.' ) {
			// column-value filter
			tokenizer.nextToken();

			if ( tokenizer.ttype != TT_WORD )
				throw new IOException("expected column name, got" + tokenizer.nextToken());

			String columnName = tokenizer.sval;
			tokenizer.nextToken();

			skipToken('=');
			Pattern valuePattern = parsePattern();
			ret = new FilterColumnPattern(type, dbPattern, tablePattern, columnName, valuePattern);
		} else {
			ret = new FilterPattern(type, dbPattern, tablePattern);
		}

		if ( tokenizer.ttype == ',' ) {
			tokenizer.nextToken();
		}

		return ret;
	}

	private Pattern parsePattern() throws IOException {
		Pattern pattern;
		switch ( tokenizer.ttype ) {
			case '/':
				String regexp = parseRegexp();
				try {
					pattern = Pattern.compile(regexp);
				} catch ( PatternSyntaxException e ) {
					throw new IOException("Invalid regexp /" + regexp + "/: " + e.getDescription());
				}
				break;
			case '*':
				pattern = Pattern.compile("");
				break;
			case TT_WORD:
			case '`':
			case '\'':
			case '"':
				pattern = literalPattern(tokenizer.sval);
				break;
			default:
				throw new IOException("Expected string or regexp, saw '" + Character.toString((char) tokenizer.ttype));
		}
		tokenizer.nextToken();
		return pattern;
	}

	private String parseRegexp() throws IOException {
		char ch, lastChar = 0;
		String s = "";
		while ( true ) {
			int c = inputStream.read();
			if ( c == -1 )
				throw new IOException("Unterminated regexp: /" + s);
			ch = (char) c;
			if ( ch == '/' && lastChar != '\\' )
				break;

			s += ch;
			lastChar = ch;
		}
		return s;
	}

	static Pattern literalPattern(String name) {
		return Pattern.compile(LITERAL_START + escapeLiteral(name) + LITERAL_END);
	}

	static String escapeLiteral(String name) {
		StringBuilder sb = new StringBuilder(name.length());
		for ( char c : name.toCharArray() ) {
			if ( REGEX_METACHARS.indexOf(c) >= 0 )
				sb.append('\\');
			sb.append(c);
		}
		return sb.toString();
	}

	/**
	 * Returns the identifier an exact-match pattern built by {@link #literalPattern} matches, or
	 * null if the pattern is a regular expression.
	 */
	static String literalValue(Pattern p) {
		String s = p.pattern();
		int minLength = LITERAL_START.length() + LITERAL_END.length();
		if ( s.length() < minLength || !s.startsWith(LITERAL_START) || !s.endsWith(LITERAL_END) )
			return null;

		String body = s.substring(LITERAL_START.length(), s.length() - LITERAL_END.length());
		StringBuilder name = new StringBuilder(body.length());
		for ( int i = 0; i < body.length(); i++ ) {
			char c = body.charAt(i);
			if ( c == '\\' ) {
				if ( ++i == body.length() )
					return null;
				c = body.charAt(i);
			}
			name.append(c);
		}

		String literal = name.toString();
		return escapeLiteral(literal).equals(body) ? literal : null;
	}

	static String quoteIdentifier(String name) {
		if ( BARE_IDENTIFIER.matcher(name).matches() )
			return name;
		return "`" + name.replace("\\", "\\\\")
			.replace("`", "\\`")
			.replace("\n", "\\n")
			.replace("\r", "\\r") + "`";
	}
}
