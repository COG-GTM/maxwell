package com.zendesk.maxwell.bootstrap;

import com.zendesk.maxwell.util.Sql;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

/**
 * A bootstrap `where_clause`, parsed against a restricted grammar and re-rendered
 * as parameterized SQL.
 *
 * Rows in `maxwell.bootstrap` can be written by anyone with INSERT on that table,
 * and the bootstrap query runs with the replication account, so the clause is
 * never pasted into SQL verbatim.  Only the following is accepted:
 *
 * <pre>
 *   expr      := and_expr ( OR and_expr )*
 *   and_expr  := not_expr ( AND not_expr )*
 *   not_expr  := NOT not_expr | '(' expr ')' | predicate
 *   predicate := operand cmp_op operand
 *              | operand IS [NOT] ( NULL | TRUE | FALSE | UNKNOWN )
 *              | operand [NOT] IN '(' literal ( ',' literal )* ')'
 *              | operand [NOT] BETWEEN operand AND operand
 *              | operand [NOT] LIKE operand [ ESCAPE string ]
 *   operand   := column | literal
 *   literal   := 'string' | "string" | [+-] number | NULL | TRUE | FALSE
 *   cmp_op    := = | &lt;=&gt; | &lt;&gt; | != | &lt; | &lt;= | &gt; | &gt;=
 * </pre>
 *
 * Columns must belong to the table being bootstrapped.  Function calls,
 * subqueries, UNION, variables, comments and statement separators are rejected.
 * Literals are bound as statement parameters, column names are re-quoted.
 */
public class BootstrapWhereClause {
	public static class InvalidWhereClauseException extends Exception {
		InvalidWhereClauseException(String message) {
			super(message);
		}
	}

	static final int MAX_LENGTH = 16384;
	static final int MAX_DEPTH = 64;

	private final String sql;
	private final List<Object> params;

	private BootstrapWhereClause(String sql, List<Object> params) {
		this.sql = sql;
		this.params = Collections.unmodifiableList(params);
	}

	/**
	 * @return the clause as SQL with `?` placeholders, suitable for appending after "where "
	 */
	public String getSQL() {
		return sql;
	}

	public List<Object> getParams() {
		return params;
	}

	/**
	 * Bind this clause's literals to a statement built from {@link #getSQL()}.
	 *
	 * @param statement the statement
	 * @param offset    the 1-based index of the first placeholder belonging to this clause
	 * @return the index of the next placeholder after this clause
	 * @throws SQLException if binding fails
	 */
	public int bind(PreparedStatement statement, int offset) throws SQLException {
		for ( Object p : params ) {
			statement.setObject(offset++, p);
		}
		return offset;
	}

	/**
	 * Parse and validate a user supplied where clause.
	 *
	 * @param clause  the raw clause, may be null or blank
	 * @param columns column names of the table being bootstrapped
	 * @return the parsed clause, or null if there is no clause
	 * @throws InvalidWhereClauseException if the clause is not in the allowed grammar
	 */
	public static BootstrapWhereClause parse(String clause, Collection<String> columns) throws InvalidWhereClauseException {
		return parse(clause, columns, false);
	}

	/**
	 * Parse a where clause using the quoting rules of the given connection's sql_mode
	 * (NO_BACKSLASH_ESCAPES and ANSI_QUOTES).
	 *
	 * @param clause     the raw clause, may be null or blank
	 * @param columns    column names of the table being bootstrapped
	 * @param connection the connection the clause will run on
	 * @return the parsed clause, or null if there is no clause
	 * @throws InvalidWhereClauseException if the clause is not in the allowed grammar
	 * @throws SQLException if sql_mode can't be read
	 */
	public static BootstrapWhereClause parse(String clause, Collection<String> columns, Connection connection) throws InvalidWhereClauseException, SQLException {
		if ( clause == null || clause.trim().isEmpty() )
			return null;
		Set<String> sqlMode = getSqlMode(connection);
		return parse(clause, columns, sqlMode.contains("NO_BACKSLASH_ESCAPES"), sqlMode.contains("ANSI_QUOTES"));
	}

	static Set<String> getSqlMode(Connection connection) throws SQLException {
		try ( Statement s = connection.createStatement();
			  ResultSet rs = s.executeQuery("select @@session.sql_mode") ) {
			String mode = rs.next() ? rs.getString(1) : null;
			Set<String> modes = new HashSet<>();
			if ( mode != null ) {
				for ( String m : mode.split(",") )
					modes.add(m.trim().toUpperCase(Locale.ROOT));
			}
			return modes;
		}
	}

	/**
	 * @param clause             the raw clause, may be null or blank
	 * @param columns            column names of the table being bootstrapped
	 * @param noBackslashEscapes treat backslashes in string literals literally, as mysql does under NO_BACKSLASH_ESCAPES
	 * @return the parsed clause, or null if there is no clause
	 * @throws InvalidWhereClauseException if the clause is not in the allowed grammar
	 */
	static BootstrapWhereClause parse(String clause, Collection<String> columns, boolean noBackslashEscapes) throws InvalidWhereClauseException {
		return parse(clause, columns, noBackslashEscapes, false);
	}

	/**
	 * @param clause             the raw clause, may be null or blank
	 * @param columns            column names of the table being bootstrapped
	 * @param noBackslashEscapes treat backslashes in string literals literally, as mysql does under NO_BACKSLASH_ESCAPES
	 * @param ansiQuotes         treat double quotes as identifier quotes, as mysql does under ANSI_QUOTES
	 * @return the parsed clause, or null if there is no clause
	 * @throws InvalidWhereClauseException if the clause is not in the allowed grammar
	 */
	static BootstrapWhereClause parse(String clause, Collection<String> columns, boolean noBackslashEscapes, boolean ansiQuotes) throws InvalidWhereClauseException {
		if ( clause == null || clause.trim().isEmpty() )
			return null;

		if ( clause.length() > MAX_LENGTH )
			throw new InvalidWhereClauseException("where clause is longer than " + MAX_LENGTH + " characters");

		Map<String, String> columnsByLowerName = new HashMap<>();
		for ( String c : columns )
			columnsByLowerName.put(c.toLowerCase(Locale.ROOT), c);

		Parser parser = new Parser(new Lexer(clause, noBackslashEscapes, ansiQuotes).tokenize(), columnsByLowerName);
		String sql = parser.parseClause();
		return new BootstrapWhereClause(sql, parser.params);
	}

	enum TokenType { IDENT, QUOTED_IDENT, STRING, NUMBER, SYMBOL, EOF }

	static class Token {
		final TokenType type;
		final String text;
		final int pos;

		Token(TokenType type, String text, int pos) {
			this.type = type;
			this.text = text;
			this.pos = pos;
		}

		boolean isKeyword(String keyword) {
			return type == TokenType.IDENT && text.equalsIgnoreCase(keyword);
		}

		boolean isSymbol(String symbol) {
			return type == TokenType.SYMBOL && text.equals(symbol);
		}

		String describe() {
			return type == TokenType.EOF ? "end of clause" : "'" + text + "' at position " + pos;
		}
	}

	static class Lexer {
		private static final List<String> SYMBOLS = Arrays.asList(
			"<=>", "<>", "!=", "<=", ">=", "=", "<", ">", "(", ")", ",", "+", "-"
		);

		private final String input;
		private final boolean noBackslashEscapes;
		private final boolean ansiQuotes;
		private int pos = 0;

		Lexer(String input, boolean noBackslashEscapes, boolean ansiQuotes) {
			this.input = input;
			this.noBackslashEscapes = noBackslashEscapes;
			this.ansiQuotes = ansiQuotes;
		}

		List<Token> tokenize() throws InvalidWhereClauseException {
			List<Token> tokens = new ArrayList<>();
			while ( true ) {
				skipWhitespace();
				if ( pos >= input.length() ) {
					tokens.add(new Token(TokenType.EOF, "", pos));
					return tokens;
				}
				tokens.add(next());
			}
		}

		private void skipWhitespace() {
			while ( pos < input.length() && Character.isWhitespace(input.charAt(pos)) )
				pos++;
		}

		private Token next() throws InvalidWhereClauseException {
			int start = pos;
			char c = input.charAt(pos);

			if ( input.startsWith("--", pos) || input.startsWith("/*", pos) || c == '#' )
				throw new InvalidWhereClauseException("comments are not allowed in a bootstrap where clause (position " + start + ")");

			if ( c == '`' || (c == '"' && ansiQuotes) )
				return new Token(TokenType.QUOTED_IDENT, readQuotedIdentifier(c), start);

			if ( c == '\'' || c == '"' )
				return new Token(TokenType.STRING, readString(c), start);

			if ( isDigit(c) || (c == '.' && pos + 1 < input.length() && isDigit(input.charAt(pos + 1))) )
				return new Token(TokenType.NUMBER, readNumber(), start);

			if ( isIdentifierStart(c) ) {
				while ( pos < input.length() && isIdentifierPart(input.charAt(pos)) )
					pos++;
				return new Token(TokenType.IDENT, input.substring(start, pos), start);
			}

			for ( String s : SYMBOLS ) {
				if ( input.startsWith(s, pos) ) {
					pos += s.length();
					return new Token(TokenType.SYMBOL, s, start);
				}
			}

			throw new InvalidWhereClauseException("unexpected character '" + c + "' at position " + start + " in bootstrap where clause");
		}

		private String readString(char quote) throws InvalidWhereClauseException {
			int start = pos;
			StringBuilder sb = new StringBuilder();
			pos++;
			while ( pos < input.length() ) {
				char c = input.charAt(pos);
				if ( c == quote ) {
					if ( pos + 1 < input.length() && input.charAt(pos + 1) == quote ) {
						sb.append(quote);
						pos += 2;
						continue;
					}
					pos++;
					return sb.toString();
				}
				if ( c == '\\' && !noBackslashEscapes && pos + 1 < input.length() ) {
					sb.append(unescape(input.charAt(pos + 1)));
					pos += 2;
					continue;
				}
				sb.append(c);
				pos++;
			}
			throw new InvalidWhereClauseException("unterminated string starting at position " + start + " in bootstrap where clause");
		}

		/* mysql's default (non NO_BACKSLASH_ESCAPES) string escapes. */
		private static String unescape(char c) {
			switch ( c ) {
				case '0': return "\0";
				case 'b': return "\b";
				case 'n': return "\n";
				case 'r': return "\r";
				case 't': return "\t";
				case 'Z': return "\u001A";
				case '%': return "\\%";
				case '_': return "\\_";
				default:  return String.valueOf(c);
			}
		}

		private String readQuotedIdentifier(char quote) throws InvalidWhereClauseException {
			int start = pos;
			StringBuilder sb = new StringBuilder();
			pos++;
			while ( pos < input.length() ) {
				char c = input.charAt(pos);
				if ( c == quote ) {
					if ( pos + 1 < input.length() && input.charAt(pos + 1) == quote ) {
						sb.append(quote);
						pos += 2;
						continue;
					}
					pos++;
					return sb.toString();
				}
				sb.append(c);
				pos++;
			}
			throw new InvalidWhereClauseException("unterminated identifier starting at position " + start + " in bootstrap where clause");
		}

		private String readNumber() throws InvalidWhereClauseException {
			int start = pos;
			while ( pos < input.length() && isDigit(input.charAt(pos)) )
				pos++;
			if ( pos < input.length() && input.charAt(pos) == '.' ) {
				pos++;
				while ( pos < input.length() && isDigit(input.charAt(pos)) )
					pos++;
			}
			if ( pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E') ) {
				int expStart = pos;
				pos++;
				if ( pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-') )
					pos++;
				if ( pos >= input.length() || !isDigit(input.charAt(pos)) )
					throw new InvalidWhereClauseException("malformed number at position " + expStart + " in bootstrap where clause");
				while ( pos < input.length() && isDigit(input.charAt(pos)) )
					pos++;
			}
			if ( pos < input.length() && isIdentifierPart(input.charAt(pos)) )
				throw new InvalidWhereClauseException("malformed number at position " + start + " in bootstrap where clause");
			return input.substring(start, pos);
		}

		private static boolean isDigit(char c) {
			return c >= '0' && c <= '9';
		}

		private static boolean isIdentifierStart(char c) {
			return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$' || c > 0x7F;
		}

		private static boolean isIdentifierPart(char c) {
			return isIdentifierStart(c) || isDigit(c);
		}
	}

	static class Parser {
		private static final Set<String> COMPARISON_OPERATORS = new HashSet<>(Arrays.asList(
			"=", "<=>", "<>", "!=", "<", "<=", ">", ">="
		));
		private static final Set<String> RESERVED = new HashSet<>(Arrays.asList(
			"and", "or", "not", "is", "in", "between", "like", "escape", "null", "true", "false", "unknown"
		));

		private final List<Token> tokens;
		private final Map<String, String> columns;
		final List<Object> params = new ArrayList<>();
		private int index = 0;
		private int depth = 0;

		Parser(List<Token> tokens, Map<String, String> columns) {
			this.tokens = tokens;
			this.columns = columns;
		}

		String parseClause() throws InvalidWhereClauseException {
			String sql = parseOr();
			if ( peek().type != TokenType.EOF )
				throw unexpected(peek());
			return sql;
		}

		private String parseOr() throws InvalidWhereClauseException {
			enter();
			String sql = parseAnd();
			while ( peek().isKeyword("OR") ) {
				advance();
				sql = "(" + sql + " OR " + parseAnd() + ")";
			}
			depth--;
			return sql;
		}

		private String parseAnd() throws InvalidWhereClauseException {
			String sql = parseNot();
			while ( peek().isKeyword("AND") ) {
				advance();
				sql = "(" + sql + " AND " + parseNot() + ")";
			}
			return sql;
		}

		private String parseNot() throws InvalidWhereClauseException {
			if ( peek().isKeyword("NOT") ) {
				advance();
				enter();
				String sql = "(NOT " + parseNot() + ")";
				depth--;
				return sql;
			}

			if ( peek().isSymbol("(") ) {
				advance();
				String sql = parseOr();
				expectSymbol(")");
				return sql;
			}

			return parsePredicate();
		}

		private String parsePredicate() throws InvalidWhereClauseException {
			String left = parseOperand();
			Token t = peek();

			if ( t.type == TokenType.SYMBOL && COMPARISON_OPERATORS.contains(t.text) ) {
				advance();
				return "(" + left + " " + t.text + " " + parseOperand() + ")";
			}

			if ( t.isKeyword("IS") ) {
				advance();
				String not = "";
				if ( peek().isKeyword("NOT") ) {
					advance();
					not = "NOT ";
				}
				Token v = advance();
				for ( String kw : Arrays.asList("NULL", "TRUE", "FALSE", "UNKNOWN") ) {
					if ( v.isKeyword(kw) )
						return "(" + left + " IS " + not + kw + ")";
				}
				throw unexpected(v);
			}

			String not = "";
			if ( t.isKeyword("NOT") ) {
				advance();
				not = "NOT ";
				t = peek();
			}

			if ( t.isKeyword("IN") ) {
				advance();
				expectSymbol("(");
				List<String> values = new ArrayList<>();
				values.add(parseLiteral());
				while ( peek().isSymbol(",") ) {
					advance();
					values.add(parseLiteral());
				}
				expectSymbol(")");
				return "(" + left + " " + not + "IN (" + String.join(", ", values) + "))";
			}

			if ( t.isKeyword("BETWEEN") ) {
				advance();
				String low = parseOperand();
				Token and = advance();
				if ( !and.isKeyword("AND") )
					throw unexpected(and);
				String high = parseOperand();
				return "(" + left + " " + not + "BETWEEN " + low + " AND " + high + ")";
			}

			if ( t.isKeyword("LIKE") ) {
				advance();
				String sql = left + " " + not + "LIKE " + parseOperand();
				if ( peek().isKeyword("ESCAPE") ) {
					advance();
					Token esc = advance();
					if ( esc.type != TokenType.STRING )
						throw unexpected(esc);
					sql += " ESCAPE " + bindParam(esc.text);
				}
				return "(" + sql + ")";
			}

			throw unexpected(t);
		}

		private String parseOperand() throws InvalidWhereClauseException {
			Token t = peek();
			if ( t.type == TokenType.QUOTED_IDENT )
				return parseColumn(advance());

			if ( t.type == TokenType.IDENT && !RESERVED.contains(t.text.toLowerCase(Locale.ROOT)) ) {
				Token next = tokens.get(index + 1);
				if ( next.isSymbol("(") )
					throw new InvalidWhereClauseException("function calls are not allowed in a bootstrap where clause: " + t.describe());
				return parseColumn(advance());
			}

			return parseLiteral();
		}

		private String parseColumn(Token t) throws InvalidWhereClauseException {
			String column = columns.get(t.text.toLowerCase(Locale.ROOT));
			if ( column == null )
				throw new InvalidWhereClauseException("unknown column " + t.describe() + " in bootstrap where clause");
			return Sql.quoteIdentifier(column);
		}

		private String parseLiteral() throws InvalidWhereClauseException {
			Token t = advance();
			switch ( t.type ) {
				case STRING:
					return bindParam(t.text);
				case NUMBER:
					return bindParam(parseNumber(t.text, false));
				case SYMBOL:
					if ( t.isSymbol("-") || t.isSymbol("+") ) {
						Token n = advance();
						if ( n.type != TokenType.NUMBER )
							throw unexpected(n);
						return bindParam(parseNumber(n.text, t.isSymbol("-")));
					}
					break;
				case IDENT:
					if ( t.isKeyword("NULL") ) return "NULL";
					if ( t.isKeyword("TRUE") ) return "TRUE";
					if ( t.isKeyword("FALSE") ) return "FALSE";
					break;
				default:
					break;
			}
			throw unexpected(t);
		}

		private static Object parseNumber(String text, boolean negative) {
			String s = negative ? "-" + text : text;
			if ( s.indexOf('e') >= 0 || s.indexOf('E') >= 0 )
				return Double.valueOf(s);
			if ( s.indexOf('.') >= 0 )
				return new BigDecimal(s);
			BigInteger i = new BigInteger(s);
			if ( i.bitLength() < 64 )
				return i.longValue();
			return new BigDecimal(i);
		}

		private String bindParam(Object value) {
			params.add(value);
			return "?";
		}

		private void enter() throws InvalidWhereClauseException {
			if ( ++depth > MAX_DEPTH )
				throw new InvalidWhereClauseException("bootstrap where clause is nested too deeply");
		}

		private Token peek() {
			return tokens.get(index);
		}

		private Token advance() {
			Token t = tokens.get(index);
			if ( t.type != TokenType.EOF )
				index++;
			return t;
		}

		private void expectSymbol(String symbol) throws InvalidWhereClauseException {
			Token t = advance();
			if ( !t.isSymbol(symbol) )
				throw unexpected(t);
		}

		private static InvalidWhereClauseException unexpected(Token t) {
			return new InvalidWhereClauseException("unexpected " + t.describe() + " in bootstrap where clause");
		}
	}
}
