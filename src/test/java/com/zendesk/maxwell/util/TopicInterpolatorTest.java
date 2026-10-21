package com.zendesk.maxwell.util;

import com.zendesk.maxwell.replication.BinlogPosition;
import com.zendesk.maxwell.replication.Position;
import com.zendesk.maxwell.row.RowIdentity;
import com.zendesk.maxwell.row.RowMap;
import org.junit.Test;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;

public class TopicInterpolatorTest {

	private static final String DATABASE_TEMPLATE = "%{database}";

	private static final String DATABASE_TABLE_TEMPLATE = DATABASE_TEMPLATE + ".%{table}";

	private static final String DATABASE_TABLE_TYPE_TEMPLATE = DATABASE_TABLE_TEMPLATE + ".%{type}";


	@Test
	public void doNothingIfStringIsNotInterpolated() {
		RowIdentity pk = newRowIdentity();
		RowMap r = newRowMap();

		TopicInterpolator myString = new TopicInterpolator("abcxyz");
		assertThat(myString.generateFromRowIdentity(pk), equalTo("abcxyz"));
		assertThat(myString.generateFromRowMap(r), equalTo("abcxyz"));

		TopicInterpolator emptyStr = new TopicInterpolator("");
		assertThat(emptyStr.generateFromRowIdentity(pk), equalTo(""));
		assertThat(emptyStr.generateFromRowMap(r), equalTo(""));
	}

	@Test
	public void generateFromRowIdentityCorrectly() {
		TopicInterpolator db = new TopicInterpolator(DATABASE_TEMPLATE);
		assertThat(db.generateFromRowIdentity(newRowIdentity()), equalTo("testDb"));
		assertThat(db.generateFromRowIdentity(new RowIdentity("testDb", "testTable", null, null)),
				equalTo("testDb"));
		assertThat(db.generateFromRowIdentity(new RowIdentity("testDb", null, "update", null)),
				equalTo("testDb"));
		assertThat(db.generateFromRowIdentity(new RowIdentity("testDb", null, null, null)),
				equalTo("testDb"));


		TopicInterpolator dbTable = new TopicInterpolator(DATABASE_TABLE_TEMPLATE);
		assertThat(dbTable.generateFromRowIdentity(newRowIdentity()), equalTo("testDb.testTable"));
		assertThat(dbTable.generateFromRowIdentity(new RowIdentity("testDb", "testTable", null, null)),
				equalTo("testDb.testTable"));
		assertThat(dbTable.generateFromRowIdentity(new RowIdentity("testDb", null, "update", null)),
				equalTo("testDb."));
		assertThat(dbTable.generateFromRowIdentity(new RowIdentity("testDb", null, null, null)),
				equalTo("testDb."));

	}

	@Test
	public void generateFromRowMapCorrectly() {
		TopicInterpolator db = new TopicInterpolator(DATABASE_TEMPLATE);
		assertThat(db.generateFromRowMap(newRowMap()), equalTo("testDb"));
		assertThat(db.generateFromRowMap(new RowMap("insert", "testDb", "testTable", System.currentTimeMillis(), Collections.emptyList(), new Position(new BinlogPosition(3, "mysql.1"), 0L))),
				equalTo("testDb"));
		assertThat(db.generateFromRowMap(new RowMap("insert", "testDb", null, System.currentTimeMillis(), Collections.emptyList(), new Position(new BinlogPosition(3, "mysql.1"), 0L))),
				equalTo("testDb"));
		assertThat(db.generateFromRowMap(new RowMap(null, "testDb", null, System.currentTimeMillis(), Collections.emptyList(), new Position(new BinlogPosition(3, "mysql.1"), 0L))),
				equalTo("testDb"));

		TopicInterpolator dbTable = new TopicInterpolator(DATABASE_TABLE_TEMPLATE);
		assertThat(dbTable.generateFromRowMap(newRowMap()), equalTo("testDb.testTable"));
		assertThat(dbTable.generateFromRowMap(new RowMap(null, "testDb", "testTable", System.currentTimeMillis(), Collections.emptyList(), new Position(new BinlogPosition(3, "mysql.1"), 0L))),
				equalTo("testDb.testTable"));
		assertThat(dbTable.generateFromRowMap(new RowMap("insert", "testDb", null, System.currentTimeMillis(), Collections.emptyList(), new Position(new BinlogPosition(3, "mysql.1"), 0L))),
				equalTo("testDb."));
		assertThat(dbTable.generateFromRowMap(new RowMap(null, "testDb", null, System.currentTimeMillis(), Collections.emptyList(), new Position(new BinlogPosition(3, "mysql.1"), 0L))),
				equalTo("testDb."));

		TopicInterpolator dbTableType = new TopicInterpolator(DATABASE_TABLE_TYPE_TEMPLATE);
		assertThat(dbTableType.generateFromRowMap(newRowMap()), equalTo("testDb.testTable.insert"));
		assertThat(dbTable.generateFromRowMap(new RowMap(null, "testDb", "testTable", System.currentTimeMillis(), Collections.emptyList(), new Position(new BinlogPosition(3, "mysql.1"), 0L))),
				equalTo("testDb.testTable"));
		assertThat(dbTableType.generateFromRowMap(new RowMap("insert", "testDb", null, System.currentTimeMillis(), Collections.emptyList(), new Position(new BinlogPosition(3, "mysql.1"), 0L))),
				equalTo("testDb..insert"));
		assertThat(dbTableType.generateFromRowMap(new RowMap(null, "testDb", null, System.currentTimeMillis(), Collections.emptyList(), new Position(new BinlogPosition(3, "mysql.1"), 0L))),
				equalTo("testDb.."));
	}

	@Test
	public void generateFromRowMapCorrectlyAndTrimAllWhitesSpaces() {
		TopicInterpolator dbTable = new TopicInterpolator("  %{database} .  %{table} \n");
		assertThat(dbTable.generateFromRowMapAndCleanUpIllegalCharacters(newRowMap()), equalTo("testDb.testTable"));
		assertThat(dbTable.generateFromRowMapAndCleanUpIllegalCharacters(new RowMap(null, "testDb", "testTable", System.currentTimeMillis(), Collections.emptyList(), new Position(new BinlogPosition(3, "mysql.1"), 0L))),
				equalTo("testDb.testTable"));
		assertThat(dbTable.generateFromRowMapAndCleanUpIllegalCharacters(new RowMap("insert", "testDb", null, System.currentTimeMillis(), Collections.emptyList(), new Position(new BinlogPosition(3, "mysql.1"), 0L))),
				equalTo("testDb."));
		assertThat(dbTable.generateFromRowMapAndCleanUpIllegalCharacters(new RowMap(null, "testDb", null, System.currentTimeMillis(), Collections.emptyList(), new Position(new BinlogPosition(3, "mysql.1"), 0L))),
				equalTo("testDb."));

	}


	@Test
	public void interpolateFullTopic() {
		TopicInterpolator topicInterpolator = new TopicInterpolator(DATABASE_TABLE_TYPE_TEMPLATE);

		assertThat(topicInterpolator.interpolate("testDb", "testTable", "insert", true), equalTo("testDb.testTable.insert"));
	}

	@Test
	public void interpolateTopicWithoutType() {
		TopicInterpolator topicInterpolator = new TopicInterpolator(DATABASE_TABLE_TEMPLATE);

		assertThat(topicInterpolator.interpolate("testDb", "testTable", null, true), equalTo("testDb.testTable"));
	}

	@Test
	public void interpolateTopicWithoutDatabase() {
		TopicInterpolator topicInterpolator = new TopicInterpolator("%{table}.%{type}");

		assertThat(topicInterpolator.interpolate(null, "testTable", "insert", true), equalTo("testTable.insert"));
	}

	@Test
	public void interpolateTopicWithoutTable() {
		TopicInterpolator topicInterpolator = new TopicInterpolator("%{database}.%{type}");

		assertThat(topicInterpolator.interpolate("testDb", null, "insert", true), equalTo("testDb.insert"));
	}

	@Test
	public void replaceAnyNonAlphaNumericCharacterBeforeInterpolation() {
		TopicInterpolator topicInterpolator = new TopicInterpolator(DATABASE_TABLE_TYPE_TEMPLATE);

		assertThat(topicInterpolator.interpolate("&test Db.db!", "&test Table.table!", "&insert t.table!", true), equalTo("_test_Db_db_._test_Table_table_._insert_t_table_"));
	}

	@Test
	public void doNOTReplaceAnyNonAlphaNumericCharacterBeforeInterpolation() {
		TopicInterpolator topicInterpolator = new TopicInterpolator(DATABASE_TABLE_TYPE_TEMPLATE);

		assertThat(topicInterpolator.interpolate("&test Db.db!", "&test Table.table!", "&insert t.table!", false), equalTo("&test Db.db!.&test Table.table!.&insert t.table!"));
	}

	@Test
	public void interpolatesRegexReplacementMetacharactersLiterally() {
		TopicInterpolator dbTable = new TopicInterpolator(DATABASE_TABLE_TYPE_TEMPLATE);

		assertThat(dbTable.generateFromRowMap(rowMap("d$b", "t$x")), equalTo("d$b.t$x.insert"));
		assertThat(dbTable.generateFromRowMap(rowMap("db", "a$1")), equalTo("db.a$1.insert"));
		assertThat(dbTable.generateFromRowMap(rowMap("db", "t\\")), equalTo("db.t\\.insert"));
		assertThat(dbTable.generateFromRowMap(rowMap("db", "${x}\\$0")), equalTo("db.${x}\\$0.insert"));
		assertThat(dbTable.generateFromRowIdentity(new RowIdentity("db", "t$x", null, null)), equalTo("db.t$x."));
		assertThat(dbTable.generateFromRowMapAndCleanUpIllegalCharacters(rowMap("db", "t$x\\")), equalTo("db.t_x_.insert"));
	}

	@Test
	public void generateKafkaTopicReplacesCharactersKafkaDisallows() {
		TopicInterpolator topic = new TopicInterpolator("namespace_%{database}_%{table}");

		assertThat(topic.generateKafkaTopicFromRowMap(rowMap("test-Db.1", "my_table")), equalTo("namespace_test-Db.1_my_table"));
		assertThat(topic.generateKafkaTopicFromRowMap(rowMap("db", "t$x")), equalTo("namespace_db_t_x"));
		assertThat(topic.generateKafkaTopicFromRowMap(rowMap("db", "caf\u00e9 \\")), equalTo("namespace_db_caf___"));
		assertThat(topic.generateKafkaTopicFromRowIdentity(new RowIdentity("d$b", "t\\", null, null)), equalTo("namespace_d_b_t_"));

		// String.replaceAll used to drop backslashes (`a\b` -> `ab`); keep existing kafka topics where they were
		assertThat(topic.generateKafkaTopicFromRowMap(rowMap("db", "a\\b")), equalTo("namespace_db_ab"));
		assertThat(topic.generateKafkaTopicFromRowMap(rowMap("db", "a\\b")),
				equalTo("namespace_%{database}_%{table}".replaceAll("%\\{database\\}", "db").replaceAll("%\\{table\\}", "a\\b")));

		TopicInterpolator plain = new TopicInterpolator("maxwell");
		assertThat(plain.generateKafkaTopicFromRowMap(rowMap("db", "t$x")), equalTo("maxwell"));
	}

	private RowMap rowMap(String database, String table) {
		return new RowMap("insert", database, table, System.currentTimeMillis(), Collections.emptyList(), new Position(new BinlogPosition(3, "mysql.1"), 0L));
	}

	private RowIdentity newRowIdentity() {
		return new RowIdentity("testDb", "testTable", "insert", null);
	}

	private RowMap newRowMap() {
		return new RowMap("insert", "testDb", "testTable", System.currentTimeMillis(), Collections.emptyList(), new Position(new BinlogPosition(3, "mysql.1"), 0L));
	}
}
