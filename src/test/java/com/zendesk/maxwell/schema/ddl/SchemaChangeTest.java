package com.zendesk.maxwell.schema.ddl;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.regex.Pattern;

import org.junit.Test;

public class SchemaChangeTest {
	private static String repeat(String s, int n) {
		StringBuilder sb = new StringBuilder();
		for ( int i = 0; i < n; i++ )
			sb.append(s);
		return sb.toString();
	}

	@Test
	public void testStripSetStatement() {
		assertThat(SchemaChange.stripForBlacklist("SET STATEMENT max_statement_time = 60 FOR flush table"), is("flush table"));
		assertThat(SchemaChange.stripForBlacklist("  set statement a = 'x y' ,b=\"q\"\tfor   DROP EVENT e"), is("DROP EVENT e"));
		assertThat(SchemaChange.stripForBlacklist("/* hi */ SET STATEMENT a=1 FOR GRANT ALL"), is("GRANT ALL"));
	}

	@Test
	public void testSetStatementOnlyStrippedAtStart() {
		String sql = "CREATE TABLE t (id int) COMMENT 'SET STATEMENT a=1 FOR x'";
		assertThat(SchemaChange.stripForBlacklist(sql), is(sql));
	}

	@Test(timeout = 5000)
	public void testSetStatementBacktracking() {
		String segments = repeat("=bbbbb", 50000);
		SchemaChange.stripForBlacklist("CREATE TABLE t (id int) COMMENT 'SET STATEMENT a" + segments + "'");
		SchemaChange.stripForBlacklist("SET STATEMENT a" + segments);
		SchemaChange.stripForBlacklist("SET STATEMENT " + repeat("a=1 ", 50000));
	}

	@Test(timeout = 5000)
	public void testParseSurvivesSetStatementComment() {
		String sql = "CREATE TABLE t (id int) COMMENT 'SET STATEMENT a" + repeat("=bbbbb", 40) + "'";
		assertThat(SchemaChange.parse("default_db", sql).size(), is(1));
	}

	@Test(timeout = 5000)
	public void testCommentStrippingBacktracking() {
		String openers = repeat("/*!1 /*M!2 /* ", 200000);
		assertThat(SchemaChange.stripForBlacklist("CREATE VIEW v AS SELECT '" + openers + "'").length(), is(not(0)));
		SchemaChange.stripForBlacklist("CREATE TABLE t (a int)" + repeat("\n", 200000));
		SchemaChange.stripForBlacklist("CREATE" + repeat(" ", 200000) + "x");
	}

	@Test
	public void testExecutableCommentsMatchRegex() {
		Pattern regex = Pattern.compile("/\\*M?!\\d+\\s*(.*)\\*/");
		String[] inputs = {
			"/*!50003 DROP FUNCTION IF EXISTS `f` */",
			"/*M!100100 CREATE EVENT e */ /*!50001 x */",
			"/*!40101 SET x */ CREATE /*!50000 FUNCTION */ f",
			"/*!50001\n  CREATE VIEW v */",
			"/*!50001 CREATE\nVIEW v */",
			"/*! no digits */ /*M! OR REPLACE */",
			"/*!1 **/",
			"/*!1 /*!2 a */ b */",
			"unterminated /*!1 a",
		};
		for ( String s : inputs )
			assertThat(s, SchemaChange.unwrapExecutableComments(s), is(regex.matcher(s).replaceAll("$1")));
	}

	@Test
	public void testCStyleCommentsMatchRegex() {
		Pattern regex = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
		String[] inputs = {
			"/* hi bob */ CREATE EVENT FOO",
			"/* some \n mulitline\n comment */ drop procedure",
			"a /*/ b */ c /**/ d",
			"a /* unterminated",
			"a */ b /* c",
		};
		for ( String s : inputs )
			assertThat(s, SchemaChange.stripCStyleComments(s), is(regex.matcher(s).replaceAll("")));
	}

	@Test
	public void testBlacklistAfterBlankLines() {
		assertThat(SchemaChange.parse("default_db", "\n\n  \n# comment\n\nDELETE FROM foo"), is(nullValue()));
		assertThat(SchemaChange.parse("default_db", "CREATE AGGREGATE FUNCTION f RETURNS STRING SONAME 'x.so'"), is(nullValue()));
		assertThat(SchemaChange.parse("default_db", "CREATE FUNCTION f RETURNS STRING SONAME 'x.so'"), is(nullValue()));
	}
}
