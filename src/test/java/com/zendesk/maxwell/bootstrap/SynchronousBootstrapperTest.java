package com.zendesk.maxwell.bootstrap;

import com.zendesk.maxwell.CaseSensitivity;
import com.zendesk.maxwell.schema.Database;
import com.zendesk.maxwell.schema.Schema;
import com.zendesk.maxwell.schema.Table;
import com.zendesk.maxwell.schema.columndef.ColumnDef;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

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

	private Schema schemaWithShard1() {
		Table table = tableWithPK(Arrays.asList("domain"));
		Database db = new Database("shard_1", new ArrayList<>(Arrays.asList(table)), "utf8");
		return new Schema(new ArrayList<>(Arrays.asList(db)), "utf8", CaseSensitivity.CASE_SENSITIVE);
	}

	private BootstrapTask task(String database, String table) {
		BootstrapTask t = new BootstrapTask();
		t.id = 1L;
		t.database = database;
		t.table = table;
		return t;
	}

	@Test
	public void testFindTableForTask() throws Exception {
		Schema schema = schemaWithShard1();
		assertSame(
			schema.findDatabase("shard_1").findTable("reserved_pk_test"),
			SynchronousBootstrapper.findTableForTask(schema, task("shard_1", "reserved_pk_test"))
		);
	}

	@Test
	public void testMissingTableAbortsBootstrap() {
		assertThrows(
			SynchronousBootstrapper.BootstrapAbortException.class,
			() -> SynchronousBootstrapper.findTableForTask(schemaWithShard1(), task("shard_1", "nope"))
		);
	}

	@Test
	public void testMissingDatabaseAbortsBootstrap() {
		assertThrows(
			SynchronousBootstrapper.BootstrapAbortException.class,
			() -> SynchronousBootstrapper.findTableForTask(schemaWithShard1(), task("nonexistent", "reserved_pk_test"))
		);
	}
}
