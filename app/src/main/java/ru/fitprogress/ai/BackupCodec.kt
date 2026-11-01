package ru.fitprogress.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.time.LocalDate
import java.security.MessageDigest

data class Backup(val format: String = "FitProgressAI", val version: Int = 3, val exercises: List<Exercise> = emptyList(), val workouts: List<Workout> = emptyList(), val runs: List<Run> = emptyList(), val goals: List<SportGoal> = emptyList(), val wellbeing: List<Wellbeing> = emptyList(), val coachReceipts: List<CoachReceipt> = emptyList())
object BackupCodec {
    val gson = Gson()
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun encode(backup: Backup): String {
        val root = gson.toJsonTree(backup).asJsonObject
        root.addProperty("sha256", digest(gson.toJson(root)))
        return gson.toJson(root)
    }
    fun checkedExport(backup: Backup): String = encode(backup).also { decode(it) }
    fun decode(text: String): Backup {
        require(text.toByteArray().size <= 20_000_000) { "Резервная копия больше 20 МБ" }
        val root = JsonParser.parseString(text).asJsonObject
        val checksum = root.remove("sha256")?.asString ?: error("В копии нет контрольной суммы")
        require(checksum == digest(gson.toJson(root))) { "Контрольная сумма не совпадает: файл повреждён или изменён" }
        require(root.get("format")?.asString == "FitProgressAI" && root.get("version")?.asInt in listOf(1, 2, 3)) { "Неизвестный формат или версия" }
        listOf("exercises", "workouts", "runs").forEach { require(root.has(it) && root[it].isJsonArray) { "Отсутствует таблица $it" } }
        for(key in listOf("goals", "wellbeing", "coachReceipts")) {
            require((root.get("version").asInt < 3 && !root.has(key)) || root.has(key) && root[key].isJsonArray) { "Некорректная таблица $key в резервной копии" }
        }
        val b = gson.fromJson(text, Backup::class.java)
        require(b.exercises.size <= 10000 && b.workouts.size <= 20000 && b.runs.size <= 10000) { "Слишком много записей" }
        require((b.exercises.map { it.id }).distinct().size == b.exercises.size)
        require((b.workouts.map { it.id }).distinct().size == b.workouts.size)
        require((b.runs.map { it.id }).distinct().size == b.runs.size)
        require(b.goals.size <= 1000 && b.wellbeing.size <= 20000 && b.coachReceipts.size <= 1000)
        require(b.goals.map { it.id }.distinct().size == b.goals.size && b.wellbeing.map { it.date }.distinct().size == b.wellbeing.size && b.coachReceipts.map { it.week }.distinct().size == b.coachReceipts.size)
        b.goals.forEach(PersonalValidation::goal); b.wellbeing.forEach(PersonalValidation::wellbeing)
        b.coachReceipts.forEach { LocalDate.parse(it.week); require(it.option in listOf("repeat", "maintain", "progress", "reduce", "recovery") && it.appliedAt > 0 && it.workoutIds.size <= 1000) }
        b.exercises.forEach { validateExercise(it); require(!it.builtIn && !it.id.startsWith("builtin-")) }
        b.workouts.forEach(::validateWorkout)
        require(b.workouts.count { it.status in listOf("active", "paused") } <= 1) { "Несколько активных тренировок" }
        b.runs.forEach { r -> require(r.id.isNotBlank() && r.status in listOf("active", "paused", "done") && r.started > 0 && r.ended >= 0 && r.accumulatedMs >= 0 && r.activeSince >= 0 && r.distance.isFinite() && r.distance >= 0 && r.points.size <= 200000); r.points.forEach { require(Gps.valid(it) && (it.elapsedMs == null || it.elapsedMs >= 0)) }; require(r.recordingMode in listOf("gps", "manual") && r.movingMs >= 0 && r.lastAnnouncedKm >= 0 && r.lastAnnouncedKm <= r.distance / 1000); require((!r.demo && r.recordingMode != "manual") || r.status == "done"); require(r.recordingMode != "manual" || r.points.isEmpty()); require(r.points.zipWithNext().all { it.first.segment != it.second.segment || Gps.durationBetween(it.first, it.second) > 0 }) }
        require(b.runs.count { it.status != "done" } <= 1)
        return b
    }
    fun validateWorkout(w: Workout) {
            require(w.id.isNotBlank() && w.name.isNotBlank() && w.name.length <= 200)
            LocalDate.parse(w.date)
            require(w.status in listOf("planned", "active", "paused", "done"))
            require(w.notes.length <= 20000 && w.program.length <= 300)
            require(w.timerSetId.length <= 100 && w.timerUntil >= 0 && w.timerRemaining >= 0 && w.restRemaining >= 0)
            require(w.accumulatedMs >= 0 && w.started >= 0 && w.ended >= 0 && w.activeSince >= 0 && w.restUntil >= 0)
            require(w.effort in 1..10 && w.recovery in 1..10 && w.items.size <= 300)
            require(w.items.map { it.id }.distinct().size == w.items.size)
            w.plan?.let { require(w.template && w.plan.id == w.id); PlanBuilder.validate(it) }
            w.items.forEach { i -> require(i.id.isNotBlank() && i.name.isNotBlank() && i.exerciseId.isNotBlank() && (i.muscleGroup == null || i.muscleGroup.length <= 200)); require(i.rest in 0..3600 && i.sets.size <= 100 && i.notes.length <= 20000 && i.superset.length <= 100); require(i.sets.map { it.id }.distinct().size == i.sets.size); i.sets.forEach { s -> SessionEdits.validate(s) } }
    }
    fun validateExercise(e: Exercise) { require(e.id.isNotBlank() && e.name.isNotBlank() && e.name.length <= 200 && e.category in categories && e.sets in 1..100 && e.reps in 0..10000 && e.weight.isFinite() && e.weight in 0.0..2000.0 && e.seconds in 0..86400 && e.rest in 0..3600 && e.technique.length <= 20000 && e.muscles.length <= 2000 && e.equipment.length <= 2000 && e.imageUri.length <= 4000 && e.notes.length <= 20000) { "Проверьте название и параметры упражнения" } }
    private fun cell(s: Any?) = "\"" + s.toString().replace("\"", "\"\"").let { if(it.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$it" else it } + "\""
    fun csv(b: Backup): String = buildString {
        append("тип,дата,название,упражнение,подход,повторения,вес_кг,секунды,выполнен,дистанция_м,время_мс,тип_подхода,дистанция_подхода_м,отдых_сек,RPE,RIR,комментарий\r\n")
        b.workouts.filter { it.status == "done" }.forEach { w -> w.plan?.let { require(w.template && w.plan.id == w.id); PlanBuilder.validate(it) }
            w.items.forEach { i -> i.sets.forEachIndexed { index, s -> append(listOf("тренировка", w.date, w.name, i.name, index + 1, s.reps, s.weight, s.seconds, s.done, "", w.accumulatedMs, s.type, s.distanceMeters, s.restSeconds ?: i.rest, s.rpe ?: "", s.rir ?: "", s.comment).joinToString(",") { cell(it) }); append("\r\n") } } }
        b.runs.filter { it.status == "done" && !it.demo }.forEach { r -> append(listOf(if(r.recordingMode == "manual") "бег_вручную" else "бег_GPS", r.started, "Бег", "", "", "", "", "", true, r.distance, r.accumulatedMs, "", "", "", "", "", "").joinToString(",") { cell(it) }); append("\r\n") }
    }
}
