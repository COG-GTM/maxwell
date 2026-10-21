package com.zendesk.maxwell.bootstrap;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class BootstrapWhereClauseTest {
	private static final List<String> COLUMNS = Arrays.asList("id", "account_id", "text_field", "my_date", "key");

	private BootstrapWhereClause parse(String clause) throws Exception {
		return BootstrapWhereClause.parse(clause, COLUMNS);
	}

	private void assertRejected(String clause, String message) {
		try {
			BootstrapWhereClause w = parse(clause);
			fail("expected '" + clause + "' to be rejected, got: " + w.getSQL());
		} catch ( BootstrapWhereClause.InvalidWhereClauseException e ) {
			assertThat(e.getMessage(), containsString(message));
		} catch ( Exception e ) {
			fail("unexpected exception " + e);
		}
	}

	@Test
	public void testBlankClauseIsNull() throws Exception {
		assertThat(parse(null), is(nullValue()));
		assertThat(parse(""), is(nullValue()));
		assertThat(parse("   "), is(nullValue()));
	}

	@Test
	public void testSimpleComparison() throws Exception {
		BootstrapWhereClause w = parse("id > 1");
		assertThat(w.getSQL(), is("(`id` > ?)"));
		assertThat(w.getParams(), is(Arrays.<Object>asList(1L)));
	}

	@Test
	public void testDocumentedDateExample() throws Exception {
		BootstrapWhereClause w = parse("my_date >= '2017-01-07 00:00:00'");
		assertThat(w.getSQL(), is("(`my_date` >= ?)"));
		assertThat(w.getParams(), is(Arrays.<Object>asList("2017-01-07 00:00:00")));
	}

	@Test
	public void testBooleanOperatorsAndPrecedence() throws Exception {
		BootstrapWhereClause w = parse("id >= 10 and (account_id = 1 OR account_id <> -2) and not text_field is null");
		assertThat(w.getSQL(), is("(((`id` >= ?) AND ((`account_id` = ?) OR (`account_id` <> ?))) AND (NOT (`text_field` IS NULL)))"));
		assertThat(w.getParams(), is(Arrays.<Object>asList(10L, 1L, -2L)));
	}

	@Test
	public void testInBetweenLike() throws Exception {
		BootstrapWhereClause w = parse("id not in (1, 2.5, 'x') AND account_id BETWEEN 1 AND 10 AND text_field LIKE 'a\\_%' ESCAPE '\\\\'");
		assertThat(w.getSQL(), is("(((`id` NOT IN (?, ?, ?)) AND (`account_id` BETWEEN ? AND ?)) AND (`text_field` LIKE ? ESCAPE ?))"));
		assertThat(w.getParams(), is(Arrays.<Object>asList(1L, new BigDecimal("2.5"), "x", 1L, 10L, "a\\_%", "\\")));
	}

	@Test
	public void testColumnsAreCanonicalizedAndQuoted() throws Exception {
		assertThat(parse("`KEY` = 'a' and ID=1").getSQL(), is("((`key` = ?) AND (`id` = ?))"));
	}

	@Test
	public void testStringEscapesAreDecoded() throws Exception {
		assertThat(parse("text_field = 'it''s' or text_field = \"a\\\"b\\n\"").getParams(), is(Arrays.<Object>asList("it's", "a\"b\n")));
	}

	@Test
	public void testNoBackslashEscapesTreatsBackslashLiterally() throws Exception {
		BootstrapWhereClause w = BootstrapWhereClause.parse("text_field = 'a\\b' or text_field = 'c\\'", COLUMNS, true);
		assertThat(w.getSQL(), is("((`text_field` = ?) OR (`text_field` = ?))"));
		assertThat(w.getParams(), is(Arrays.<Object>asList("a\\b", "c\\")));
	}

	@Test
	public void testInjectedQuotesStayInsideTheLiteral() throws Exception {
		BootstrapWhereClause w = parse("text_field = 'x\\' UNION SELECT 1 -- '");
		assertThat(w.getSQL(), is("(`text_field` = ?)"));
		assertThat(w.getParams(), is(Arrays.<Object>asList("x' UNION SELECT 1 -- ")));
	}

	@Test
	public void testRejectsUnion() {
		assertRejected("1=0 UNION SELECT user, authentication_string, 1, 1, 1 FROM user", "unexpected 'UNION'");
		assertRejected("1=0 UNION SELECT user, authentication_string, 1, 1, 1 FROM mysql.user", "unexpected character '.'");
	}

	@Test
	public void testRejectsSubqueries() {
		assertRejected("(SELECT SUBSTR(authentication_string,1,1) FROM mysql.user WHERE user='root')='*'", "unexpected character '.'");
		assertRejected("(SELECT SUBSTR(authentication_string,1,1) FROM user WHERE user='root')='*'", "unknown column 'SELECT'");
		assertRejected("id IN (SELECT id FROM other)", "unexpected 'SELECT'");
		assertRejected("EXISTS (select 1)", "function calls are not allowed");
	}

	@Test
	public void testRejectsFunctionCalls() {
		assertRejected("text_field = LOAD_FILE('/etc/passwd')", "function calls are not allowed");
		assertRejected("SLEEP(10) = 0", "function calls are not allowed");
		assertRejected("id = 1 and benchmark (1000000, md5('a'))", "function calls are not allowed");
	}

	@Test
	public void testRejectsUnknownColumns() {
		assertRejected("password = 'x'", "unknown column 'password'");
		assertRejected("mysql.user = 1", "unexpected character '.'");
		assertRejected("`id` = `other`", "unknown column 'other'");
	}

	@Test
	public void testRejectsCommentsSeparatorsAndVariables() {
		assertRejected("id = 1 -- comment", "comments are not allowed");
		assertRejected("id = 1 # comment", "comments are not allowed");
		assertRejected("id = /* x */ 1", "comments are not allowed");
		assertRejected("id = 1; drop table foo", "unexpected character ';'");
		assertRejected("id = @@version", "unexpected character '@'");
	}

	@Test
	public void testRejectsOtherOperators() {
		assertRejected("id = 1 || 1", "unexpected character '|'");
		assertRejected("id = 1 && 1", "unexpected character '&'");
		assertRejected("id + 1 = 2", "unexpected '+'");
		assertRejected("id REGEXP 'a'", "unexpected 'REGEXP'");
		assertRejected("id = 0x41", "malformed number");
		assertRejected("text_field = _binary'a'", "unknown column '_binary'");
	}

	@Test
	public void testRejectsMalformedClauses() {
		assertRejected("id = 'unterminated", "unterminated string");
		assertRejected("id =", "unexpected end of clause");
		assertRejected("(id = 1", "unexpected end of clause");
		assertRejected("id = 1)", "unexpected ')'");
		assertRejected("id", "unexpected end of clause");
	}

	@Test
	public void testRejectsDeepNesting() {
		StringBuilder sb = new StringBuilder();
		for ( int i = 0; i < 100; i++ ) sb.append('(');
		sb.append("id = 1");
		for ( int i = 0; i < 100; i++ ) sb.append(')');
		assertRejected(sb.toString(), "nested too deeply");
	}

	@Test
	public void testRejectsOverlongClause() {
		StringBuilder sb = new StringBuilder("id in (1");
		while ( sb.length() < BootstrapWhereClause.MAX_LENGTH ) sb.append(", 1");
		sb.append(')');
		assertRejected(sb.toString(), "longer than");
	}
}
