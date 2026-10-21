package com.zendesk.maxwell.filtering;

import java.io.IOException;
import java.io.Reader;
import java.io.StreamTokenizer;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static java.io.StreamTokenizer.*;

public class FilterParser {
	public static final int MAX_FILTER_LENGTH = 64 * 1024;

	private StreamTokenizer tokenizer;
	private Reader inputStream;
	private final String input;

	public FilterParser(String input) {
		this.input = input;
	}

	public List<FilterPattern> parse() throws InvalidFilterException {
		if ( input != null && input.length() > MAX_FILTER_LENGTH )
			throw new InvalidFilterException("filter exceeds maximum length of " + MAX_FILTER_LENGTH + " characters");

		this.inputStream = new StringReader(input);

		this.tokenizer = new StreamTokenizer(inputStream);
		try {
			return doParse();
		} catch ( IOException e ) {
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
				try {
					pattern = Pattern.compile(parseRegexp());
				} catch ( PatternSyntaxException e ) {
					throw new IOException("invalid regexp: " + e.getDescription());
				}
				break;
			case '*':
				pattern = Pattern.compile("");
				break;
			case TT_WORD:
			case '`':
			case '\'':
			case '"':
				pattern = Pattern.compile("^" + tokenizer.sval + "$");
				break;
			default:
				throw new IOException("Expected string or regexp, saw '" + Character.toString((char) tokenizer.ttype));
		}
		tokenizer.nextToken();
		return pattern;
	}

	private String parseRegexp() throws IOException {
		int ch, lastChar = 0;
		StringBuilder s = new StringBuilder();
		while ( true ) {
			ch = inputStream.read();
			if ( ch == -1 )
				throw new IOException("unterminated regexp");

			if ( ch == '/' && lastChar != '\\' )
				break;

			s.append((char) ch);
			lastChar = ch;
		}
		return s.toString();
	}
}
