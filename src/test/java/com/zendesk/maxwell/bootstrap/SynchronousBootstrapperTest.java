package com.zendesk.maxwell.bootstrap;

import com.zendesk.maxwell.schema.Table;
import com.zendesk.maxwell.schema.columndef.ColumnDef;
import com.zendesk.maxwell.filtering.Filter;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class SynchronousBootstrapperTest {
	private Table tableWithPK(List<String> pkList) {
		List<ColumnDef> columns = new ArrayList<>();
		columns.add(ColumnDef.build("domain", "utf8", "varchar", (short) 0, false, null, 64L));
		columns.add(ColumnDef.build("key", "utf8", "varchar", (short) 1, false, null, 64L));
		columns.add(ColumnDef.build("value", "utf8", "text", (short) 2, false, null, null));

		return new Table("shard_1", "reserved_pk_test", "utf8", columns, pkList);
	}

	@Test
	public void testOrdersByQuotedPrimaryKey() {
		Table table = tableWithPK(Arrays.asList("domain", "key"));

		assertThat(
			SynchronousBootstrapper.buildSelectSQL("shard_1", "reserved_pk_test", table, null),
			is("select * from `shard_1`.`reserved_pk_test` order by `domain`,`key`")
		);
	}

	@Test
	public void testNoOrderByWhenTableHasNoPrimaryKey() {
		Table table = tableWithPK(new ArrayList<>());

		assertThat(
			SynchronousBootstrapper.buildSelectSQL("shard_1", "reserved_pk_test", table, null),
			is("select * from `shard_1`.`reserved_pk_test`")
		);
	}

	@Test
	public void testWhereClauseIsParameterizedAheadOfOrderBy() throws Exception {
		Table table = tableWithPK(Arrays.asList("domain", "key"));
		BootstrapWhereClause where = BootstrapWhereClause.parse("domain > 'a'", table.getColumnNames());

		assertThat(
			SynchronousBootstrapper.buildSelectSQL("shard_1", "reserved_pk_test", table, where),
			is("select * from `shard_1`.`reserved_pk_test` where (`domain` > ?) order by `domain`,`key`")
		);
		assertThat(where.getParams(), is(Arrays.<Object>asList("a")));
	}

	@Test
	public void testEmptyWhereClauseIsIgnored() throws Exception {
		Table table = tableWithPK(Arrays.asList("domain"));

		assertThat(
			SynchronousBootstrapper.buildSelectSQL("shard_1", "reserved_pk_test", table, BootstrapWhereClause.parse("", table.getColumnNames())),
			is("select * from `shard_1`.`reserved_pk_test` order by `domain`")
		);
	}

	@Test
	public void testTableFilterIsApplied() throws Exception {
		Filter filter = new Filter("exclude: secret.*, blacklist: shard_1.hidden");
		Set<String> columns = new HashSet<>(Arrays.asList("id"));

		assertTrue(SynchronousBootstrapper.isTableIncluded(filter, "shard_1", "minimal", columns));
		assertTrue(SynchronousBootstrapper.isTableIncluded(null, "shard_1", "minimal", columns));
		assertFalse(SynchronousBootstrapper.isTableIncluded(filter, "secret", "users", columns));
		assertFalse(SynchronousBootstrapper.isTableIncluded(filter, "shard_1", "hidden", columns));
		assertFalse(SynchronousBootstrapper.isTableIncluded(null, "mysql", "ha_health_check", columns));
	}

	@Test
	public void testColumnFilterCanReincludeTable() throws Exception {
		Filter filter = new Filter("exclude: shard_1.*, include: shard_1.minimal.account_id=1");

		assertTrue(SynchronousBootstrapper.isTableIncluded(filter, "shard_1", "minimal", new HashSet<>(Arrays.asList("id", "account_id"))));
		assertFalse(SynchronousBootstrapper.isTableIncluded(filter, "shard_1", "other", new HashSet<>(Arrays.asList("id"))));
	}

	@Test
	public void testEscapesBacktickInDatabaseAndTableName() {
		List<ColumnDef> columns = new ArrayList<>();
		columns.add(ColumnDef.build("id", "utf8", "int", (short) 0, true, null, null));
		Table table = new Table("we`ird", "ta`ble", "utf8", columns, Arrays.asList("id"));

		assertThat(
			SynchronousBootstrapper.buildSelectSQL("we`ird", "ta`ble", table, null),
			is("select * from `we``ird`.`ta``ble` order by `id`")
		);
	}
}
