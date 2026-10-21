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
		assertEquals(80035, MysqlVersion.versionId("8.0.35", 8, 0));
		assertEquals(50744, MysqlVersion.versionId("5.7.44-log", 5, 7));
		assertEquals(100612, MysqlVersion.versionId("10.6.12-MariaDB", 10, 6));
		assertEquals(100612, MysqlVersion.versionId("5.5.5-10.6.12-MariaDB-1:10.6.12+maria~ubu2004", 10, 6));
		assertEquals(80099, MysqlVersion.versionId("8.0", 8, 0));
		assertEquals(80499, MysqlVersion.versionId(null, 8, 4));
	}
}
