package com.zendesk.maxwell.filtering;

import org.junit.Test;

import static org.junit.Assert.*;

public class FilterRegexGuardTest {
	private void assertRejected(String filter) {
		try {
			FilterRegexGuard.check(new FilterParser(filter).parse());
			fail("expected filter to be rejected: " + filter);
		} catch ( InvalidFilterException e ) {
			// expected
		}
	}

	private void assertAccepted(String filter) throws InvalidFilterException {
		FilterRegexGuard.check(new FilterParser(filter).parse());
	}

	@Test
	public void testNestedQuantifiers() {
		assertTrue(FilterRegexGuard.hasNestedQuantifier("(a+)+"));
		assertTrue(FilterRegexGuard.hasNestedQuantifier("(a*)*"));
		assertTrue(FilterRegexGuard.hasNestedQuantifier("^(\\w+\\s?)*$"));
		assertNotNull(FilterRegexGuard.backtrackingRisk("(x+x+)+y"));
		assertTrue(FilterRegexGuard.hasNestedQuantifier("((ab)+){2,}"));
		assertTrue(FilterRegexGuard.hasNestedQuantifier("(?:a{1,10}b)+"));
		assertTrue(FilterRegexGuard.hasNestedQuantifier("((a+))+"));
	}

	@Test
	public void testSafePatterns() {
		assertFalse(FilterRegexGuard.hasNestedQuantifier(""));
		assertFalse(FilterRegexGuard.hasNestedQuantifier("^foo$"));
		assertFalse(FilterRegexGuard.hasNestedQuantifier("^tmp_\\d+$"));
		assertFalse(FilterRegexGuard.hasNestedQuantifier("(foo|bar)+"));
		assertFalse(FilterRegexGuard.hasNestedQuantifier("(a+)?"));
		assertFalse(FilterRegexGuard.hasNestedQuantifier("(ab){1}c+"));
		assertFalse(FilterRegexGuard.hasNestedQuantifier("[(a+)]+"));
		assertFalse(FilterRegexGuard.hasNestedQuantifier("\\(a+\\)+"));
		assertFalse(FilterRegexGuard.hasNestedQuantifier("\\Q(a+)+\\E"));
	}

	@Test
	public void testPolynomialBacktracking() {
		assertEquals("adjacent unbounded quantifiers", FilterRegexGuard.backtrackingRisk("a*a*a*a*a*b"));
		assertEquals("adjacent unbounded quantifiers", FilterRegexGuard.backtrackingRisk(".*.*=.*"));
		assertEquals("adjacent unbounded quantifiers", FilterRegexGuard.backtrackingRisk("\\d+\\d{2,}x"));
		assertEquals("adjacent unbounded quantifiers", FilterRegexGuard.backtrackingRisk("[a-z]+?[a-z]*$"));
		assertNotNull(FilterRegexGuard.backtrackingRisk(".*a.*a.*a.*a"));

		assertNull(FilterRegexGuard.backtrackingRisk(".*foo.*bar.*"));
		assertNull(FilterRegexGuard.backtrackingRisk("^\\d+\\.\\d+$"));
		assertNull(FilterRegexGuard.backtrackingRisk("a*|a*"));
		assertNull(FilterRegexGuard.backtrackingRisk("a{1,5}a{1,5}"));
		assertNull(FilterRegexGuard.backtrackingRisk("a*\\*a*"));
	}

	@Test
	public void testFilterLevelCheck() throws InvalidFilterException {
		assertAccepted("exclude: *.*, include: db.tbl, blacklist: bad_db.*, include: /^foo_\\d+$/./bar/");
		assertAccepted("exclude: db.tbl.col = /^(foo|bar)$/");
		assertRejected("include: /(a+)+$/.*");
		assertRejected("include: *./(a*)*b/");
		assertRejected("exclude: db.tbl.col = /^(\\w+\\s?)*$/");
		assertRejected("exclude: db.tbl.col = /a*a*a*a*a*b/");

		StringBuilder longRegex = new StringBuilder();
		for ( int i = 0; i <= FilterRegexGuard.MAX_PATTERN_LENGTH; i++ )
			longRegex.append('a');
		assertRejected("include: /" + longRegex + "/.*");
	}
}
