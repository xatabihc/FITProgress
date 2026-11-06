package ru.fitprogress.ai

import com.google.gson.*
import java.time.*
import java.util.Locale
import java.util.UUID

/** Reads user-owned JSON exports only. Unsupported rows are reported, never guessed. */
data class WorkoutImportState(val source: String? = null, val preview: WorkoutImportPreview? = null, val mapping: Map<String, String> = emptyMap(), val report: String = "")
data class WorkoutImportPreview(val backup: Backup, val skipped: Int, val issues: List<String>, val matches: List<String>)
object WorkoutImport {
    private fun stable(value: String) = "external-" + UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8))
    private fun canonical(value: JsonElement): String = when {
        value.isJsonObject -> value.asJsonObject.entrySet().sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") { "${it.key}:${canonical(it.value)}" }
        value.isJsonArray -> value.asJsonArray.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        else -> value.toString()
    }
    private fun JsonObject.field(vararg keys: String): JsonElement? = keys.firstNotNullOfOrNull { get(it)?.takeUnless(JsonElement::isJsonNull) }
    private fun JsonObject.text(vararg keys: String) = field(*keys)?.asString?.trim()
    private fun JsonObject.number(vararg keys: String) = text(*keys)?.replace(',', '.')?.toDouble()?.also { require(it.isFinite()) }
    private fun JsonObject.integer(vararg keys: String): Int? = number(*keys)?.let { require(it % 1.0 == 0.0 && it in 0.0..Int.MAX_VALUE.toDouble()); it.toInt() }
    fun normalize(name: String) = name.trim().lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("\\s+"), " ")
    fun parse(text: String, catalog: List<Exercise>, mapping: Map<String, String> = emptyMap()): WorkoutImportPreview {
        require(text.toByteArray(Charsets.UTF_8).size <= 20_000_000) { "Файл больше 20 МБ" }
        val root = JsonParser.parseString(text.removePrefix("\uFEFF"))
        val rows = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.field("workouts", "trainings", "trainingSessions", "тренировки")?.asJsonArray
            else -> null
        } ?: error("Не найден список workouts / trainings / trainingSessions / тренировки. Экспортируйте пользовательский JSON из исходного приложения.")
        require(rows.size() <= 20000) { "В файле больше 20 000 тренировок" }
        val exercises = linkedMapOf<String, Exercise>(); val workouts = linkedMapOf<String, Workout>()
        val issues = mutableListOf<String>(); val matches = linkedSetOf<String>(); var skipped = 0
        rows.forEachIndexed { index, node ->
            try {
                val row = node.asJsonObject
                val sourceId = row.text("id", "uuid") ?: canonical(row)
                val id = stable("workout:$sourceId")
                if(id in workouts) { skipped++; issues += "Запись ${index + 1}: повтор идентификатора в файле"; return@forEachIndexed }
                val name = row.text("name", "title", "название") ?: error("нет названия")
                val rawDate = row.text("date", "scheduledDate", "дата") ?: error("нет даты")
                val date = runCatching { LocalDate.parse(rawDate) }.getOrElse { Instant.parse(rawDate).atZone(ZoneId.systemDefault()).toLocalDate() }
                val status = when(row.text("status", "state", "статус")?.lowercase(Locale.ROOT)) {
                    "done", "completed", "finished", "завершено", "завершена" -> "done"
                    "active", "paused", "in_progress", "незавершена", "на паузе" -> "paused"
                    "planned", "scheduled", "pending", "запланирована", "в плане" -> "planned"
                    else -> error("неизвестный или отсутствующий статус")
                }
                val itemRows = row.field("items", "exercises", "упражнения")?.asJsonArray ?: error("нет списка упражнений")
                require(itemRows.size() <= 300) { "больше 300 упражнений" }
                val pending = linkedMapOf<String, Exercise>()
                val items = itemRows.mapIndexed { itemIndex, itemNode ->
                    val item = itemNode.asJsonObject
                    val title = item.text("name", "exerciseName", "название") ?: error("нет названия упражнения")
                    val norm = normalize(title)
                    val selected = mapping[title]?.let { chosen -> catalog.firstOrNull { it.id == chosen } ?: error("сопоставленное упражнение удалено") }
                        ?: catalog.firstOrNull { normalize(it.name) == norm }
                    val exerciseId = selected?.id ?: stable("exercise:$norm")
                    if(selected == null) pending[exerciseId] = Exercise(id = exerciseId, name = title, sets = 1, reps = 0, notes = "Импорт из пользовательского JSON. Техника и параметры по умолчанию не указаны источником.")
                    val sets = item.field("sets", "approaches", "подходы")?.asJsonArray ?: error("нет списка подходов у «$title»")
                    require(sets.size() in 1..100) { "число подходов вне диапазона 1–100" }
                    val entries = sets.mapIndexed { setIndex, setNode ->
                        val set = setNode.asJsonObject
                        val reps = set.integer("reps", "repetitions", "повторения")
                        val seconds = set.integer("seconds", "durationSeconds", "секунды")
                        val distance = set.number("distanceMeters", "дистанция")
                        require(reps != null || seconds != null || distance != null) { "нет результата или плана подхода у «$title»" }
                        SetEntry(id = stable("$id:$itemIndex:$setIndex"), reps = reps ?: 0,
                            weight = set.number("weight", "weightKg", "вес") ?: 0.0, seconds = seconds ?: 0,
                            distanceMeters = distance ?: 0.0, done = set.field("done", "completed", "выполнен")?.asBoolean ?: false,
                            restSeconds = set.integer("restSeconds", "rest"), comment = set.text("comment", "notes") ?: "").also(SessionEdits::validate)
                    }
                    WorkoutItem(id = stable("$id:item:$itemIndex"), exerciseId = exerciseId, name = title, sets = entries,
                        rest = item.integer("rest", "restSeconds") ?: 0, notes = item.text("notes", "comment") ?: "",
                        muscleGroup = selected?.category)
                }
                val duration = row.number("accumulatedMs", "durationMs") ?: 0.0
                require(duration in 0.0..604800000.0 && duration % 1.0 == 0.0) { "некорректная длительность" }
                val w = Workout(id = id, name = name, date = date.toString(), status = status, items = items,
                    accumulatedMs = duration.toLong(), notes = listOfNotNull(row.text("notes", "comment"),
                        "Импорт JSON: отсутствующие числа отображаются как 0; подходы без явной отметки выполнения не считаются выполненными. Активная запись переносится на паузе.").joinToString("\n"))
                BackupCodec.validateWorkout(w)
                workouts[id] = w; exercises.putAll(pending)
                items.forEach { item -> matches += "${item.name} → ${catalog.firstOrNull { it.id == item.exerciseId }?.name ?: "новое упражнение"}" }
            } catch(e: Exception) { skipped++; issues += "Запись ${index + 1}: ${e.message?.take(180) ?: "неподдерживаемые данные"}" }
        }
        require(workouts.isNotEmpty()) { "Нет поддерживаемых тренировок. ${issues.take(3).joinToString("; ")}" }
        return WorkoutImportPreview(Backup(exercises = exercises.values.toList(), workouts = workouts.values.toList()), skipped, issues, matches.toList())
    }
}
