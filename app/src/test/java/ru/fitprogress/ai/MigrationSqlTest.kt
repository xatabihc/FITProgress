package ru.fitprogress.ai

import com.google.gson.JsonParser
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.sql.DriverManager

/** Executes the exact migration SQL; Android Room opening is tested separately on device. */
class MigrationSqlTest {
    private fun schema(version: Int): com.google.gson.JsonObject {
        val file = listOf(File("app/schemas/ru.fitprogress.ai.FitDatabase/$version.json"), File("schemas/ru.fitprogress.ai.FitDatabase/$version.json")).first { it.exists() }
        return JsonParser.parseString(file.readText()).asJsonObject["database"].asJsonObject
    }
    @Test fun migrationPreservesTenThousandHistoryPayloadsByteForByte() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            val old = schema(1)
            old["entities"].asJsonArray.forEach { e -> val entity = e.asJsonObject; c.createStatement().use { it.execute(entity["createSql"].asString.replace("\u0024{TABLE_NAME}", entity["tableName"].asString)) } }
            c.autoCommit = false
            val values = (0 until 10000).map { "{\"id\":\"w-$it\",\"name\":\"История $it\",\"weight\":${it % 200}}" }
            c.prepareStatement("INSERT INTO workouts(id,payload) VALUES (?,?)").use { stmt -> values.forEachIndexed { i, payload -> stmt.setString(1, "w-$i"); stmt.setString(2, payload); stmt.addBatch() }; stmt.executeBatch() }
            c.createStatement().use { it.execute("INSERT INTO exercises VALUES ('e','exercise-snapshot')"); it.execute("INSERT INTO runs VALUES ('r','gps-snapshot')"); it.execute(MIGRATION_1_2_SQL) }; c.commit()
            c.createStatement().executeQuery("SELECT id,payload FROM workouts").use { rs -> var count = 0; while(rs.next()) { val i = rs.getString(1).removePrefix("w-").toInt(); assertEquals(values[i], rs.getString(2)); count++ }; assertEquals(10000, count) }
            for(table in listOf("exercises", "runs")) c.createStatement().executeQuery("SELECT COUNT(*) FROM $table").use { assertTrue(it.next()); assertEquals(1, it.getInt(1)) }
            c.createStatement().executeQuery("SELECT COUNT(*) FROM personal_records").use { assertTrue(it.next()); assertEquals(0, it.getInt(1)) }
            c.createStatement().use { it.execute(MIGRATION_1_2_SQL) } // Idempotent creation, never a DROP.
            val fresh = schema(2); assertEquals(4, fresh["entities"].asJsonArray.size())
            assertEquals(old["entities"].asJsonArray.toList(), fresh["entities"].asJsonArray.filter { it.asJsonObject["tableName"].asString != "personal_records" })
        }
    }
    @Test fun migrationHasNoDestructiveStatement() { assertFalse(MIGRATION_1_2_SQL.contains("DROP", ignoreCase = true)); assertFalse(MIGRATION_1_2_SQL.contains("DELETE", ignoreCase = true)) }
}
