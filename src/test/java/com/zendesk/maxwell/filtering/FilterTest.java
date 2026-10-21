package com.zendesk.maxwell.filtering;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class FilterTest {
	private List<FilterPattern> filters;

	private List<FilterPattern> runParserTest(String input) throws Exception {
		return new FilterParser(input).parse();
	}

	@Test
	public void TestParser() throws Exception {
		filters = runParserTest("include:/foo.*/.bar");

		assertEquals(1, filters.size());
		assertEquals(FilterPatternType.INCLUDE, filters.get(0).getType());
		assertEquals(Pattern.compile("foo.*").toString(), filters.get(0).getDatabasePattern().toString());
		assertEquals("bar", FilterParser.literalValue(filters.get(0).getTablePattern()));
	}

	@Test
	public void TestBlacklists() throws Exception {
		filters = runParserTest("blacklist:foo.*");
		assertEquals(1, filters.size());
		assertEquals(FilterPatternType.BLACKLIST, filters.get(0).getType());
		assertEquals("foo", FilterParser.literalValue(filters.get(0).getDatabasePattern()));
	}

	@Test
	public void TestQuoting() throws Exception {
		String tests[] = {
			"include:`foo`.*",
			"include:'foo'.*",
			"include:\"foo\".*"
		};
		for ( String test : tests ) {
			filters = runParserTest(test);
			assertEquals(1, filters.size());
			assertEquals("foo", FilterParser.literalValue(filters.get(0).getDatabasePattern()));
		}
	}

	@Test
	public void TestOddNames() throws Exception {
		runParserTest("include: 1foo.bar");
		runParserTest("include: _foo._bar");

	}

	@Test
	public void TestAdvancedRegexp() throws Exception {
		String pattern = "\\w+ \\/[a-z]*1";
		filters = runParserTest("include: /" + pattern + "/.*");
		assertEquals(1, filters.size());
		assertEquals(Pattern.compile(pattern).toString(), filters.get(0).getDatabasePattern().toString());
	}

	@Test
	public void TestColumnFilterParse() throws Exception {
		String input = "include: 1foo.bar.column=foo";
		filters = runParserTest(input);
		assertEquals(1, filters.size());
		assertEquals(input, filters.get(0).toString());
	}

	@Test
	public void TestExcludeAll() throws Exception {
		Filter f = new Filter("exclude: *.*, include: foo.bar");
		assertTrue(f.includes("foo", "bar"));
		assertFalse(f.includes("anything", "else"));
	}

	@Test
	public void TestBlacklist() throws Exception {
		Filter f = new Filter("blacklist: seria.*");
		assertTrue(f.includes("foo", "bar"));
		assertFalse(f.includes("seria", "var"));
		assertTrue(f.isDatabaseBlacklisted("seria"));
		assertTrue(f.isTableBlacklisted("seria", "anything"));
	}

	@Test
	public void TestColumnFiltersAreIgnoredByIncludes() throws Exception {
		Filter f = new Filter("exclude: *.*, include: foo.bar.col=val");
		assertFalse(f.includes("foo", "bar"));
	}

	@Test
	public void TestColumnFilters() throws Exception {
		Map<String, Object> map = new HashMap<>();
		map.put("col", "val");
		Filter f = new Filter("exclude: *.*, include: foo.bar.col=val");
		assertFalse(f.includes("foo", "bar"));
		assertTrue(f.includes("foo", "bar", map));
	}

	@Test
	public void TestColumnFiltersFromOtherTables() throws Exception {
		Map<String, Object> map = new HashMap<>();
		map.put("col", "val");
		Filter f = new Filter("exclude: *.*, include: no.go.col=val");
		assertFalse(f.includes("foo", "bar"));
		assertFalse(f.includes("foo", "bar", map));
	}

	@Test
	public void TestNullValuesInData() throws Exception {
		Map<String, Object> map = new HashMap<>();
		map.put("col", null);
		Filter f = new Filter("exclude: *.*, include: foo.bar.col=null");
		assertFalse(f.includes("foo", "bar"));
		assertTrue(f.includes("foo", "bar", map));

		map.put("col", "null");
		assertTrue(f.includes("foo", "bar", map));

		map.remove("col");
		assertFalse(f.includes("foo", "bar", map));

	}

	@Test
	public void TestSetValidFilter() throws Exception {
		Filter f = new Filter("exclude: *.*, include: foo.bar.col=null");
		assertEquals(f.toString(), "exclude: *.*, include: foo.bar.col=null");
		f.set("blacklist: seria.*");
		assertEquals(f.toString(), "blacklist: seria.*");
	}

	@Test
	public void TestSetInvalidFilter() throws Exception {
		Filter f = new Filter("exclude: *.*, include: foo.bar.col=null");
		assertEquals(f.toString(), "exclude: *.*, include: foo.bar.col=null");
		try {
			f.set("bl: seria.*");
		} catch (InvalidFilterException e) {
			// do nothing
		}
		assertEquals(f.toString(), "exclude: *.*, include: foo.bar.col=null");
	}

	@Test
	public void TestToString() throws Exception {
		Filter f = new Filter("exclude: *.*, include: foo.bar.col=null");
		assertEquals(f.toString(), "exclude: *.*, include: foo.bar.col=null");
	}

	@Test
	public void TestEmptyToString() throws Exception {
		Filter f = new Filter("");
		assertEquals(f.toString(), "");
	}

	@Test
	public void TestQuotedNamesAreLiteral() throws Exception {
		Filter f = new Filter("exclude: shop.'orders$archive'");
		assertFalse(f.includes("shop", "orders$archive"));
		assertTrue(f.includes("shop", "orders"));

		f = new Filter("exclude: *.*, include: db.'pii.data'");
		assertTrue(f.includes("db", "pii.data"));
		assertFalse(f.includes("db", "piiXdata"));

		f = new Filter("exclude: db.`a|b`");
		assertFalse(f.includes("db", "a|b"));
		assertTrue(f.includes("db", "a"));
		assertTrue(f.includes("db", "b"));

		f = new Filter("exclude: \"we(ird\".`t[1`");
		assertFalse(f.includes("we(ird", "t[1"));
		assertTrue(f.includes("weird", "t1"));
	}

	@Test
	public void TestQuotedBlacklistIsLiteral() throws Exception {
		Filter f = new Filter("blacklist: shop.`orders$archive`, blacklist: `my$db`.*");
		assertTrue(f.isTableBlacklisted("shop", "orders$archive"));
		assertFalse(f.isTableBlacklisted("shop", "orders"));
		assertTrue(f.isDatabaseBlacklisted("my$db"));
		assertFalse(f.isDatabaseBlacklisted("my"));
	}

	@Test
	public void TestQuotedColumnValueIsLiteral() throws Exception {
		Map<String, Object> map = new HashMap<>();
		Filter f = new Filter("exclude: db.tbl.col = 'a.b'");
		map.put("col", "a.b");
		assertFalse(f.includes("db", "tbl", map));
		map.put("col", "aXb");
		assertTrue(f.includes("db", "tbl", map));
	}

	@Test
	public void TestRegexpsStillWork() throws Exception {
		Filter f = new Filter("exclude: db./orders\\$.*/");
		assertFalse(f.includes("db", "orders$archive"));
		assertTrue(f.includes("db", "orders"));
	}

	@Test
	public void TestInvalidRegexpThrowsInvalidFilterException() {
		String[] tests = { "include: db./tbl(/", "include: /[a/.*", "include: db.tbl.col=/(/" };
		for ( String test : tests ) {
			try {
				new FilterParser(test).parse();
				fail("expected InvalidFilterException for " + test);
			} catch ( InvalidFilterException e ) {
				// expected
			}
		}
	}

	@Test(timeout = 5000)
	public void TestUnterminatedRegexpThrowsInvalidFilterException() {
		try {
			new FilterParser("include: db./tbl").parse();
			fail("expected InvalidFilterException");
		} catch ( InvalidFilterException e ) {
			// expected
		}
	}

	@Test
	public void TestQuotedNamesRoundTrip() throws Exception {
		String[] inputs = {
			"exclude: shop.`orders$archive`",
			"include: `pii.data`.*",
			"blacklist: `a|b`.`t(1`",
			"exclude: db.tbl.col=`a.b`",
			"exclude: db.`back\\\\slash`",
			"exclude: db.`tick\\`name`",
			"include: foo./bar.*/",
		};
		for ( String input : inputs ) {
			Filter f = new Filter(input);
			assertEquals(input, f.toString());
			assertEquals(input, new Filter(f.toString()).toString());
		}

		Filter f = new Filter("exclude: db.`back\\\\slash`, exclude: db.`tick\\`name`");
		assertFalse(f.includes("db", "back\\slash"));
		assertFalse(f.includes("db", "tick`name"));
	}

	@Test
	public void TestLiteralsDoNotMatchTrailingNewline() throws Exception {
		Map<String, Object> map = new HashMap<>();
		Filter f = new Filter("exclude: *.*, include: db.tbl.col=approved");
		map.put("col", "approved");
		assertTrue(f.includes("db", "tbl", map));
		map.put("col", "approved\n");
		assertFalse(f.includes("db", "tbl", map));

		f = new Filter("exclude: db.tbl");
		assertTrue(f.includes("db", "tbl\n"));
		assertFalse(f.includes("db", "tbl"));
	}

	@Test
	public void TestControlCharactersRoundTrip() throws Exception {
		Map<String, Object> map = new HashMap<>();
		Filter f = new Filter("exclude: db.tbl.col=`a\\nb\\rc`");
		assertEquals("exclude: db.tbl.col=`a\\nb\\rc`", f.toString());

		Filter reparsed = new Filter(f.toString());
		assertEquals(f.toString(), reparsed.toString());
		map.put("col", "a\nb\rc");
		assertFalse(reparsed.includes("db", "tbl", map));
		map.put("col", "anbrc");
		assertTrue(reparsed.includes("db", "tbl", map));
	}

	@Test
	public void TestRegexpNullColumnValue() throws Exception {
		Map<String, Object> map = new HashMap<>();
		map.put("col", null);
		Filter f = new Filter("exclude: *.*, include: foo.bar.col=/^null$/");
		assertTrue(f.includes("foo", "bar", map));
	}

	@Test
	public void TestLiteralPatternsKeepPlainForm() throws Exception {
		filters = runParserTest("include: 'foo$bar'.baz");
		assertEquals("\\Afoo\\$bar\\z", filters.get(0).getDatabasePattern().pattern());
		assertEquals("\\Abaz\\z", filters.get(0).getTablePattern().pattern());
	}
}
