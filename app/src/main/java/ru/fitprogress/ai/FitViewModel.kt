package ru.fitprogress.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate

class FitViewModel @JvmOverloads constructor(app: Application, val repo: Repository = (app as FitApp).repository) : AndroidViewModel(app) {
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 20)
    val busy = MutableStateFlow(false)
    val loadFailure = MutableStateFlow<String?>(null)
    private val mutations = OrderedMutations()
    private fun <T> Flow<T>.reportReadFailures(): Flow<T> = catch { error ->
        if(error is CancellationException || error !is Exception) throw error
        loadFailure.value = "Не удалось прочитать локальные данные (${error.javaClass.simpleName}). История не удалялась. Закройте приложение и перезапустите его."
    }
    val exercises = repo.exercises.reportReadFailures().stateIn(viewModelScope, SharingStarted.Eagerly, Catalog.exercises)
    val workouts = repo.workouts.reportReadFailures().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val runs = repo.runs.reportReadFailures().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val personalData = repo.personalData.reportReadFailures().stateIn(viewModelScope, SharingStarted.Eagerly, PersonalState())
    val goals = personalData.map { it.goals }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val wellbeing = personalData.map { it.wellbeing }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val profile = repo.profile.reportReadFailures().stateIn(viewModelScope, SharingStarted.Eagerly, Profile())
    val runLaunch = RunService.launch.state
    private var requestedRunRoute: NavigationRoute? = null
    fun requestRunStart(requestPermission: () -> Unit, route: NavigationRoute? = null) {
        requestedRunRoute = route
        beginRunStart(requestPermission)
    }
    fun retryRunStart(requestPermission: () -> Unit) = beginRunStart(requestPermission)
    private fun beginRunStart(requestPermission: () -> Unit) {
        val context = getApplication<Application>()
        val fine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        android.util.Log.i("FitRunStart", "UI start pressed; fine=$fine phase=${runLaunch.value.phase}")
        if(!RunService.launch.request(fine)) return
        if(fine) dispatchRunStart() else try { requestPermission() } catch(e: RuntimeException) { RunService.launch.fail(RunLaunchError.PERMISSION, e.javaClass.simpleName) }
    }
    fun runPermissionResult() {
        val fine = getApplication<Application>().checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        android.util.Log.i("FitRunStart", "Permission result; fine=$fine")
        if(RunService.launch.permissionResult(fine)) dispatchRunStart()
    }
    private fun dispatchRunStart() {
        val context = getApplication<Application>()
        try {
            val enabled = context.getSystemService(android.location.LocationManager::class.java).isLocationEnabled
            if(RunService.launch.dispatch(enabled) { RunService.command(context, RunService.START, requestedRunRoute) }) {
                val attempt = runLaunch.value.attempt
                viewModelScope.launch { delay(15000); RunService.launch.timeout(attempt) }
            }
        } catch(e: RuntimeException) { android.util.Log.e("FitRunStart", "Dispatch failed", e); RunService.launch.fail(RunLaunchError.SERVICE, e.javaClass.simpleName) }
    }
    fun action(block: suspend () -> Unit): Job = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        try { mutations.run { check(loadFailure.value == null) { "Изменения недоступны до восстановления чтения данных" }; block() } }
        catch(error: Exception) { if(error is CancellationException) throw error; messages.emit(error.message ?: "Не удалось сохранить данные") }
    }
    fun exclusive(block: suspend () -> Unit) {
        if(!busy.compareAndSet(false, true)) return
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { mutations.run { check(loadFailure.value == null) { "Изменения недоступны до восстановления чтения данных" }; block() } }
            catch(error: Exception) { if(error is CancellationException) throw error; messages.emit(error.message ?: "Ошибка операции") }
            finally { busy.value = false }
        }
    }
    fun notify(text: String) { messages.tryEmit(text) }
    val savedRoutes = repo.savedRoutes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun updateNavigationRoute(route: NavigationRoute) = action { RouteMath.validate(route); RunService.command(getApplication(), RunService.UPDATE_ROUTE, route) }
    fun create(onCreated: (String) -> Unit) = exclusive { val w = Workout(); repo.save(w); onCreated(w.id) }
    fun update(id: String, block: (Workout) -> Workout) = action { repo.updateWorkout(id, block) }
    fun addExercise(workout: Workout, exercise: Exercise) = update(workout.id) { require(it.items.size < 300) { "Не более 300 упражнений" }; it.copy(items = it.items + Training.item(exercise).copy(rest = if(exercise.rest == 60) profile.value.rest else exercise.rest)) }
    fun stamp() = AndroidClock.read(getApplication())
    fun start(w: Workout) = action { repo.updateWorkout(w.id) {
        require(it.items.isNotEmpty() && !it.template && !it.recoveryDay) { "Добавьте упражнения" }
        SessionTimers.resume(it, stamp())
    }; try { WorkoutTimerService.start(getApplication()) } catch(e: Exception) { repo.updateWorkout(w.id) { SessionTimers.pause(it, stamp()) }; throw e } }
    fun pause(w: Workout) = update(w.id) { SessionTimers.pause(it, stamp()) }
    fun rest(id: String, seconds: Int) = update(id) { SessionTimers.startRest(it, seconds, stamp()) }
    fun stage(id: String, set: SetEntry) = update(id) { SessionTimers.startStage(it, set, stamp()) }
    fun completeSet(w: Workout, itemId: String, setId: String, entry: SetEntry) = update(w.id) { SessionEdits.complete(it, itemId, entry.copy(id = setId), stamp()) }
    fun finish(w: Workout, effort: Int, recovery: Int, pain: Boolean) = update(w.id) { SessionTimers.finish(it, stamp()).copy(effort = effort, recovery = recovery, pain = pain) }
    fun createPlan(open: (String) -> Unit) = exclusive { val p = TrainingPlan(); repo.save(PlanBuilder.definition(p)); open(p.id) }
    fun updatePlan(id: String, transform: (TrainingPlan) -> TrainingPlan) = update(id) { w -> val p = transform(requireNotNull(w.plan)); PlanBuilder.validate(p); PlanBuilder.definition(p) }
    fun duplicate(w: Workout, template: Boolean = false, onCreated: (String) -> Unit = {}) = exclusive { val copy = Training.duplicate(w, date = LocalDate.now(), template = template).copy(name = w.name + if(template) " · шаблон" else " · копия"); repo.save(copy); onCreated(copy.id) }
    fun demo() = exclusive {
        val now = System.currentTimeMillis(); val pts = (0..40).map { i -> GeoPoint(56.83 + i * 0.00005, 60.60 + kotlin.math.sin(i / 6.0) * 0.0002, 5f, now - (40 - i) * 5000) }
        repo.saveRun(Run(started = now - 200000, ended = now, points = pts, distance = pts.zipWithNext().sumOf { Gps.distance(it.first, it.second) }, accumulatedMs = 200000, activeSince = 0, status = "done", demo = true))
        messages.emit("Демо-маршрут создан. Он исключён из статистики.")
    }
    fun copyWeek(from: LocalDate, to: LocalDate) = exclusive { val copies = Training.copyWeek(workouts.value, from, to); require(copies.isNotEmpty()) { "В исходной неделе нет занятий" }; repo.saveAll(copies); messages.emit("Скопировано занятий: ${copies.size}") }
}
