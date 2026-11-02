package ru.fitprogress.ai

import kotlin.math.max

/** Wall time labels events; elapsed time measures durations, including device sleep. */
data class ClockStamp(val wallMs: Long, val elapsedMs: Long, val boot: Long)
data class MonotonicTiming(val boot: Long = 0, val anchorMs: Long = 0, val restDeadlineMs: Long = 0, val stageDeadlineMs: Long = 0)
object SessionTimers {
    fun elapsed(w: Workout, now: ClockStamp): Long = w.accumulatedMs + if(w.status == "active" && w.timing?.boot == now.boot) max(0, now.elapsedMs - w.timing.anchorMs) else 0
    fun rest(w: Workout, now: ClockStamp): Long = if(w.status == "paused") w.restRemaining else if(w.timing?.boot == now.boot) max(0, w.timing.restDeadlineMs - now.elapsedMs) else 0
    fun stage(w: Workout, now: ClockStamp): Long = if(w.status == "paused") w.timerRemaining else if(w.timing?.boot == now.boot) max(0, w.timing.stageDeadlineMs - now.elapsedMs) else 0
    fun resume(w: Workout, now: ClockStamp): Workout {
        require(w.status in listOf("planned", "paused", "active"))
        if(w.status == "active") return w
        return w.copy(status = "active", started = w.started.takeIf { it > 0 } ?: now.wallMs, activeSince = now.wallMs,
            timing = MonotonicTiming(now.boot, now.elapsedMs, if(w.restRemaining > 0) now.elapsedMs + w.restRemaining else 0, if(w.timerRemaining > 0) now.elapsedMs + w.timerRemaining else 0),
            restUntil = 0, timerUntil = 0)
    }
    fun checkpoint(w: Workout, now: ClockStamp): Workout = if(w.status != "active" || w.timing?.boot != now.boot) w else w.copy(
        accumulatedMs = elapsed(w, now), activeSince = now.wallMs, timing = w.timing.copy(anchorMs = now.elapsedMs),
        restRemaining = rest(w, now), timerRemaining = stage(w, now))
    fun pause(w: Workout, now: ClockStamp): Workout = if(w.status != "active") w else w.copy(status = "paused", accumulatedMs = elapsed(w, now), activeSince = 0,
        restRemaining = rest(w, now), timerRemaining = stage(w, now), timing = null, restUntil = 0, timerUntil = 0)
    fun finish(w: Workout, now: ClockStamp): Workout {
        require(w.status in listOf("active", "paused")) { "Тренировка ещё не начата" }
        return pause(w, now).copy(status = "done", ended = now.wallMs, timerSetId = "", timerRemaining = 0, restRemaining = 0)
    }
    fun startRest(w: Workout, seconds: Int, now: ClockStamp): Workout {
        require(w.status == "active" && seconds in 0..3600)
        val t = requireNotNull(w.timing) { "Продолжите тренировку для восстановления часов" }; require(t.boot == now.boot)
        return w.copy(timing = t.copy(restDeadlineMs = if(seconds > 0) now.elapsedMs + seconds * 1000L else 0), restRemaining = seconds * 1000L, restUntil = 0)
    }
    fun startStage(w: Workout, set: SetEntry, now: ClockStamp): Workout {
        require(w.status == "active" && set.seconds in 1..86400)
        val t = requireNotNull(w.timing); require(t.boot == now.boot)
        return w.copy(timerSetId = set.id, timerRemaining = set.seconds * 1000L, timing = t.copy(stageDeadlineMs = now.elapsedMs + set.seconds * 1000L), timerUntil = 0)
    }
    /** A changed boot invalidates elapsed anchors. Preserve the last durable checkpoint. */
    fun recover(w: Workout, now: ClockStamp): Workout = if(w.status == "active" && (now.boot < 0 || w.timing == null || w.timing.boot != now.boot || now.elapsedMs < w.timing.anchorMs))
        w.copy(status = "paused", activeSince = 0, restUntil = 0, timerUntil = 0, timing = null) else w
    fun forImport(w: Workout): Workout = if(w.status in listOf("active", "paused")) w.copy(status = "paused", activeSince = 0, timing = null, restUntil = 0, timerUntil = 0) else w.copy(timing = null)
}
val setTypes = linkedMapOf("normal" to "Обычный", "warmup" to "Разминочный", "working" to "Рабочий", "failure" to "До технического отказа", "drop" to "Дроп-сет", "superset" to "Суперсет", "giant" to "Гигантский", "circuit" to "Круговой", "interval" to "Интервальный", "timed" to "По времени", "distance" to "На дистанцию")
object SessionEdits {
    fun validate(s: SetEntry) {
        require(s.id.isNotBlank() && s.reps in 0..10000 && s.weight.isFinite() && s.weight in 0.0..2000.0 && s.seconds in 0..86400)
        require(s.type in setTypes && s.distanceMeters.isFinite() && s.distanceMeters in 0.0..1_000_000.0)
        require(s.restSeconds == null || s.restSeconds in 0..3600)
        require(s.rpe == null || s.rpe in 1..10); require(s.rir == null || s.rir in 0..10); require(s.comment.length <= 2000)
    }
    fun cancelMissingStage(w: Workout): Workout {
        if(w.timerSetId.isBlank() || w.items.any { i -> i.sets.any { it.id == w.timerSetId && !it.done } }) return w
        return w.copy(timerSetId = "", timerUntil = 0, timerRemaining = 0, timing = w.timing?.copy(stageDeadlineMs = 0))
    }
    fun editable(w: Workout) = w.status in listOf("planned", "active", "paused")
    fun addSet(w: Workout, itemId: String): Workout {
        require(editable(w)); val item = requireNotNull(w.items.find { it.id == itemId }); require(item.sets.size < 100)
        val next = (item.sets.lastOrNull() ?: SetEntry()).copy(id = newId(), done = false)
        return w.copy(items = w.items.map { if(it.id == itemId) it.copy(sets = it.sets + next) else it })
    }
    fun move(w: Workout, itemId: String, delta: Int): Workout {
        require(editable(w) && delta in listOf(-1, 1)); val list = w.items.toMutableList(); val from = list.indexOfFirst { it.id == itemId }; require(from >= 0)
        val to = (from + delta).coerceIn(0, list.lastIndex); java.util.Collections.swap(list, from, to); return w.copy(items = list)
    }
    fun replace(w: Workout, itemId: String, e: Exercise): Workout {
        require(editable(w)); val old = requireNotNull(w.items.find { it.id == itemId }); require(old.sets.none { it.done }) { "Выполненные подходы нельзя заменить. Добавьте новое упражнение." }
        return w.copy(items = w.items.map { if(it.id == itemId) Training.item(e).copy(id = old.id) else it })
    }
    fun lastResult(w: Workout, itemId: String, history: List<Workout>): Workout {
        require(editable(w)); val item = requireNotNull(w.items.find { it.id == itemId })
        val last = history.filter { it.status == "done" && it.id != w.id }.sortedByDescending { it.ended }.flatMap { it.items }.firstOrNull { it.exerciseId == item.exerciseId && it.sets.any { s -> s.done } } ?: error("Предыдущего результата нет")
        var index = 0; val actual = last.sets.filter { it.done }
        return w.copy(items = w.items.map { i -> if(i.id != itemId) i else i.copy(sets = i.sets.map { s -> if(s.done) s else actual.getOrNull(index++)?.copy(id = s.id, done = false) ?: s }) })
    }
    fun complete(w: Workout, itemId: String, result: SetEntry, now: ClockStamp): Workout {
        require(w.status == "active"); validate(result)
        val item = requireNotNull(w.items.find { it.id == itemId }); val old = requireNotNull(item.sets.find { it.id == result.id })
        if(old.done) return w // Retries/double taps never restart rest or overwrite a result.
        val updated = w.copy(items = w.items.map { i -> if(i.id != itemId) i else i.copy(sets = i.sets.map { if(it.id == result.id) result.copy(done = true) else it }) }, timerSetId = "", timerRemaining = 0, timing = w.timing?.copy(stageDeadlineMs = 0))
        return SessionTimers.startRest(updated, result.restSeconds ?: item.rest, now)
    }
}

object RunTimers {
    fun elapsed(r: Run, now: ClockStamp): Long = r.accumulatedMs + if(r.status == "active" && r.timing?.boot == now.boot) max(0, now.elapsedMs - r.timing.anchorMs) else 0
    fun resume(r: Run, now: ClockStamp): Run {
        require(r.status in listOf("active", "paused")) { "Завершённую пробежку нельзя возобновить" }
        return if(r.status == "active" && r.timing?.boot == now.boot) r else r.copy(status = "active", activeSince = now.wallMs, timing = MonotonicTiming(now.boot, now.elapsedMs), segment = r.segment + 1)
    }
    fun checkpoint(r: Run, now: ClockStamp): Run = if(r.status != "active") r else r.copy(accumulatedMs = elapsed(r, now), activeSince = now.wallMs, timing = MonotonicTiming(now.boot, now.elapsedMs))
    fun pause(r: Run, now: ClockStamp): Run = if(r.status != "active") r else r.copy(status = "paused", accumulatedMs = elapsed(r, now), activeSince = 0, timing = null, segment = r.segment + 1)
    fun finish(r: Run, now: ClockStamp): Run = if(r.status == "done") r else pause(r, now).copy(status = "done", ended = now.wallMs, activeSince = 0, timing = null)
}

object ManualRuns {
    fun create(distanceMeters: Double, durationMs: Long, ended: Long, now: Long): Run {
        require(distanceMeters.isFinite() && distanceMeters in 1.0..1_000_000.0) { "Дистанция: от 1 м до 1000 км" }
        require(durationMs in 1000..604800000 && ended <= now && ended > durationMs) { "Проверьте время и дату" }
        return Run(started = ended - durationMs, ended = ended, distance = distanceMeters, accumulatedMs = durationMs, activeSince = 0, status = "done", recordingMode = "manual")
    }
}
