package com.zendesk.maxwell.replication;

import com.zendesk.maxwell.TestWithNameLogging;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MysqlVersionTest extends TestWithNameLogging {
	@Test
	public void testVersionComparison() {
		MysqlVersion version = new MysqlVersion(6, 5);
		assertTrue(version.atLeast(6, 5));
		assertTrue(version.atLeast(6, 4));
		assertTrue(version.atLeast(5, 9));

		assertFalse(version.atLeast(7, 2));
		assertFalse(version.atLeast(6, 6));
	}

	@Test
	public void testVersionId() {
		assertEquals(80036, MysqlVersion.parseVersionId("8.0.36", 8, 0));
		assertEquals(80036, MysqlVersion.parseVersionId("8.0.36-0ubuntu0.22.04.1", 8, 0));
		assertEquals(50744, MysqlVersion.parseVersionId("5.7.44-log", 5, 7));
		assertEquals(100612, MysqlVersion.parseVersionId("5.5.5-10.6.12-MariaDB-1:10.6.12+maria~ubu2004", 5, 5));
		assertEquals(110402, MysqlVersion.parseVersionId("11.4.2-MariaDB", 11, 4));
		assertEquals(80499, MysqlVersion.parseVersionId("8.4", 8, 4));
		assertEquals(80499, MysqlVersion.parseVersionId("garbage", 8, 4));
		assertEquals(80499, MysqlVersion.parseVersionId(null, 8, 4));
		assertEquals(50799, new MysqlVersion(5, 7).getVersionId());
	}
}
