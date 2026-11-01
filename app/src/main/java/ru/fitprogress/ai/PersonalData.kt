package ru.fitprogress.ai

import java.time.*
import kotlin.math.*

val goalKinds = linkedMapOf("sessions" to "Количество занятий", "distance" to "Километраж", "pace" to "Средний темп", "strength" to "Рабочий вес", "program" to "Завершить программу", "consistency" to "Регулярность по неделям")
data class SportGoal(val id: String = newId(), val title: String = "", val kind: String = "sessions", val target: Double = 3.0, val from: String = LocalDate.now().toString(), val until: String = LocalDate.now().plusDays(6).toString(), val exerciseId: String = "", val programId: String = "", val source: String = "all", val weeklySessions: Int = 3, val minReps: Int = 1, val archived: Boolean = false, val notes: String = "")
data class Wellbeing(val date: String = LocalDate.now().toString(), val energy: Int = 5, val fatigue: Int = 5, val sleep: Int = 5, val soreness: Int = 0, val recovery: Int = 5, val pain: Boolean = false, val comment: String = "")
data class GoalProgress(val current: Double, val target: Double, val fraction: Float, val achieved: Boolean, val explanation: String, val history: List<Pair<String, Double>>)
object PersonalValidation {
    fun goal(g: SportGoal) {
        require(g.id.isNotBlank() && g.title.isNotBlank() && g.title.length <= 200 && g.kind in goalKinds && g.notes.length <= 10000)
        require(g.target.isFinite() && g.target > 0 && g.target <= 1_000_000 && LocalDate.parse(g.from) <= LocalDate.parse(g.until))
        require(g.source in listOf("all", "gps", "manual") && g.weeklySessions in 1..14 && g.minReps in 1..100)
        if(g.kind == "pace") require(g.target in 1.0..60.0)
        if(g.kind == "strength") require(g.exerciseId.isNotBlank() && g.target <= 2000)
        if(g.kind == "program") require(g.programId.isNotBlank() && g.target == 100.0)
        if(g.kind in listOf("sessions", "consistency")) require(g.target % 1.0 == 0.0)
    }
    fun wellbeing(w: Wellbeing) { LocalDate.parse(w.date); require(w.energy in 1..10 && w.fatigue in 0..10 && w.sleep in 1..10 && w.soreness in 0..10 && w.recovery in 1..10 && w.comment.length <= 10000) }
}
fun Run.localDate(): LocalDate = Instant.ofEpochMilli(started).atZone(ZoneId.systemDefault()).toLocalDate()
fun weekOf(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)
object GoalTracker {
    fun calculate(g: SportGoal, workouts: List<Workout>, runs: List<Run>, today: LocalDate): GoalProgress {
        PersonalValidation.goal(g)
        val from = LocalDate.parse(g.from); val end = minOf(LocalDate.parse(g.until), today)
        if(today < from) return GoalProgress(0.0, g.target, 0f, false, "Период цели ещё не начался", emptyList())
        val ws = workouts.filter { it.status == "done" && !it.template && !it.recoveryDay && it.completedDate() in from..end && (g.kind !in listOf("sessions", "consistency") || g.source == "all") }
        val rs = runs.filter { it.status == "done" && !it.demo && it.localDate() in from..end && (g.source == "all" || it.recordingMode == g.source) }
        val history: List<Pair<String, Double>> = when(g.kind) {
            "sessions" -> (ws.map { it.completedDate().toString() to 1.0 } + rs.map { it.localDate().toString() to 1.0 }).sortedBy { it.first }
            "distance" -> rs.sortedBy { it.started }.map { it.localDate().toString() to it.distance / 1000 }
            "pace" -> rs.filter { it.distance >= 1000 }.sortedBy { it.started }.mapNotNull { r -> Gps.pace(r.distance, r.accumulatedMs)?.let { r.localDate().toString() to it } }
            "strength" -> ws.sortedBy { it.ended }.mapNotNull { w -> w.items.filter { it.exerciseId == g.exerciseId }.flatMap { it.sets }.filter { it.done && it.type != "warmup" && it.reps >= g.minReps }.maxOfOrNull { it.weight }?.let { w.completedDate().toString() to it } }
            "consistency" -> (ws.map { it.completedDate() } + rs.map { it.localDate() }).groupingBy { weekOf(it) }.eachCount().toSortedMap().map { it.key.toString() to if(it.value >= g.weeklySessions) 1.0 else 0.0 }
            else -> {
                val planned = workouts.filter { !it.template && !it.recoveryDay && it.program == g.programId && LocalDate.parse(it.date) in from..LocalDate.parse(g.until) }
                planned.filter { it.status == "done" && it.completedDate() in from..end }.sortedBy { it.ended }.map { it.completedDate().toString() to if(planned.isEmpty()) 0.0 else 100.0 / planned.size }
            }
        }
        val value = when(g.kind) { "pace" -> history.minOfOrNull { it.second } ?: 0.0; "strength" -> history.maxOfOrNull { it.second } ?: 0.0; else -> history.sumOf { it.second } }
        val reached = if(g.kind == "pace") value > 0 && value <= g.target else value + 0.00001 >= g.target
        val fraction = (if(g.kind == "pace") if(value > 0) g.target / value else 0.0 else value / g.target).coerceIn(0.0, 1.0).toFloat()
        val reason = when { today < from -> "Период цели ещё не начался"; reached -> "Цель подтверждается сохранёнными результатами"; history.isEmpty() -> "В выбранном периоде нет подходящих завершённых результатов"; today > LocalDate.parse(g.until) -> "Срок завершён: результат %.2f при цели %.2f. Причина недостижения не определяется автоматически.".format(value, g.target); else -> "Текущий результат %.2f; цель %.2f до %s".format(value, g.target, g.until) }
        return GoalProgress(value, g.target, fraction, reached, reason, history)
    }
}
data class ExerciseAnalysis(val id: String, val name: String, val volume: Double, val repetitions: Int, val maxWeight: Double, val estimated1Rm: Double?)
data class PeriodAnalysis(val workouts: Int, val runs: Int, val durationMs: Long, val volume: Double, val gpsKm: Double, val manualKm: Double, val pace: Double?, val activeDays: Int, val exercises: List<ExerciseAnalysis>, val muscleVolume: Map<String, Double>, val fatigue: Double?, val recovery: Double?)
object Analytics {
    fun summarize(workouts: List<Workout>, runs: List<Run>, diary: List<Wellbeing>, from: LocalDate, until: LocalDate, catalog: List<Exercise>): PeriodAnalysis {
        val ws = workouts.filter { it.status == "done" && !it.template && !it.recoveryDay && it.completedDate() in from..until }
        val rs = runs.filter { it.status == "done" && !it.demo && it.localDate() in from..until }
        val byExercise = ws.flatMap { it.items }.groupBy { it.exerciseId }.map { (id, items) ->
            val sets = items.flatMap { it.sets }.filter { it.done }
            ExerciseAnalysis(id, items.last().name, sets.sumOf { it.reps * it.weight }, sets.sumOf { it.reps }, sets.maxOfOrNull { it.weight } ?: 0.0,
                sets.filter { it.reps in 1..10 && it.weight > 0 && it.type != "warmup" }.maxOfOrNull { if(it.reps == 1) it.weight else it.weight * (1 + it.reps / 30.0) })
        }
        val groups = mutableMapOf<String, Double>()
        ws.flatMap { it.items }.forEach { i -> val key = i.muscleGroup ?: catalog.find { it.id == i.exerciseId }?.category ?: "Группа не сохранена"; groups[key] = (groups[key] ?: 0.0) + i.sets.filter { it.done }.sumOf { it.weight * it.reps } }
        val ds = diary.filter { LocalDate.parse(it.date) in from..until }
        val distance = rs.sumOf { it.distance }; val ms = rs.sumOf { it.accumulatedMs }
        return PeriodAnalysis(ws.size, rs.size, ws.sumOf { it.accumulatedMs } + ms, ws.sumOf { it.volume() }, rs.filter { it.recordingMode == "gps" }.sumOf { it.distance } / 1000, rs.filter { it.recordingMode == "manual" }.sumOf { it.distance } / 1000,
            Gps.pace(distance, ms), (ws.map { it.completedDate() } + rs.map { it.localDate() }).distinct().size, byExercise, groups.toSortedMap(), ds.takeIf { it.isNotEmpty() }?.map { it.fatigue }?.average(), ds.takeIf { it.isNotEmpty() }?.map { it.recovery }?.average())
    }
}

data class PersonalState(val loaded: Boolean = false, val goals: List<SportGoal> = emptyList(), val wellbeing: List<Wellbeing> = emptyList())

object ReminderPolicy {
    fun shouldNotify(enabled: Boolean, hour: Int, now: LocalDateTime, lastDate: String?, plannedCount: Int): Boolean {
        require(hour in 0..23 && plannedCount >= 0)
        return enabled && plannedCount > 0 && now.hour >= hour && lastDate != now.toLocalDate().toString()
    }
}
