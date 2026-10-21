package com.zendesk.maxwell.util;

import com.zendesk.maxwell.replication.BinlogPosition;
import com.zendesk.maxwell.replication.Position;
import com.zendesk.maxwell.row.RowIdentity;
import com.zendesk.maxwell.row.RowMap;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertThrows;

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


	@Test
	public void substitutedValuesCannotInjectPlaceholders() {
		TopicInterpolator raw = new TopicInterpolator("ns_%{database}_%{table}");
		assertThat(raw.interpolate("%{table}", "orders", null, false), equalTo("ns_%{table}_orders"));
		assertThat(raw.interpolate("%{type}", "orders", "insert", false), equalTo("ns_%{type}_orders"));

		TopicInterpolator escaped = new TopicInterpolator("ns_%{database}_%{table}", true);
		assertThat(escaped.interpolate("%{table}", "orders", null, false), equalTo("ns_%{table}_orders"));
	}

	@Test
	public void regexReplacementCharactersAreSubstitutedLiterally() {
		TopicInterpolator topicInterpolator = new TopicInterpolator(DATABASE_TABLE_TEMPLATE);

		assertThat(topicInterpolator.interpolate("db$1", "t\\able$", null, false), equalTo("db$1.t\\able$"));
	}

	@Test
	public void legacyModeKeepsExistingNames() {
		TopicInterpolator topicInterpolator = new TopicInterpolator("namespace_%{database}_%{table}");

		assertThat(topicInterpolator.interpolate("shop_eu", "orders", null, false), equalTo("namespace_shop_eu_orders"));
		assertThat(topicInterpolator.interpolate("shop", "eu_orders", null, false), equalTo("namespace_shop_eu_orders"));
	}

	@Test
	public void escapingSeparatesTenantsSharingAPrefix() {
		TopicInterpolator topicInterpolator = new TopicInterpolator("namespace_%{database}_%{table}", true);

		String victim = topicInterpolator.interpolate("shop_eu", "orders", null, false);
		String attacker = topicInterpolator.interpolate("shop", "eu_orders", null, false);

		assertThat(victim, equalTo("namespace_shop-5Feu_orders"));
		assertThat(attacker, equalTo("namespace_shop_eu-5Forders"));
		assertThat(attacker, not(equalTo(victim)));
	}

	@Test
	public void escapingLeavesNamesWithoutSeparatorsUnchanged() {
		TopicInterpolator dotted = new TopicInterpolator("maxwell.%{database}.%{table}", true);
		assertThat(dotted.interpolate("shop_eu", "order_items", null, false), equalTo("maxwell.shop_eu.order_items"));
		assertThat(dotted.interpolate("a.b", "c", null, false), equalTo("maxwell.a-2Eb.c"));
		assertThat(dotted.interpolate("a", "b.c", null, false), equalTo("maxwell.a.b-2Ec"));
		assertThat(dotted.interpolate("a-b", "c", null, false), equalTo("maxwell.a-2Db.c"));
	}

	@Test
	public void escapeCharacterIsChosenFromCharactersNotInTheTemplate() {
		TopicInterpolator dashed = new TopicInterpolator("mx-%{database}-%{table}", true);
		assertThat(dashed.interpolate("my-db", "order_items", null, false), equalTo("mx-my_2Ddb-order_5Fitems"));
		assertThat(dashed.interpolate("my", "db-order_items", null, false), equalTo("mx-my-db_2Dorder_5Fitems"));

		TopicInterpolator dashedUnderscored = new TopicInterpolator("mx-%{database}_%{table}", true);
		assertThat(dashedUnderscored.interpolate("a_b", "c-d.e", null, false), equalTo("mx-a.5Fb_c.2Dd.2Ee"));
	}

	@Test
	public void escapingCleanUpModeIsReversible() {
		TopicInterpolator topicInterpolator = new TopicInterpolator(DATABASE_TABLE_TYPE_TEMPLATE, true);

		assertThat(topicInterpolator.interpolate("a.b", "c", "insert", true), equalTo("a-2Eb.c.insert"));
		assertThat(topicInterpolator.interpolate("a_b", "c", "insert", true), equalTo("a-5Fb.c.insert"));
		assertThat(topicInterpolator.interpolate("a b", "c", "insert", true), equalTo("a-20b.c.insert"));
		assertThat(topicInterpolator.interpolate("caf\u00e9", "c", "insert", true), equalTo("caf-C3-A9.c.insert"));
		assertThat(topicInterpolator.interpolate("a", "b", "insert", true), equalTo("a.b.insert"));
	}

	@Test
	public void escapingCleanUpModeStripsTemplateWhitespace() {
		TopicInterpolator topicInterpolator = new TopicInterpolator("  %{database} .  %{table} \n", true);

		assertThat(topicInterpolator.generateFromRowMapAndCleanUpIllegalCharacters(newRowMap()), equalTo("testDb.testTable"));
	}

	@Test
	public void escapingRejectsTemplatesThatCannotBeMadeUnambiguous() {
		assertThrows(IllegalArgumentException.class, () -> new TopicInterpolator("%{database}%{table}", true));
		assertThrows(IllegalArgumentException.class, () -> new TopicInterpolator("%{database}x%{table}", true));
		assertThrows(IllegalArgumentException.class, () -> new TopicInterpolator("a-b_c.%{database}", true));

		TopicInterpolator onlyAdjacentAfterCleanUp = new TopicInterpolator("%{database} %{table}", true);
		assertThat(onlyAdjacentAfterCleanUp.interpolate("a", "b", null, false), equalTo("a b"));
		assertThrows(IllegalArgumentException.class, () -> onlyAdjacentAfterCleanUp.interpolate("a", "b", null, true));

		new TopicInterpolator("%{database}%{table}");
		new TopicInterpolator("a-b_c.%{database}");
		new TopicInterpolator("plain-topic_name.v1", true);
	}

	private RowIdentity newRowIdentity() {
		return new RowIdentity("testDb", "testTable", "insert", null);
	}


	@Test
	public void cleanUpTemplateWithWhitespaceOnlySeparatorIsRejectedUpFront() {
		TopicInterpolator escaped = new TopicInterpolator("%{database} %{table}", true);
		assertThrows(IllegalArgumentException.class, escaped::validateForCleanUp);

		new TopicInterpolator("%{database} %{table}", false).validateForCleanUp();
		new TopicInterpolator("%{database}.%{table}", true).validateForCleanUp();
	}

	@Test
	public void escapedNamesDoNotCollideAroundAlphanumericLiterals() {
		TopicInterpolator topic = new TopicInterpolator("%{database}_x%{table}", true);
		assertThat(topic.generateKafkaTopicFromRowMap(rowMap("a", "xb")), equalTo("a_xxb"));
		assertThat(topic.generateKafkaTopicFromRowMap(rowMap("a_x", "b")), equalTo("a-5Fx_xb"));
		assertThat(topic.generateKafkaTopicFromRowMap(rowMap("a", "x_b")), equalTo("a_xx-5Fb"));
	}

	@Test
	public void escapedInterpolationIsInjective() {
		String[] templates = {
			"namespace_%{database}_%{table}", "ns%{database}_%{table}", "a_%{database}.%{table}",
			"x-%{database}", "%{database}_x%{table}", "%{database}.%{table}.%{type}", "p:%{database}:%{table}"
		};
		char[] alphabet = {'a', 'x', '_', '.', '-', ':', '%', '5', 'F', '$', '\\', '\u00e9'};
		List<String> values = new ArrayList<>();
		values.add("");
		for (int len = 1; len <= 2; len++) {
			int combos = (int) Math.pow(alphabet.length, len);
			for (int n = 0; n < combos; n++) {
				StringBuilder sb = new StringBuilder();
				for (int i = 0, k = n; i < len; i++, k /= alphabet.length)
					sb.append(alphabet[k % alphabet.length]);
				values.add(sb.toString());
			}
		}

		for (String template : templates) {
			TopicInterpolator interpolator = new TopicInterpolator(template, true).validateForCleanUp();
			for (int mode = 0; mode < 3; mode++) {
				Map<String, String> seen = new HashMap<>();
				for (String db : values) {
					for (String table : values) {
						String type = template.contains("%{type}") ? table + db : null;
						String out = mode == 2
							? interpolator.generateKafkaTopicFromRowMap(new RowMap(type, db, table, 0L, Collections.emptyList(), new Position(new BinlogPosition(3, "mysql.1"), 0L)))
							: interpolator.interpolate(db, table, type, mode == 1);
						if (mode == 2 && template.replaceAll("%\\{\\w+\\}", "").matches("[A-Za-z0-9._-]*") && !out.matches("[A-Za-z0-9._-]*"))
							throw new AssertionError(template + " produced illegal kafka topic " + out);
						String identity = (template.contains("%{database}") ? db : "") + "\u0000"
							+ (template.contains("%{table}") ? table : "") + "\u0000" + type;
						String previous = seen.put(out, identity);
						if (previous != null && !previous.equals(identity))
							throw new AssertionError(template + " (mode=" + mode + ") maps both " + previous + " and " + identity + " to " + out);
					}
				}
			}
		}
	}

	private RowMap newRowMap() {
		return new RowMap("insert", "testDb", "testTable", System.currentTimeMillis(), Collections.emptyList(), new Position(new BinlogPosition(3, "mysql.1"), 0L));
	}

	@Test
	public void escapedKafkaTopicsAreLegalAndUnambiguous() {
		TopicInterpolator topic = new TopicInterpolator("namespace_%{database}_%{table}", true);

		assertThat(topic.generateKafkaTopicFromRowMap(rowMap("shop_eu", "orders")), equalTo("namespace_shop-5Feu_orders"));
		assertThat(topic.generateKafkaTopicFromRowMap(rowMap("shop", "eu_orders")), equalTo("namespace_shop_eu-5Forders"));
		assertThat(topic.generateKafkaTopicFromRowMap(rowMap("db", "t$x")), equalTo("namespace_db_t-24x"));
		assertThat(topic.generateKafkaTopicFromRowMap(rowMap("db", "t_x")), equalTo("namespace_db_t-5Fx"));
		assertThat(topic.generateKafkaTopicFromRowMap(rowMap("db", "caf\u00e9")), equalTo("namespace_db_caf-C3-A9"));
		assertThat(topic.generateKafkaTopicFromRowIdentity(new RowIdentity("db", "a\\b", null, null)), equalTo("namespace_db_a-5Cb"));
		assertThat(topic.generateKafkaTopicFromRowMap(rowMap("%{table}", "x")), equalTo("namespace_-25-7Btable-7D_x"));
	}
}
