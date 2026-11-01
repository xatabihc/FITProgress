package ru.fitprogress.ai

import java.time.LocalDate
import java.util.UUID
import kotlin.math.*

fun newId(): String = UUID.randomUUID().toString()
val categories = listOf("Грудь", "Спина", "Ноги", "Плечи", "Руки", "Пресс", "Кардио", "Растяжка", "Функциональные", "Калистеника", "Другие")
data class Exercise(
    val id: String = newId(), val name: String = "", val category: String = "Другие",
    val technique: String = "", val muscles: String = "", val equipment: String = "Без оборудования",
    val imageUri: String = "", val sets: Int = 3, val reps: Int = 10, val weight: Double = 0.0,
    val seconds: Int = 0, val rest: Int = 60, val notes: String = "", val builtIn: Boolean = false
)
data class SetEntry(val id: String = newId(), val reps: Int = 10, val weight: Double = 0.0, val seconds: Int = 0, val done: Boolean = false, val type: String = "normal", val distanceMeters: Double = 0.0, val restSeconds: Int? = null, val rpe: Int? = null, val rir: Int? = null, val comment: String = "")
data class WorkoutItem(val id: String = newId(), val exerciseId: String, val name: String, val sets: List<SetEntry>, val rest: Int = 60, val superset: String = "", val notes: String = "", val muscleGroup: String? = null)
data class Workout(
    val id: String = newId(), val name: String = "Новая тренировка", val date: String = LocalDate.now().toString(),
    val items: List<WorkoutItem> = emptyList(), val notes: String = "", val status: String = "planned",
    val template: Boolean = false, val recoveryDay: Boolean = false, val program: String = "", val started: Long = 0, val ended: Long = 0,
    val accumulatedMs: Long = 0, val activeSince: Long = 0, val restUntil: Long = 0,
    val effort: Int = 5, val recovery: Int = 7, val pain: Boolean = false,
    val timerSetId: String = "", val timerUntil: Long = 0, val timerRemaining: Long = 0, val restRemaining: Long = 0,
    val timing: MonotonicTiming? = null, val plan: TrainingPlan? = null, val programDayId: String = ""
) {
    fun elapsed(now: Long) = (accumulatedMs + if (status == "active" && activeSince > 0) max(0, now - activeSince) else 0).coerceAtLeast(0)
    fun completedDate(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): LocalDate = if(status == "done" && ended > 0) java.time.Instant.ofEpochMilli(ended).atZone(zone).toLocalDate() else LocalDate.parse(date)
    fun volume() = items.sumOf { it.sets.filter { s -> s.done }.sumOf { s -> s.weight * s.reps } }
    fun completedSets() = items.sumOf { it.sets.count { s -> s.done } }
}
data class GeoPoint(val lat: Double, val lon: Double, val accuracy: Float, val time: Long, val segment: Int = 0, val elapsedMs: Long? = null, val boot: Long? = null)
data class Run(
    val id: String = newId(), val started: Long = System.currentTimeMillis(), val ended: Long = 0,
    val points: List<GeoPoint> = emptyList(), val distance: Double = 0.0, val accumulatedMs: Long = 0,
    val activeSince: Long = System.currentTimeMillis(), val status: String = "active", val segment: Int = 0,
    val demo: Boolean = false, val timing: MonotonicTiming? = null, val recordingMode: String = "gps", val movingMs: Long = 0, val lastAnnouncedKm: Int = 0
) {
    fun elapsed(now: Long) = (accumulatedMs + if (status == "active") max(0, now - activeSince) else 0).coerceAtLeast(0)
}
object Training {
    fun item(e: Exercise) = WorkoutItem(exerciseId = e.id, name = e.name, sets = List(e.sets) { SetEntry(reps = e.reps, weight = e.weight, seconds = e.seconds) }, rest = e.rest, muscleGroup = e.category)
    fun duplicate(w: Workout, date: LocalDate = LocalDate.parse(w.date), template: Boolean = false): Workout = Workout(
        name = w.name, date = date.toString(), items = w.items.map { it.copy(id = newId(), sets = it.sets.map { s -> s.copy(id = newId(), done = false) }) },
        notes = w.notes, template = template, recoveryDay = w.recoveryDay, program = w.program
    )
    fun copyWeek(all: List<Workout>, from: LocalDate, to: LocalDate): List<Workout> = all.filter {
        !it.template && LocalDate.parse(it.date) >= from && LocalDate.parse(it.date) < from.plusDays(7)
    }.map { duplicate(it, to.plusDays(java.time.temporal.ChronoUnit.DAYS.between(from, LocalDate.parse(it.date)))) }
    fun pause(w: Workout, now: Long) = w.copy(status = "paused", accumulatedMs = w.elapsed(now), activeSince = 0, timerRemaining = max(0, w.timerUntil - now), timerUntil = 0, restRemaining = max(0, w.restUntil - now), restUntil = 0)
    fun resume(w: Workout, now: Long) = w.copy(status = "active", started = if (w.started == 0L) now else w.started, activeSince = now, timerUntil = if(w.timerRemaining > 0) now + w.timerRemaining else w.timerUntil, timerRemaining = 0, restUntil = if(w.restRemaining > 0) now + w.restRemaining else w.restUntil, restRemaining = 0)
    fun finish(w: Workout, now: Long) = w.copy(status = "done", accumulatedMs = w.elapsed(now), activeSince = 0, restUntil = 0, ended = now, timerUntil = 0, timerRemaining = 0, restRemaining = 0)
}
object Gps {
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat); val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 6371000 * 2 * atan2(sqrt(h.coerceIn(0.0, 1.0)), sqrt((1 - h).coerceIn(0.0, 1.0)))
    }
    fun valid(p: GeoPoint) = p.lat.isFinite() && p.lon.isFinite() && p.lat in -90.0..90.0 && p.lon in -180.0..180.0 && p.accuracy.isFinite() && p.accuracy in 0f..35f && p.time > 0
    fun durationBetween(a: GeoPoint, b: GeoPoint): Long = if(a.elapsedMs != null && b.elapsedMs != null && a.boot != null && a.boot == b.boot) b.elapsedMs - a.elapsedMs else b.time - a.time
    fun age(p: GeoPoint, now: ClockStamp): Long = if(p.elapsedMs != null && p.boot == now.boot) now.elapsedMs - p.elapsedMs else now.wallMs - p.time
    fun appendAt(r: Run, point: GeoPoint, now: ClockStamp): Run {
        if(point.boot != null && point.boot != now.boot || age(point, now) !in -1000L..20000L) return r
        return append(r, point, point.time)
    }
    fun currentPaceAt(points: List<GeoPoint>, now: ClockStamp): Double? {
        val last = points.lastOrNull() ?: return null
        if(age(last, now) !in 0L..20000L) return null
        return currentPace(points, last.time)
    }
    fun accept(last: GeoPoint?, p: GeoPoint, now: Long): Boolean {
        if (!valid(p) || abs(now - p.time) > 20_000) return false
        if (last == null || last.segment != p.segment) return true
        val dt = durationBetween(last, p) / 1000.0
        if (dt <= 0 || dt > 120) return false
        val meters = distance(last, p)
        return meters >= max(3.0, (last.accuracy + p.accuracy) * 0.5) && meters / dt <= 12.0
    }
    fun pace(distance: Double, ms: Long): Double? = if (distance >= 10 && ms > 0) ms / 60.0 / distance else null // min/km
    fun speed(distance: Double, ms: Long): Double = if (ms > 0) distance * 3600 / ms else 0.0 // km/h
    fun currentPace(points: List<GeoPoint>, now: Long): Double? {
        val last = points.lastOrNull() ?: return null
        if(now - last.time > 20000) return null
        val recent = points.takeLastWhile { it.segment == last.segment && durationBetween(it, last) in 0L..30000L }
        if(recent.size < 2) return null
        return pace(recent.zipWithNext().sumOf { distance(it.first, it.second) }, durationBetween(recent.first(), last))
    }
    data class Split(val distanceMeters: Double, val minutesPerKm: Double)
    fun splits(points: List<GeoPoint>, splitMeters: Double = 100.0): List<Split> {
        require(splitMeters > 0)
        val result = mutableListOf<Split>(); var distanceInSplit = 0.0; var msInSplit = 0.0; var total = 0.0
        points.zipWithNext().filter { it.first.segment == it.second.segment }.forEach { (a, b) ->
            var meters = distance(a, b); var ms = durationBetween(a, b).toDouble()
            if(meters > 0 && ms > 0) {
                while(distanceInSplit + meters >= splitMeters) {
                    val take = splitMeters - distanceInSplit; val time = ms * take / meters
                    total += splitMeters; result += Split(total, (msInSplit + time) / 60.0 / splitMeters)
                    meters -= take; ms -= time; distanceInSplit = 0.0; msInSplit = 0.0
                }
                distanceInSplit += meters; msInSplit += ms
            }
        }
        return result
    }
    /** Best measured continuous segment, with interpolation at the target distance. */
    fun bestTime(points: List<GeoPoint>, targetMeters: Double): Long? {
        require(targetMeters > 0)
        var best: Double? = null
        points.groupBy { it.segment }.values.forEach { segment ->
            val distances = DoubleArray(segment.size); val times = DoubleArray(segment.size)
            for(i in 1 until segment.size) { distances[i] = distances[i - 1] + distance(segment[i - 1], segment[i]); times[i] = times[i - 1] + durationBetween(segment[i - 1], segment[i]).coerceAtLeast(0) }
            var end = 1
            for(start in segment.indices) {
                end = max(end, start + 1)
                while(end < segment.size && distances[end] - distances[start] < targetMeters) end++
                if(end >= segment.size) break
                val length = distances[end] - distances[end - 1]
                if(length <= 0) continue
                val fraction = (distances[start] + targetMeters - distances[end - 1]) / length
                val finishTime = times[end - 1] + (times[end] - times[end - 1]) * fraction
                val duration = finishTime - times[start]
                if(duration > 0 && (best == null || duration < best)) best = duration
            }
        }
        return best?.roundToLong()
    }
    fun append(r: Run, p: GeoPoint, now: Long): Run {
        if (r.status != "active") return r
        val last = r.points.lastOrNull()
        if(last != null && durationBetween(last, p) > 120000 && valid(p) && abs(now - p.time) <= 20000) return r.copy(segment = r.segment + 1, points = r.points + p.copy(segment = r.segment + 1))
        if(!accept(last, p, now)) return r
        val delta = if (last != null && last.segment == p.segment) distance(last, p) else 0.0
        val moving = if(last != null && last.segment == p.segment && durationBetween(last, p) in 1..20000 && delta / (durationBetween(last, p) / 1000.0) >= 0.5) durationBetween(last, p) else 0
        return r.copy(points = r.points + p, distance = r.distance + delta, movingMs = r.movingMs + moving)
    }
}
data class Recommendation(val title: String, val reason: String, val factor: Double?)
interface RecommendationEngine { fun suggest(workouts: List<Workout>, runs: List<Run>): List<Recommendation> }
class LocalRecommendations : RecommendationEngine {
    override fun suggest(workouts: List<Workout>, runs: List<Run>): List<Recommendation> {
        val training = workouts.filter { !it.recoveryDay }; val completed = training.filter { it.status == "done" }
        val danger = completed.any { it.pain || it.recovery <= 3 || it.effort >= 9 }
        val adherence = if (training.isEmpty()) 0.0 else completed.size.toDouble() / training.size
        val days = (completed.map { it.date } + runs.filter { it.status == "done" && !it.demo }.map { java.time.Instant.ofEpochMilli(it.started).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() }).distinct().size
        val result = mutableListOf(Recommendation("Сохранить нагрузку", if (completed.isEmpty()) "Недостаточно истории для прогрессии. Сначала оцените переносимость текущего плана." else "Выполнено ${completed.size} из ${training.size} занятий. Сохранение нагрузки даёт время закрепить технику.", 1.0))
        if (!danger && adherence >= 0.8 && completed.size >= 2 && days <= 5) result += Recommendation("Небольшая прогрессия", "Выполнено не менее 80% плана, восстановление выше 3/10, нет боли и выраженной усталости. Предлагается +5% веса; без веса — +1 повторение.", 1.05)
        result += Recommendation("Облегчённая неделя", if (danger) "Отмечены боль, низкое восстановление или высокая сложность. Снизьте нагрузку, при боли прекратите провоцирующее упражнение." else if (days > 5) "Менее двух дней отдыха. Предлагается снизить нагрузку на 20%." else "Вариант для восстановления: снизить рабочие веса и повторения на 20%.", 0.8)
        result += Recommendation("Составить вручную", "Вы самостоятельно выбираете занятия и восстановительные дни.", null)
        return result
    }
    fun preview(source: List<Workout>, from: LocalDate, to: LocalDate, factor: Double): List<Workout> = Training.copyWeek(source, from, to).map { w ->
        w.copy(items = w.items.map { item -> item.copy(sets = item.sets.map { s -> s.copy(weight = (s.weight * factor * 10).roundToInt() / 10.0, reps = if (s.weight == 0.0 && s.seconds == 0 && s.distanceMeters == 0.0 && factor != 1.0) max(1, if (factor > 1) s.reps + 1 else (s.reps * factor).roundToInt()) else s.reps) }) })
    }
}
data class Program(val id: String, val name: String, val direction: String, val level: String, val weeks: Int, val weekdays: List<Int>, val exerciseNames: List<String>, val reps: Int, val sets: Int, val workSeconds: Int = 0, val restSeconds: Int = 60, val advice: String = "Разминка 5–10 минут. Выполняйте без боли, контролируйте технику. Между занятиями — восстановление. Это общий план, а не медицинская рекомендация.")
object Catalog {
    val exercises: List<Exercise> = listOf(
        Triple("Отжимания", "Грудь", "Ладони под плечами. Сохраняйте прямую линию корпуса, опускайтесь подконтрольно, выдох при подъёме."),
        Triple("Жим гантелей лёжа", "Грудь", "Стопы на полу, лопатки сведены. Опустите гантели к груди, затем выжмите без рывка."),
        Triple("Тяга гантели в наклоне", "Спина", "Обопритесь рукой, держите спину нейтрально. Подтягивайте локоть к тазу, не вращая корпус."),
        Triple("Подтягивания", "Калистеника", "Начните с активного виса. Тяните локти вниз без раскачивания, плавно опускайтесь."),
        Triple("Приседания", "Ноги", "Стопы на ширине плеч, колени направлены вдоль носков. Сядьте до комфортной глубины без округления спины."),
        Triple("Выпады назад", "Ноги", "Сделайте шаг назад, опустите колено к полу. Передняя стопа полностью прижата к полу."),
        Triple("Жим гантелей стоя", "Плечи", "Напрягите корпус. Выжимайте гантели вверх без прогиба поясницы."),
        Triple("Сгибания рук", "Руки", "Прижмите локти к корпусу. Сгибайте руки без раскачивания и плавно опускайте вес."),
        Triple("Планка", "Пресс", "Локти под плечами. Напрягите живот и ягодицы, дышите свободно, не прогибайте поясницу."),
        Triple("Лёгкий бег", "Кардио", "Выберите разговорный темп. Короткий естественный шаг, расслабленные плечи. При дискомфорте переходите на ходьбу."),
        Triple("Ходьба", "Кардио", "Двигайтесь в комфортном темпе, держите корпус прямо. Используйте как восстановление между беговыми интервалами."),
        Triple("Растяжка задней поверхности бедра", "Растяжка", "Наклоняйтесь от тазобедренного сустава с нейтральной спиной. Удерживайте мягкое натяжение без боли."),
        Triple("Берпи без прыжка", "Функциональные", "Присядьте, шагните в упор, вернитесь и встаньте. Сохраняйте контроль корпуса."),
        Triple("Велосипед", "Другие", "Настройте высоту седла. Поддерживайте ровное вращение педалей и лёгкое дыхание.")
    ).mapIndexed { i, t -> Exercise(id = "builtin-$i", name = t.first, category = t.second, technique = t.third, muscles = when(t.second) { "Грудь" -> "Грудные, трицепс"; "Спина", "Калистеника" -> "Широчайшие, бицепс"; "Ноги" -> "Квадрицепсы, ягодичные"; else -> t.second }, equipment = if (t.first.contains("гантел") || t.first == "Сгибания рук") "Гантели" else if(t.first == "Подтягивания") "Турник" else if(t.first == "Велосипед") "Велосипед" else "Без оборудования", seconds = if(t.second in listOf("Кардио", "Растяжка", "Пресс", "Другие")) 30 else 0, builtIn = true) }
    private val directionExercises = linkedMapOf(
        "Тренажёрный зал" to listOf("Приседания", "Жим гантелей лёжа", "Тяга гантели в наклоне"),
        "Домашние тренировки" to listOf("Приседания", "Отжимания", "Планка"),
        "Бег" to listOf("Ходьба", "Лёгкий бег", "Ходьба"),
        "Калистеника" to listOf("Подтягивания", "Отжимания", "Планка"),
        "ОФП" to listOf("Приседания", "Отжимания", "Ходьба"),
        "Функциональные тренировки" to listOf("Берпи без прыжка", "Выпады назад", "Планка"),
        "Растяжка" to listOf("Растяжка задней поверхности бедра", "Ходьба"),
        "Велоспорт" to listOf("Велосипед", "Растяжка задней поверхности бедра"),
        "Смешанные тренировки" to listOf("Приседания", "Отжимания", "Лёгкий бег")
    )
    val programs = directionExercises.flatMap { (direction, names) -> listOf("Новичок", "Любитель", "Продвинутый").mapIndexed { index, level ->
        Program("$direction-$index", "$direction · $level", direction, level, 4 + index * 2, if(index == 0) listOf(1, 4) else listOf(1, 3, 5), names, 8 + index * 2, 2 + index, if(direction == "Бег") 60 + index * 60 else if(direction == "Велоспорт") 900 + index * 600 else 0, 60 + index * 15)
    } } + listOf("Первые шаги в беге", "Чередование ходьбы и бега", "Подготовка к непрерывному бегу", "Улучшение результата на 3 км", "Подготовка к 5 км", "Подготовка к 10 км", "Развитие выносливости", "Интервальные тренировки").mapIndexed { i, name ->
        Program("running-$i", name, "Бег", if(i < 3) "Новичок" else "Любитель", if(i < 3) 6 else 8, listOf(2, 4, 6), listOf("Ходьба", "Лёгкий бег", "Ходьба"), 1, if(i < 3) 6 else 4, if(i < 3) 60 + i * 30 else 180 + (i - 3) * 120, if(i < 3) 90 else 120,
            "Разминка: ходьба 5 минут. Повторяйте бег и ходьбу в разговорном темпе. Заминка: ходьба 5 минут. Между занятиями — день восстановления. Увеличивайте длительность только при хорошем самочувствии; целевая дистанция не гарантируется этим общим планом.")
    }
    fun schedule(p: Program, start: LocalDate): List<Workout> = (0 until p.weeks * 7).mapNotNull { day ->
        val date = start.plusDays(day.toLong())
        if (date.dayOfWeek.value !in p.weekdays) null else {
            val week = day / 7
            val items = if(p.direction == "Бег") {
                val walk = exercises.first { it.name == "Ходьба" }; val run = exercises.first { it.name == "Лёгкий бег" }
                listOf(Training.item(walk).copy(name = "Разминка · ходьба", sets = listOf(SetEntry(reps = 1, seconds = 300)), rest = 0)) +
                    (0 until p.sets).flatMap { listOf(Training.item(run).copy(sets = listOf(SetEntry(reps = 1, seconds = p.workSeconds + week * 10)), rest = 0), Training.item(walk).copy(sets = listOf(SetEntry(reps = 1, seconds = p.restSeconds)), rest = 0)) } +
                    listOf(Training.item(walk).copy(name = "Заминка · ходьба", sets = listOf(SetEntry(reps = 1, seconds = 300)), rest = 0))
            } else p.exerciseNames.mapNotNull { name -> exercises.find { it.name == name } }.map { e -> Training.item(e).copy(sets = List(p.sets) { SetEntry(reps = p.reps, seconds = if(e.seconds > 0) max(e.seconds, p.workSeconds) else 0) }, rest = p.restSeconds) }
            Workout(name = "${p.name} · неделя ${week + 1}", date = date.toString(), items = items, program = p.id, notes = p.advice)
        }
    }
}
fun formatTime(ms: Long): String { val s = max(0, ms / 1000); return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) }
fun formatPace(value: Double?): String = if(value == null || !value.isFinite()) "—" else { val sec = (value * 60).roundToInt(); "%d:%02d /км".format(sec / 60, sec % 60) }
