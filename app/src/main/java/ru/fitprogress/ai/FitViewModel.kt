package ru.fitprogress.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate

class FitViewModel(app: Application) : AndroidViewModel(app) {
    val repo = (app as FitApp).repository
    val exercises = repo.exercises.stateIn(viewModelScope, SharingStarted.Eagerly, Catalog.exercises)
    val workouts = repo.workouts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val runs = repo.runs.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val profile = repo.profile.stateIn(viewModelScope, SharingStarted.Eagerly, Profile())
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 20)
    val busy = MutableStateFlow(false)
    fun action(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch(e: Exception) { if(e is CancellationException) throw e; messages.emit(e.message ?: "Не удалось сохранить данные") } } }
    fun exclusive(block: suspend () -> Unit) { if(busy.value) return; busy.value = true; viewModelScope.launch { try { block() } catch(e: Exception) { if(e is CancellationException) throw e; messages.emit(e.message ?: "Ошибка операции") } finally { busy.value = false } } }
    fun notify(text: String) { messages.tryEmit(text) }
    fun create(onCreated: (String) -> Unit) = exclusive { val w = Workout(); repo.save(w); onCreated(w.id) }
    fun update(id: String, block: (Workout) -> Workout) = action { repo.updateWorkout(id, block) }
    fun addExercise(workout: Workout, exercise: Exercise) = update(workout.id) { it.copy(items = it.items + Training.item(exercise).copy(rest = if(exercise.rest == 60) profile.value.rest else exercise.rest)) }
    fun start(w: Workout) = update(w.id) { require(it.items.isNotEmpty()) { "Добавьте упражнения" }; Training.resume(it, System.currentTimeMillis()) }
    fun pause(w: Workout) = update(w.id) { Training.pause(it, System.currentTimeMillis()) }
    fun completeSet(w: Workout, itemId: String, setId: String, entry: SetEntry) = update(w.id) { current ->
        require(current.status == "active") { "Продолжите тренировку" }
        current.copy(items = current.items.map { i -> if(i.id != itemId) i else i.copy(sets = i.sets.map { s -> if(s.id == setId) entry.copy(done = true) else s }) }, timerSetId = "", timerUntil = 0, timerRemaining = 0, restUntil = System.currentTimeMillis() + (current.items.find { it.id == itemId }?.rest ?: 0) * 1000L)
    }
    fun finish(w: Workout, effort: Int, recovery: Int, pain: Boolean) = update(w.id) { Training.finish(it, System.currentTimeMillis()).copy(effort = effort, recovery = recovery, pain = pain) }
    fun duplicate(w: Workout, template: Boolean = false, onCreated: (String) -> Unit = {}) = exclusive { val copy = Training.duplicate(w, template = template).copy(name = w.name + if(template) " · шаблон" else " · копия"); repo.save(copy); onCreated(copy.id) }
    fun demo() = exclusive {
        val now = System.currentTimeMillis(); val pts = (0..40).map { i -> GeoPoint(56.83 + i * 0.00005, 60.60 + kotlin.math.sin(i / 6.0) * 0.0002, 5f, now - (40 - i) * 5000) }
        repo.saveRun(Run(started = now - 200000, ended = now, points = pts, distance = pts.zipWithNext().sumOf { Gps.distance(it.first, it.second) }, accumulatedMs = 200000, activeSince = 0, status = "done", demo = true))
        messages.emit("Демо-маршрут создан. Он исключён из статистики.")
    }
    fun copyWeek(from: LocalDate, to: LocalDate) = exclusive { val copies = Training.copyWeek(workouts.value, from, to); require(copies.isNotEmpty()) { "В исходной неделе нет занятий" }; repo.saveAll(copies); messages.emit("Скопировано занятий: ${copies.size}") }
}
