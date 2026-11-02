package ru.fitprogress.ai

import com.google.gson.JsonParser
import java.util.Locale

data class ImportedProgramExercise(
    val name: String,
    val sets: Int,
    val reps: Int,
    val weightKg: Double = 0.0,
    val seconds: Int = 0,
    val restSeconds: Int = 60
)
data class ImportedProgramDay(val week: Int, val weekday: Int, val title: String, val exercises: List<ImportedProgramExercise>)
data class ImportedProgram(val name: String, val direction: String, val level: String, val weeks: Int, val days: List<ImportedProgramDay>)

/** Versioned, offline interchange. JSON schema version 1; CSV columns are documented in README. */
object ProgramInterchange {
    fun parseJson(text: String): ImportedProgram {
        val root = JsonParser.parseString(text).asJsonObject
        require(root.get("schemaVersion")?.asInt == 1) { "Неподдерживаемая версия JSON программы" }
        val days = root.getAsJsonArray("days").map { d ->
            val day = d.asJsonObject
            ImportedProgramDay(day.get("week").asInt, day.get("weekday").asInt,
                day.get("title")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                day.getAsJsonArray("exercises").map { n ->
                    val e = n.asJsonObject
                    ImportedProgramExercise(e.get("name").asString, e.get("sets").asInt, e.get("reps").asInt,
                        e.get("weightKg")?.takeUnless { it.isJsonNull }?.asDouble ?: 0.0,
                        e.get("seconds")?.takeUnless { it.isJsonNull }?.asInt ?: 0,
                        e.get("restSeconds")?.takeUnless { it.isJsonNull }?.asInt ?: 60)
                })
        }
        return validate(ImportedProgram(root.get("name").asString,
            root.get("direction")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
            root.get("level")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
            root.get("weeks").asInt, days))
    }

    fun parseCsv(text: String): ImportedProgram {
        val rows = text.lineSequence().filter { it.isNotBlank() }.map(::csvRow).toList()
        require(rows.size >= 2) { "CSV должен содержать заголовок и хотя бы одно упражнение" }
        val header = rows.first().map { it.trim().lowercase(Locale.ROOT) }
        val required = listOf("week", "weekday", "session", "exercise", "sets", "reps")
        require(header.toSet().containsAll(required)) { "CSV: обязательные колонки ${required.joinToString()}" }
        val index = header.withIndex().associate { it.value to it.index }
        fun value(row: List<String>, key: String, default: String = ""): String {
            val column = index[key] ?: return default
            return row.getOrNull(column) ?: default
        }
        val groups = linkedMapOf<Triple<Int, Int, String>, MutableList<ImportedProgramExercise>>()
        rows.drop(1).forEachIndexed { line, row ->
            try {
                val w = value(row, "week").toInt(); val d = value(row, "weekday").toInt(); val session = value(row, "session").ifBlank { "Тренировка" }
                val e = ImportedProgramExercise(value(row, "exercise"), value(row, "sets").toInt(), value(row, "reps").toInt(),
                    value(row, "weight_kg", "0").ifBlank { "0" }.replace(',', '.').toDouble(),
                    value(row, "seconds", "0").ifBlank { "0" }.toInt(), value(row, "rest_seconds", "60").ifBlank { "60" }.toInt())
                groups.getOrPut(Triple(w, d, session)) { mutableListOf() } += e
            } catch (e: Exception) { throw IllegalArgumentException("Некорректная строка CSV ${line + 2}: ${e.message}") }
        }
        val days = groups.map { (key, exercises) -> ImportedProgramDay(key.first, key.second, key.third, exercises) }
        return validate(ImportedProgram("Импортированная программа", "Своя программа", "Не указан", days.maxOfOrNull { it.week } ?: 0, days))
    }

    fun validate(p: ImportedProgram): ImportedProgram {
        require(p.name.isNotBlank() && p.name.length <= 100) { "Название программы: 1–100 символов" }
        require(p.weeks in 1..52) { "Длительность должна быть от 1 до 52 недель" }
        require(p.days.isNotEmpty() && p.days.size <= p.weeks * 7) { "Проверьте количество занятий" }
        require(p.days.all { it.week in 1..p.weeks && it.weekday in 1..7 && it.exercises.isNotEmpty() }) { "Неделя/день/упражнения вне допустимого диапазона" }
        require(p.days.map { Triple(it.week, it.weekday, it.title) }.distinct().size == p.days.size) { "Повторяется один и тот же день программы" }
        require(p.days.all { day -> day.exercises.all { e -> e.name.isNotBlank() && e.name.length <= 120 && e.sets in 1..30 && e.reps in 0..10000 && e.weightKg.isFinite() && e.weightKg in 0.0..2000.0 && e.seconds in 0..86400 && e.restSeconds in 0..3600 && (e.reps > 0 || e.seconds > 0) } }) { "Некорректные параметры упражнения" }
        return p
    }

    private fun csvRow(line: String): List<String> {
        val out = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') { if (quoted && line.getOrNull(i + 1) == '"') { cell.append('"'); i++ } else quoted = !quoted }
            else if (c == ',' && !quoted) { out += cell.toString(); cell.setLength(0) } else cell.append(c)
            i++
        }
        require(!quoted) { "Незакрытая кавычка в CSV" }; out += cell.toString(); return out
    }
}
