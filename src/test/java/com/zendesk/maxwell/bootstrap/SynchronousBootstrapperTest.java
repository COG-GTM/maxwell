package com.zendesk.maxwell.bootstrap;

import com.zendesk.maxwell.filtering.Filter;
import com.zendesk.maxwell.row.RowMap;
import com.zendesk.maxwell.schema.Table;
import com.zendesk.maxwell.schema.columndef.ColumnDef;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

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
	public void testWhereClauseIsPassedThroughAheadOfOrderBy() {
		Table table = tableWithPK(Arrays.asList("domain", "key"));

		assertThat(
			SynchronousBootstrapper.buildSelectSQL("shard_1", "reserved_pk_test", table, "id > 1"),
			is("select * from `shard_1`.`reserved_pk_test` where id > 1 order by `domain`,`key`")
		);
	}

	@Test
	public void testEmptyWhereClauseIsIgnored() {
		Table table = tableWithPK(Arrays.asList("domain"));

		assertThat(
			SynchronousBootstrapper.buildSelectSQL("shard_1", "reserved_pk_test", table, ""),
			is("select * from `shard_1`.`reserved_pk_test` order by `domain`")
		);
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

	private static final Set<String> COLUMNS = new HashSet<>(Arrays.asList("id", "status"));

	private RowMap rowWithStatus(String db, String table, String status) {
		RowMap row = new RowMap("bootstrap-insert", db, table, 0L, Arrays.asList("id"), null);
		row.putData("id", 1);
		row.putData("status", status);
		return row;
	}

	@Test
	public void testTableAllowedWithNoFilter() {
		assertThat(SynchronousBootstrapper.shouldBootstrapTable(null, "shard_1", "minimal", COLUMNS), is(true));
		assertThat(SynchronousBootstrapper.shouldBootstrapRow(null, "shard_1", "minimal", rowWithStatus("shard_1", "minimal", "x")), is(true));
	}

	@Test
	public void testExcludedTableIsRejected() throws Exception {
		Filter filter = new Filter("maxwell", "exclude: payments.*");

		assertThat(SynchronousBootstrapper.shouldBootstrapTable(filter, "payments", "cards", COLUMNS), is(false));
		assertThat(SynchronousBootstrapper.shouldBootstrapTable(filter, "shard_1", "minimal", COLUMNS), is(true));
	}

	@Test
	public void testTableOutsideIncludeListIsRejected() throws Exception {
		Filter filter = new Filter("maxwell", "exclude: *.*, include: shard_1.*");

		assertThat(SynchronousBootstrapper.shouldBootstrapTable(filter, "shard_1", "minimal", COLUMNS), is(true));
		assertThat(SynchronousBootstrapper.shouldBootstrapTable(filter, "mysql", "user", COLUMNS), is(false));
		assertThat(SynchronousBootstrapper.shouldBootstrapTable(filter, "hr", "salaries", COLUMNS), is(false));
	}

	@Test
	public void testBlacklistedTableIsRejected() throws Exception {
		Filter filter = new Filter("maxwell", "blacklist: hr.salaries");

		assertThat(SynchronousBootstrapper.isTableBlacklisted(filter, "hr", "salaries"), is(true));
		assertThat(SynchronousBootstrapper.shouldBootstrapTable(filter, "hr", "salaries", COLUMNS), is(false));
		assertThat(SynchronousBootstrapper.shouldBootstrapTable(filter, "hr", "people", COLUMNS), is(true));
	}

	@Test
	public void testSystemBlacklistedTableIsRejectedWithoutFilter() {
		assertThat(SynchronousBootstrapper.isTableBlacklisted(null, "mysql", "ha_health_check"), is(true));
		assertThat(SynchronousBootstrapper.shouldBootstrapTable(null, "mysql", "rds_heartbeat2", COLUMNS), is(false));
	}

	@Test
	public void testColumnValueFiltersApplyToRows() throws Exception {
		Filter filter = new Filter("maxwell", "exclude: shard_1.orders, include: shard_1.orders.status=shipped");

		assertThat(SynchronousBootstrapper.shouldBootstrapTable(filter, "shard_1", "orders", COLUMNS), is(true));
		assertThat(SynchronousBootstrapper.shouldBootstrapRow(filter, "shard_1", "orders", rowWithStatus("shard_1", "orders", "shipped")), is(true));
		assertThat(SynchronousBootstrapper.shouldBootstrapRow(filter, "shard_1", "orders", rowWithStatus("shard_1", "orders", "pending")), is(false));
	}

	@Test
	public void testColumnExcludeFilterDropsMatchingRows() throws Exception {
		Filter filter = new Filter("maxwell", "exclude: shard_1.orders.status=secret");

		assertThat(SynchronousBootstrapper.shouldBootstrapTable(filter, "shard_1", "orders", COLUMNS), is(true));
		assertThat(SynchronousBootstrapper.shouldBootstrapRow(filter, "shard_1", "orders", rowWithStatus("shard_1", "orders", "secret")), is(false));
		assertThat(SynchronousBootstrapper.shouldBootstrapRow(filter, "shard_1", "orders", rowWithStatus("shard_1", "orders", "open")), is(true));
	}

	@Test
	public void testColumnFilterOnMissingColumnDoesNotAdmitExcludedTable() throws Exception {
		Filter filter = new Filter("maxwell", "exclude: shard_1.orders, include: shard_1.orders.region=eu");

		assertThat(SynchronousBootstrapper.shouldBootstrapTable(filter, "shard_1", "orders", COLUMNS), is(false));
	}

	@Test
	public void testMaxwellSystemTablesStayWhitelisted() throws Exception {
		Filter filter = new Filter("maxwell", "exclude: *.*");

		assertThat(SynchronousBootstrapper.shouldBootstrapTable(filter, "maxwell", "heartbeats", COLUMNS), is(true));
		assertThat(SynchronousBootstrapper.shouldBootstrapTable(filter, "maxwell", "positions", COLUMNS), is(false));
	}
}
