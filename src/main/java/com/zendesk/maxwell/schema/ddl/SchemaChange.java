package com.zendesk.maxwell.schema.ddl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.zendesk.maxwell.filtering.Filter;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zendesk.maxwell.schema.Schema;

public abstract class SchemaChange {
    final static Logger LOGGER = LoggerFactory.getLogger(SchemaChange.class);
	public abstract ResolvedSchemaChange resolve(Schema schema) throws InvalidSchemaError;

	private static final Set<Pattern> SQL_BLACKLIST = new HashSet<Pattern>();

	// Every pattern applied to raw DDL must run in linear time: anchored, possessive, no back-references.
	private static final String SET_STATEMENT_ASSIGNMENT = "\\w++\\s*+=\\s*+(?:'[^']*+'|\"[^\"]*+\"|\\w++)";
	private static final Pattern SET_STATEMENT = Pattern.compile(
		"\\A\\s*+SET\\s++STATEMENT\\s++" + SET_STATEMENT_ASSIGNMENT
			+ "(?:\\s*+,\\s*+" + SET_STATEMENT_ASSIGNMENT + ")*+\\s*+FOR\\s++",
		Pattern.CASE_INSENSITIVE
	);

	static {
		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*BEGIN", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*COMMIT", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*FLUSH", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*GRANT", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*REVOKE\\s+", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*SAVEPOINT", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));

		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*CREATE\\s+(AGGREGATE\\s+)?FUNCTION", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*(ALTER|CREATE)\\s+(DEFINER=[^\\s]+\\s+)?(EVENT|FUNCTION|TRIGGER|PROCEDURE)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*DROP\\s+(EVENT|FUNCTION|TRIGGER|PROCEDURE|VIEW)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));

		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*(ALTER|CREATE|DROP)\\s+((ONLINE|OFFLINE|UNIQUE|FULLTEXT|SPATIAL)\\s+)*(INDEX)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*ANALYZE\\s+TABLE", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*SET\\s+PASSWORD", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*(ALTER|CREATE|DROP|RENAME)\\s+USER", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*ALTER\\s+INSTANCE.*", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));

		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*(ALTER|CREATE|DROP)\\s+TEMPORARY\\s+TABLE", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*(ALTER|CREATE|DROP)\\s+TABLESPACE", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*(SET|DROP|CREATE)\\s+(DEFAULT\\s+)?ROLE", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*TRUNCATE\\s+", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*OPTIMIZE\\s+", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));

		SQL_BLACKLIST.add(Pattern.compile("\\A\\s*REPAIR\\s+", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
	}

	private static final Pattern DELETE_BLACKLIST = Pattern.compile("^[^\\S\\r\\n]*DELETE\\s*FROM", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

	private static final Pattern DASH_COMMENTS = Pattern.compile("--.*");
	private static final Pattern HASH_COMMENTS = Pattern.compile("^[^\\S\\r\\n]*#.*", Pattern.MULTILINE);

	private static boolean isLineTerminator(char c) {
		return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
	}

	private static boolean isRegexWhitespace(char c) {
		return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
	}

	// Replaces executable comments with their body, e.g. "/*!50032 CREATE EVENT */" -> "CREATE EVENT".
	// Same result as replaceAll("/\\*M?!\\d+\\s*(.*)\\*/", "$1") -- the body runs to the last
	// closer on its line -- but in linear time.
	static String unwrapExecutableComments(String sql) {
		int n = sql.length();
		StringBuilder out = null;
		int copied = 0;
		int lineEnd = -1;
		int lastCloser = -1;

		int i = sql.indexOf("/*", 0);
		while ( i >= 0 ) {
			int p = i + 2;
			if ( p < n && sql.charAt(p) == 'M' )
				p++;

			int digits = p + 1;
			if ( p >= n || sql.charAt(p) != '!' ) {
				i = sql.indexOf("/*", i + 1);
				continue;
			}

			p = digits;
			while ( p < n && sql.charAt(p) >= '0' && sql.charAt(p) <= '9' )
				p++;

			if ( p == digits ) {
				i = sql.indexOf("/*", i + 1);
				continue;
			}

			while ( p < n && isRegexWhitespace(sql.charAt(p)) )
				p++;

			int bodyStart = p;
			if ( bodyStart > lineEnd ) {
				lineEnd = bodyStart;
				lastCloser = -1;
				while ( lineEnd < n && !isLineTerminator(sql.charAt(lineEnd)) ) {
					if ( sql.charAt(lineEnd) == '*' && lineEnd + 1 < n && sql.charAt(lineEnd + 1) == '/' )
						lastCloser = lineEnd;
					lineEnd++;
				}
			}

			if ( lastCloser < bodyStart ) {
				i = sql.indexOf("/*", i + 1);
				continue;
			}

			if ( out == null )
				out = new StringBuilder(n);
			out.append(sql, copied, i).append(sql, bodyStart, lastCloser);
			copied = lastCloser + 2;
			i = sql.indexOf("/*", copied);
		}

		if ( out == null )
			return sql;
		return out.append(sql, copied, n).toString();
	}

	// Removes "/* ... */" comments, which may span lines. Same result as
	// Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(sql).replaceAll(""), in linear time.
	static String stripCStyleComments(String sql) {
		StringBuilder out = null;
		int copied = 0;
		int i;
		while ( (i = sql.indexOf("/*", copied)) >= 0 ) {
			int close = sql.indexOf("*/", i + 2);
			if ( close < 0 )
				break;

			if ( out == null )
				out = new StringBuilder(sql.length());
			out.append(sql, copied, i);
			copied = close + 2;
		}

		if ( out == null )
			return sql;
		return out.append(sql, copied, sql.length()).toString();
	}

	static String stripForBlacklist(String sql) {
		// first *include* /*50032 CREATE EVENT */ style sql
		sql = unwrapExecutableComments(sql);

		// now strip out comments
		sql = stripCStyleComments(sql);
		sql = DASH_COMMENTS.matcher(sql).replaceAll("");
		sql = HASH_COMMENTS.matcher(sql).replaceAll("");

		// SET STATEMENT .. FOR syntax can be applied to BLACKLIST element, just omit for tesing purposes
		return SET_STATEMENT.matcher(sql).replaceAll("");
	}

	private static boolean matchesBlacklist(String sql) {
		sql = stripForBlacklist(sql);

		for (Pattern p : SQL_BLACKLIST) {
			if (p.matcher(sql).find()) {
				LOGGER.debug("ignoring sql: {}", sql);
				return true;
			}
		}

		if ( DELETE_BLACKLIST.matcher(sql).find() ) {
			LOGGER.info("Ignoring DELETE statement: " + sql);
			LOGGER.info("You may ignore this warning if this is a MEMORY table.");
			LOGGER.info("Otherwise you should make sure your binlog_format setting is correct, and that your clients have all reconnected.");
			return true;
		}

		return false;
	}

	private static List<SchemaChange> parseSQL(String currentDB, String sql) {
		ANTLRInputStream input = new ANTLRInputStream(sql);
		mysqlLexer lexer = new mysqlLexer(input);
		lexer.removeErrorListeners();

		TokenStream tokens = new CommonTokenStream(lexer);

		LOGGER.debug("SQL_PARSE <- \"{}\"", sql);
		mysqlParser parser = new mysqlParser(tokens);
		parser.removeErrorListeners();

		MysqlParserListener listener = new MysqlParserListener(currentDB, tokens);

		ParseTree tree = parser.parse();

		ParseTreeWalker.DEFAULT.walk(listener, tree);
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("SQL_PARSE ->   {}", tree.toStringTree(parser));
		}
		return listener.getSchemaChanges();
	}

	public static List<SchemaChange> parse(String currentDB, String sql) {
		if ( matchesBlacklist(sql) ) {
			return null;
		}

		while ( true ) {
			try {
				return parseSQL(currentDB, sql);
			} catch ( ReparseSQLException e ) {
				sql = e.getSQL();
				LOGGER.debug("rewrote SQL to {}", sql);
				// re-enter loop
			} catch ( ParseCancellationException e ) {
				if (LOGGER.isDebugEnabled()) {
					// we are debug logging the toString message, slf4j will log the stacktrace of a throwable
					String msg = e.toString();
					LOGGER.debug("Parse cancelled: {}", msg);
				}
				return null;
			} catch ( MaxwellSQLSyntaxError e) {
				LOGGER.error("Error parsing SQL: '{}'", sql);
				throw (e);
			}
		}
	}

	public abstract boolean isBlacklisted(Filter filter);
}
