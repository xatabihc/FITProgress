package ru.fitprogress.ai

import java.time.LocalDate
import kotlin.math.*

data class CoachOption(val id: String, val title: String, val reason: String, val enabled: Boolean, val metrics: String)
data class CoachReceipt(val week: String = "", val option: String = "", val appliedAt: Long = 0, val workoutIds: List<String> = emptyList())
data class CoachPreview(val option: CoachOption, val from: String, val until: String, val workouts: List<Workout>, val differences: List<String>)
class AdaptiveCoach {
    fun options(all: List<Workout>, runs: List<Run>, diary: List<Wellbeing>, today: LocalDate): List<CoachOption> {
        val week = weekOf(today).minusWeeks(1); val previous = week.minusWeeks(1)
        val planned = all.filter { !it.template && !it.recoveryDay && LocalDate.parse(it.date) in week..week.plusDays(6) }
        val actual = all.filter { it.status == "done" && !it.template && !it.recoveryDay && it.completedDate() in week..week.plusDays(6) }
        val older = all.filter { it.status == "done" && !it.template && !it.recoveryDay && it.completedDate() in previous..previous.plusDays(6) }
        val rs = runs.filter { it.status == "done" && !it.demo }
        val km = rs.filter { it.localDate() in week..week.plusDays(6) }.sumOf { it.distance } / 1000
        val oldKm = rs.filter { it.localDate() in previous..previous.plusDays(6) }.sumOf { it.distance } / 1000
        val volume = actual.sumOf { it.volume() }; val oldVolume = older.sumOf { it.volume() }
        val entries = diary.filter { LocalDate.parse(it.date) in week..today }
        val recent = all.filter { it.status == "done" && it.completedDate() in week..today }
        val pain = recent.any { it.pain } || entries.any { it.pain }
        val poor = recent.any { it.effort >= 9 || it.recovery <= 3 } || entries.any { it.fatigue >= 8 || it.recovery <= 3 || it.energy <= 3 || it.sleep <= 3 || it.soreness >= 8 }
        val adherence = if(planned.isEmpty()) 0.0 else planned.count { it.status == "done" }.toDouble() / planned.size
        val activeDays = (actual.map { it.completedDate() } + rs.filter { it.localDate() in week..week.plusDays(6) }.map { it.localDate() }).distinct().size
        val spike = (oldVolume > 0 && volume > oldVolume * 1.15) || (oldKm > 0 && km > oldKm * 1.15)
        val enough = actual.map { it.completedDate() }.distinct().size >= 2 && adherence >= .8
        val progress = !pain && !poor && !spike && activeDays <= 5 && enough && entries.isNotEmpty() && older.isNotEmpty()
        val evidence = "Выполнение %.0f%%; объём %.0f → %.0f кг; бег %.2f → %.2f км; активных дней %d; записей самочувствия %d.".format(adherence * 100, oldVolume, volume, oldKm, km, activeDays, entries.size)
        val barrier = when { pain -> "Отмечена боль: прогрессия заблокирована."; poor -> "Усталость, сон или восстановление требуют снижения нагрузки."; spike -> "Рост объёма или километража более 15%: дополнительное увеличение заблокировано."; activeDays > 5 -> "Меньше двух дней без занятий: сначала восстановление."; !enough -> "Нужно ≥80% выполнения плана и занятия минимум в два разных дня."; older.isEmpty() -> "Нет тренировочной базы предыдущей недели: после перерыва сначала закрепите регулярность."; entries.isEmpty() -> "Нет дневника восстановления: данных для автоматического предложения прогрессии недостаточно."; else -> "Все проверяемые условия выполнены." }
        val hasPlan = planned.isNotEmpty()
        return listOf(
            CoachOption("repeat", "Повторить прошлую неделю", "Копируются только фактически выполненные подходы завершённых тренировок.", actual.isNotEmpty(), evidence),
            CoachOption("maintain", "Сохранить нагрузку", "Копируется исходный недельный план без изменения нагрузки. $barrier", hasPlan, evidence),
            CoachOption("progress", "Осторожная прогрессия", "$barrier Для обычных/рабочих силовых подходов: до +2,5% веса (округление вниз до 0,1 кг) или +1 повторение без веса; временные, дистанционные и специальные подходы не повышаются.", progress && hasPlan, evidence),
            CoachOption("reduce", "Уменьшить объём", "Примерно на 20% меньше подходов с округлением вниз, минимум один рабочий; разминочные сохраняются. Вес и техника остаются прежними. $barrier", hasPlan, evidence),
            CoachOption("recovery", "Добавить восстановление", "Последнее силовое занятие заменяется днём отдыха. Остальные нагрузки сохраняются. $barrier", hasPlan, evidence),
            CoachOption("manual", "Настроить самостоятельно", "Выберите занятия вручную, учитывая самочувствие. Алгоритм не является диагнозом.", true, evidence)
        )
    }
    fun preview(option: CoachOption, all: List<Workout>, today: LocalDate): CoachPreview {
        require(option.enabled && option.id != "manual")
        val week = weekOf(today).minusWeeks(1); val target = weekOf(today).plusWeeks(1)
        fun sourceDate(w: Workout) = if(option.id == "repeat") w.completedDate() else LocalDate.parse(w.date)
        val source = all.filter { !it.template && sourceDate(it) in week..week.plusDays(6) && (option.id != "repeat" || it.status == "done") }.sortedWith(compareBy<Workout> { sourceDate(it) }.thenBy { it.id })
        var next = source.map { original ->
            val w = Training.duplicate(original, target.plusDays(java.time.temporal.ChronoUnit.DAYS.between(week, sourceDate(original))))
            w.copy(program = "", programDayId = "", items = w.items.mapIndexed { index, i ->
                val src = original.items[index]
                val sets = if(option.id == "repeat") src.sets.filter { it.done }.map { it.copy(id = newId(), done = false) } else i.sets
                i.copy(sets = when(option.id) {
                    "reduce" -> sets.filter { it.type == "warmup" || it.id in sets.filter { set -> set.type != "warmup" }.take(floor(sets.count { set -> set.type != "warmup" } * .8).toInt().coerceAtLeast(1)).map { set -> set.id }.toSet() }
                    "progress" -> sets.map { s -> if(s.type !in listOf("normal", "working") || s.seconds > 0 || s.distanceMeters > 0) s else if(s.weight > 0) s.copy(weight = max(s.weight, floor(s.weight * 1.025 * 10 + 1e-8) / 10.0).coerceAtMost(2000.0)) else s.copy(reps = (s.reps + 1).coerceAtMost(10000)) }
                    else -> sets
                })
            }.filter { it.sets.isNotEmpty() })
        }.filter { it.recoveryDay || it.items.isNotEmpty() }
        if(option.id == "recovery") {
            val last = next.lastOrNull { !it.recoveryDay }
            if(last != null) next = next.map { if(it.id == last.id) Workout(name = "Восстановление · Adaptive Coach", date = it.date, recoveryDay = true, notes = option.reason) else it }
        }
        val differences = next.flatMap { w ->
            val oldDate = LocalDate.parse(w.date).minusWeeks(2).toString()
            val originals = source.filter { sourceDate(it).toString() == oldDate }
            fun describe(items: List<WorkoutItem>) = items.joinToString("; ") { i -> "${i.name}: ${i.sets.joinToString { "${it.reps}×${it.weight} кг, ${it.seconds} сек, ${it.distanceMeters} м" }}" }
            listOf("${w.date}: ${w.name}", "Было: ${describe(originals.flatMap { it.items })}", if(w.recoveryDay) "Станет: восстановление" else "Станет: ${describe(w.items)}")
        }
        return CoachPreview(option, target.toString(), target.plusDays(6).toString(), next, differences)
    }
}
