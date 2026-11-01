package ru.fitprogress.ai

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Stored as a template Workout snapshot in the existing Room table; schema v1 is preserved. */
data class PlanDay(val id: String = newId(), val offset: Int = 0, val name: String = "Тренировка", val items: List<WorkoutItem> = emptyList(), val recovery: Boolean = false, val notes: String = "", val block: String = "", val deload: Boolean = false)
data class TrainingPlan(val id: String = newId(), val name: String = "Моя программа", val weeks: Int = 4, val startDate: String = LocalDate.now().toString(), val days: List<PlanDay> = emptyList(), val notes: String = "")
data class PlanDiff(val inserts: List<Workout>, val updates: List<Workout>, val deleteIds: List<String>)
object PlanBuilder {
    fun validate(p: TrainingPlan) {
        require(p.id.isNotBlank() && p.name.isNotBlank() && p.name.length <= 200 && p.weeks in 1..52 && p.notes.length <= 20000)
        LocalDate.parse(p.startDate); require(p.days.size <= 364 && p.days.map { it.id }.distinct().size == p.days.size)
        p.days.forEach { day ->
            require(day.id.isNotBlank() && day.offset in 0 until p.weeks * 7 && day.name.isNotBlank() && day.name.length <= 200 && day.notes.length <= 20000 && day.block.length <= 200)
            require(day.items.size <= 300 && day.items.map { it.id }.distinct().size == day.items.size)
            day.items.forEach { i -> require(i.name.isNotBlank() && i.exerciseId.isNotBlank() && i.rest in 0..3600 && i.sets.size in 1..100); i.sets.forEach(SessionEdits::validate) }
            require(day.recovery || day.items.isNotEmpty()) { "Добавьте упражнения или отметьте день восстановления" }
            require(!day.recovery || day.items.isEmpty()) { "В дне отдыха не должно быть подходов" }
        }
    }
    fun definition(p: TrainingPlan) = Workout(id = p.id, name = p.name, date = p.startDate, template = true, program = "custom-definition", plan = p)
    fun schedule(p: TrainingPlan): List<Workout> {
        validate(p); val start = LocalDate.parse(p.startDate)
        return p.days.sortedBy { it.offset }.map { day -> Workout(id = "${p.id}:${day.id}", name = day.name, date = start.plusDays(day.offset.toLong()).toString(), program = p.id, programDayId = day.id,
            recoveryDay = day.recovery, notes = listOf(p.notes, day.block, if(day.deload) "Разгрузочная неделя" else "", day.notes).filter { it.isNotBlank() }.joinToString("\n"),
            items = day.items.map { it.copy(id = newId(), sets = it.sets.map { s -> s.copy(id = newId(), done = false) }) }) }
    }
    fun copyWeek(p: TrainingPlan, from: Int, to: Int): TrainingPlan {
        require(from in 0 until p.weeks && to in 0 until p.weeks && from != to)
        val copied = p.days.filter { it.offset / 7 == from }.map { it.copy(id = newId(), offset = to * 7 + it.offset % 7, items = it.items.map { i -> i.copy(id = newId(), sets = i.sets.map { s -> s.copy(id = newId(), done = false) }) }) }
        return p.copy(days = p.days.filterNot { it.offset / 7 == to } + copied)
    }
    fun shift(p: TrainingPlan, days: Long): TrainingPlan = p.copy(startDate = LocalDate.parse(p.startDate).plusDays(days).toString())
    fun deload(p: TrainingPlan, week: Int): TrainingPlan = p.copy(days = p.days.map { d -> if(d.offset / 7 != week || d.deload) d else d.copy(deload = true, items = d.items.map { i -> i.copy(sets = i.sets.take((i.sets.size * 0.7).toInt().coerceAtLeast(1))) }) })
    fun fromWorkouts(name: String, workouts: List<Workout>, start: LocalDate, weeks: Int): TrainingPlan = TrainingPlan(name = name, weeks = weeks, startDate = start.toString(), days = workouts.filter { !it.template }.map { w ->
        PlanDay(offset = ChronoUnit.DAYS.between(start, LocalDate.parse(w.date)).toInt(), name = w.name, items = w.items.map { i -> i.copy(sets = i.sets.map { it.copy(done = false) }) }, recovery = w.recoveryDay, notes = w.notes)
    }).also(::validate)
    /** Only future planned sessions are replaceable. Completed, active and past rows are immutable. */
    fun preview(p: TrainingPlan, current: List<Workout>, today: LocalDate): PlanDiff {
        val rows = current.filter { it.program == p.id && !it.template }; val replaceable = rows.filter { it.status == "planned" && LocalDate.parse(it.date) >= today }
        val protectedDays = rows.filter { it !in replaceable }.map { it.programDayId.ifBlank { it.id.removePrefix("${p.id}:") } }.toSet()
        val next = schedule(p).filter { LocalDate.parse(it.date) >= today && it.programDayId !in protectedDays }
        val existing = replaceable.associateBy { it.programDayId.ifBlank { it.id.removePrefix("${p.id}:") } }
        val inserts = next.filter { it.programDayId !in existing }
        require(inserts.none { new -> current.any { it.id == new.id } }) { "Конфликт идентификатора занятия. Существующая запись сохранена." }
        val updates = next.filter { it.programDayId in existing }.map { it.copy(id = existing.getValue(it.programDayId).id) }
        val nextDays = next.map { it.programDayId }.toSet()
        return PlanDiff(inserts, updates, replaceable.filter { it.programDayId.ifBlank { it.id.removePrefix("${p.id}:") } !in nextDays }.map { it.id })
    }
}
