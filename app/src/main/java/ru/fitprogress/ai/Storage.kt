package ru.fitprogress.ai

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.concurrent.TimeUnit

private val Context.productionSettings by preferencesDataStore("profile")
private val Context.testSettings by preferencesDataStore("profile-instrumentation")
val Context.settingsStore: androidx.datastore.core.DataStore<Preferences>
    get() = if((applicationContext as? FitApp)?.isolatedTestStorage == true) testSettings else productionSettings
data class Profile(val name: String = "Спортсмен", val level: String = "Новичок", val goals: String = "Здоровье и регулярность", val activities: String = "Силовые, бег", val light: Boolean = false, val notifications: Boolean = false, val rest: Int = 60, val timerSound: Boolean = true, val timerVibration: Boolean = true, val accent: String = "lime", val mapEnabled: Boolean = true, val reminders: Boolean = false, val reminderHour: Int = 18, val runSignals: Boolean = false, val routingBackendUrl: String = "https://valhalla1.openstreetmap.de")
class Repository(private val db: FitDatabase, private val context: Context) {
    private val dao = db.dao(); private val gson = BackupCodec.gson; private val mutex = Mutex()
    private suspend fun <T> storage(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    val personal = dao.personal().flowOn(Dispatchers.IO)
    val personalData = personal.map { rows -> PersonalState(true, rows.filter { it.kind == "goal" }.map { gson.fromJson(it.payload, SportGoal::class.java) }, rows.filter { it.kind == "wellbeing" }.map { gson.fromJson(it.payload, Wellbeing::class.java) }.sortedByDescending { it.date }) }.flowOn(Dispatchers.Default)
    val goals = personal.map { rows -> rows.filter { it.kind == "goal" }.map { gson.fromJson(it.payload, SportGoal::class.java) } }.flowOn(Dispatchers.Default)
    val wellbeing = personal.map { rows -> rows.filter { it.kind == "wellbeing" }.map { gson.fromJson(it.payload, Wellbeing::class.java) }.sortedByDescending { it.date } }.flowOn(Dispatchers.Default)
    val savedRoutes = personal.map { rows -> rows.filter { it.kind == "route" }.mapNotNull { row -> runCatching { gson.fromJson(row.payload, NavigationRoute::class.java).also(RouteMath::validate) }.getOrNull() }.sortedByDescending { it.createdAt } }.flowOn(Dispatchers.Default)
    suspend fun saveRoute(route: NavigationRoute) = storage { RouteMath.validate(route); dao.putPersonal(PersonalRow("route:${route.id}", "route", gson.toJson(route))) }
    suspend fun deleteRoute(id: String) = storage { dao.deletePersonal("route:$id") }
    suspend fun saveGoal(g: SportGoal) = storage { PersonalValidation.goal(g); dao.putPersonal(PersonalRow("goal:${g.id}", "goal", gson.toJson(g))) }
    suspend fun saveWellbeing(w: Wellbeing) = storage { PersonalValidation.wellbeing(w); require(LocalDate.parse(w.date) <= LocalDate.now()) { "Нельзя оценить самочувствие будущего дня" }; dao.putPersonal(PersonalRow("wellbeing:${w.date}", "wellbeing", gson.toJson(w))) }
    suspend fun deleteWellbeing(date: String) = storage { dao.deletePersonal("wellbeing:$date") }
    val exercises = dao.exercises().map { rows ->
        val local = Catalog.exercises + OpenExerciseLibrary.entries(context)
        val localIds = local.mapTo(HashSet()) { it.id }
        local + rows.map { gson.fromJson(it.payload, Exercise::class.java) }.filterNot { it.id in localIds }
    }.flowOn(Dispatchers.Default)
    val workouts = dao.workouts().map { rows -> rows.map { gson.fromJson(it.payload, Workout::class.java) }.sortedBy { it.date } }.flowOn(Dispatchers.Default)
    val runs = dao.runs().map { rows -> rows.map { gson.fromJson(it.payload, Run::class.java) }.sortedByDescending { it.started } }.flowOn(Dispatchers.Default)
    private fun readProfile(p: Preferences) = Profile(p[stringPreferencesKey("name")] ?: "Спортсмен", p[stringPreferencesKey("level")] ?: "Новичок", p[stringPreferencesKey("goals")] ?: "Здоровье и регулярность", p[stringPreferencesKey("activities")] ?: "Силовые, бег", p[booleanPreferencesKey("light")] ?: false, p[booleanPreferencesKey("notifications")] ?: false, p[intPreferencesKey("rest")] ?: 60, p[booleanPreferencesKey("timerSound")] ?: true, p[booleanPreferencesKey("timerVibration")] ?: true, p[stringPreferencesKey("accent")] ?: "lime", p[booleanPreferencesKey("mapEnabled")] ?: true, p[booleanPreferencesKey("reminders")] ?: false, p[intPreferencesKey("reminderHour")] ?: 18, p[booleanPreferencesKey("runSignals")] ?: false, p[stringPreferencesKey("routingBackendUrl")] ?: "https://valhalla1.openstreetmap.de")
    val profile = context.settingsStore.data.map(::readProfile)
    suspend fun updateProfile(change: (Profile) -> Profile) { context.settingsStore.edit { data ->
        val p = change(readProfile(data)); require(p.name.isNotBlank() && p.name.length <= 80 && p.rest in 0..3600 && p.accent in listOf("lime", "cyan", "orange") && p.reminderHour in 0..23 && p.routingBackendUrl.length <= 300 && runCatching { ValhallaRoutingBackend(p.routingBackendUrl) }.isSuccess) { "Укажите HTTPS backend или HTTP адрес локальной сети" }
        data[stringPreferencesKey("name")] = p.name; data[stringPreferencesKey("level")] = p.level; data[stringPreferencesKey("goals")] = p.goals; data[stringPreferencesKey("activities")] = p.activities
        data[booleanPreferencesKey("light")] = p.light; data[booleanPreferencesKey("notifications")] = p.notifications; data[intPreferencesKey("rest")] = p.rest
        data[booleanPreferencesKey("timerSound")] = p.timerSound; data[booleanPreferencesKey("timerVibration")] = p.timerVibration; data[stringPreferencesKey("accent")] = p.accent; data[booleanPreferencesKey("mapEnabled")] = p.mapEnabled
        data[booleanPreferencesKey("reminders")] = p.reminders; data[intPreferencesKey("reminderHour")] = p.reminderHour; data[booleanPreferencesKey("runSignals")] = p.runSignals; data[stringPreferencesKey("routingBackendUrl")] = p.routingBackendUrl
    } }
    suspend fun saveProfile(p: Profile) = updateProfile { p }
    suspend fun save(e: Exercise) = storage { BackupCodec.validateExercise(e); require(!e.builtIn); dao.putExercise(ExerciseRow(e.id, gson.toJson(e))) }
    suspend fun deleteExercise(id: String) = storage { require(!id.startsWith("builtin-")); dao.deleteExercise(id) } // Sessions retain a complete name/sets snapshot.
    suspend fun save(w: Workout) = storage { BackupCodec.validateWorkout(w); val old = dao.workout(w.id)?.let { gson.fromJson(it.payload, Workout::class.java) }; require(w.status == "planned" && (old == null || old.status == "planned")) { "Активную или завершённую историю нельзя перезаписывать" }; dao.putWorkout(WorkoutRow(w.id, gson.toJson(w))) }
    suspend fun saveAll(ws: List<Workout>) = storage { db.withTransaction { ws.forEach { BackupCodec.validateWorkout(it); require(dao.workout(it.id) == null); dao.putWorkout(WorkoutRow(it.id, gson.toJson(it))) } } }
    /** Import user exercises and planned sessions in one transaction; existing history is never overwritten. */
    suspend fun saveImportedProgram(newExercises: List<Exercise>, sessions: List<Workout>) = storage {
        require(newExercises.map { it.id }.distinct().size == newExercises.size)
        require(sessions.map { it.id }.distinct().size == sessions.size)
        db.withTransaction {
            newExercises.forEach { exercise ->
                BackupCodec.validateExercise(exercise)
                require(!exercise.builtIn)
                dao.putExercise(ExerciseRow(exercise.id, gson.toJson(exercise)))
            }
            sessions.forEach { session ->
                BackupCodec.validateWorkout(session)
                require(session.status == "planned" && dao.workout(session.id) == null)
                dao.putWorkout(WorkoutRow(session.id, gson.toJson(session)))
            }
        }
    }
    suspend fun deleteWorkout(id: String) = storage { val w = dao.workout(id)?.let { gson.fromJson(it.payload, Workout::class.java) }; require(w?.status == "planned"); dao.deleteWorkout(id) }
    suspend fun updateWorkout(id: String, change: (Workout) -> Workout) = storage {
        dao.workout(id)?.let { row -> val w = gson.fromJson(row.payload, Workout::class.java); if(w.status != "done") { val next = SessionEdits.cancelMissingStage(change(w)); require(next.id == id) { "Идентификатор тренировки нельзя менять" }; BackupCodec.validateWorkout(next); if(next.status == "active") require(dao.allWorkouts().none { it.id != id && gson.fromJson(it.payload, Workout::class.java).status in listOf("active", "paused") }) { "Сначала завершите текущую тренировку" }; dao.putWorkout(WorkoutRow(id, gson.toJson(next))) } }
    }
    suspend fun saveRun(r: Run) = storage {
        val previous = dao.run(r.id)?.let { gson.fromJson(it.payload, Run::class.java) }
        require(previous?.status != "done" || previous == r) { "Сохранённую пробежку нельзя перезаписывать" }
        if(previous == null && r.status != "done") require(dao.allRuns().none { gson.fromJson(it.payload, Run::class.java).status != "done" }) { "Сначала завершите текущую пробежку" }
        if(previous != r) dao.putRun(RunRow(r.id, gson.toJson(r)))
    }
    suspend fun updateRun(id: String, change: (Run) -> Run): Run? = storage {
        val r = dao.run(id)?.let { gson.fromJson(it.payload, Run::class.java) } ?: return@storage null
        if(r.status == "done") return@storage r
        change(r).also { require(it.id == id) { "Идентификатор пробежки нельзя менять" }; dao.putRun(RunRow(it.id, gson.toJson(it))) }
    }
    suspend fun currentRun(): Run? = withContext(Dispatchers.IO) { dao.allRuns().map { gson.fromJson(it.payload, Run::class.java) }.firstOrNull { it.status != "done" && !it.demo } }
    private suspend fun snapshotUnlocked(): Backup {
        val records = dao.allPersonal()
        return Backup(exercises = dao.allExercises().map { gson.fromJson(it.payload, Exercise::class.java) }, workouts = dao.allWorkouts().map { gson.fromJson(it.payload, Workout::class.java) }, runs = dao.allRuns().map { gson.fromJson(it.payload, Run::class.java) },
            goals = records.filter { it.kind == "goal" }.map { gson.fromJson(it.payload, SportGoal::class.java) }, wellbeing = records.filter { it.kind == "wellbeing" }.map { gson.fromJson(it.payload, Wellbeing::class.java) }, coachReceipts = records.filter { it.kind == "coach" }.map { gson.fromJson(it.payload, CoachReceipt::class.java) }, savedRoutes = records.filter { it.kind == "route" }.map { gson.fromJson(it.payload, NavigationRoute::class.java) })
    }
    suspend fun snapshot(): Backup = storage { db.withTransaction { snapshotUnlocked() } }
    suspend fun applyCoach(preview: CoachPreview, expected: Backup) = storage {
        db.withTransaction {
            val current = snapshotUnlocked()
            require(current.workouts.associateBy { it.id } == expected.workouts.associateBy { it.id } && current.runs.associateBy { it.id } == expected.runs.associateBy { it.id } && current.wellbeing.associateBy { it.date } == expected.wellbeing.associateBy { it.date }) { "Данные изменились. Откройте рекомендации заново." }
            val today = LocalDate.now(); val engine = AdaptiveCoach(); val option = engine.options(current.workouts, current.runs, current.wellbeing, today).first { it.id == preview.option.id }
            require(option.enabled && preview.from == weekOf(today).plusWeeks(1).toString()) { "Предложение больше не применимо" }
            val regenerated = engine.preview(option, current.workouts, today)
            // Compare semantic loads, not freshly generated UUIDs.
            fun loads(ws: List<Workout>) = ws.map { w -> w.copy(id = "", items = w.items.map { i -> i.copy(id = "", sets = i.sets.map { it.copy(id = "") }) }) }
            require(loads(regenerated.workouts) == loads(preview.workouts)) { "План изменился" }
            require(dao.personalById("coach:${preview.from}") == null) { "Рекомендация на эту неделю уже применена; дальнейшие правки доступны в календаре" }
            val target = current.workouts.filter { !it.template && LocalDate.parse(it.date) in LocalDate.parse(preview.from)..LocalDate.parse(preview.until) }
            require(target.none { it.status != "planned" }) { "В целевой неделе уже есть активные или завершённые занятия" }
            target.forEach { dao.deleteWorkout(it.id) }
            preview.workouts.forEach { BackupCodec.validateWorkout(it); require(dao.workout(it.id) == null); dao.putWorkout(WorkoutRow(it.id, gson.toJson(it))) }
            val receipt = CoachReceipt(preview.from, option.id, System.currentTimeMillis(), preview.workouts.map { it.id })
            dao.putPersonal(PersonalRow("coach:${preview.from}", "coach", gson.toJson(receipt)))
        }
    }
    suspend fun restore(b: Backup) = storage { // Validated additive import; existing history is never overwritten.
        db.withTransaction {
            require(dao.allRuns().none { gson.fromJson(it.payload, Run::class.java).status != "done" } && dao.allWorkouts().none { gson.fromJson(it.payload, Workout::class.java).status in listOf("active", "paused") }) { "Сначала завершите активные занятия" }
            val existingE = dao.allExercises().map { it.id }.toSet(); val existingW = dao.allWorkouts().map { it.id }.toSet(); val existingR = dao.allRuns().map { it.id }.toSet()
            b.goals.forEach { if(dao.personalById("goal:${it.id}") == null) dao.putPersonal(PersonalRow("goal:${it.id}", "goal", gson.toJson(it))) }
            b.wellbeing.forEach { if(dao.personalById("wellbeing:${it.date}") == null) dao.putPersonal(PersonalRow("wellbeing:${it.date}", "wellbeing", gson.toJson(it))) }
            b.coachReceipts.forEach { if(dao.personalById("coach:${it.week}") == null) dao.putPersonal(PersonalRow("coach:${it.week}", "coach", gson.toJson(it))) }
            b.savedRoutes.forEach { if(dao.personalById("route:${it.id}") == null) { RouteMath.validate(it); dao.putPersonal(PersonalRow("route:${it.id}", "route", gson.toJson(it))) } }
            b.exercises.filter { it.id !in existingE }.forEach { dao.putExercise(ExerciseRow(it.id, gson.toJson(it))) }
            b.workouts.filter { it.id !in existingW }.forEach { val w = SessionTimers.forImport(it); dao.putWorkout(WorkoutRow(w.id, gson.toJson(w))) }
            b.runs.filter { it.id !in existingR }.forEach { val r = if(it.status != "done") it.copy(status = "paused", activeSince = 0, timing = null, segment = it.segment + 1) else it; dao.putRun(RunRow(r.id, gson.toJson(r))) }
        }
    }
    suspend fun importWorkouts(b: Backup): Pair<Int, Int> = storage {
        b.workouts.forEach(BackupCodec::validateWorkout); b.exercises.forEach(BackupCodec::validateExercise)
        db.withTransaction {
            val existing = dao.allWorkouts().mapTo(HashSet()) { it.id }
            val fresh = b.workouts.filterNot { it.id in existing }
            // Imported unfinished history can coexist as drafts; activate only one session later.
            val used = fresh.flatMap { it.items }.mapTo(HashSet()) { it.exerciseId }
            val known = dao.allExercises().mapTo(HashSet()) { it.id }
            b.exercises.filter { it.id in used && it.id !in known }.forEach { dao.putExercise(ExerciseRow(it.id, gson.toJson(it))) }
            fresh.forEach { w ->
                val imported = SessionTimers.forImport(w)
                // Several unfinished source sessions become planned drafts with actual sets preserved.
                val draft = if(imported.status == "paused") imported.copy(status = "planned", notes = imported.notes + "\nНезавершённая импортированная тренировка: черновик, выполненные подходы сохранены.") else imported
                dao.putWorkout(WorkoutRow(draft.id, gson.toJson(draft)))
            }
            fresh.size to (b.workouts.size - fresh.size)
        }
    }
    suspend fun erase() = storage { db.withTransaction { require(dao.allRuns().none { gson.fromJson(it.payload, Run::class.java).status != "done" } && dao.allWorkouts().none { gson.fromJson(it.payload, Workout::class.java).status in listOf("active", "paused") }) { "Сначала завершите активные занятия" }; dao.clearExercises(); dao.clearWorkouts(); dao.clearRuns(); dao.clearPersonal() }; context.settingsStore.edit { it.clear() } }
    suspend fun recoverWorkouts(now: ClockStamp) = storage {
        dao.allWorkouts().forEach { row -> val w = gson.fromJson(row.payload, Workout::class.java); val recovered = SessionTimers.recover(w, now); if(recovered != w) dao.putWorkout(WorkoutRow(w.id, gson.toJson(recovered))) }
    }
    suspend fun checkpointWorkouts(now: ClockStamp) = storage {
        dao.allWorkouts().forEach { row -> val w = gson.fromJson(row.payload, Workout::class.java); if(w.status == "active") { val next = SessionTimers.checkpoint(w, now); dao.putWorkout(WorkoutRow(w.id, gson.toJson(next))) } }
    }
    suspend fun applyPlan(planId: String, expectedPlan: TrainingPlan, today: LocalDate) = storage {
        db.withTransaction {
            val definition = dao.workout(planId)?.let { gson.fromJson(it.payload, Workout::class.java) } ?: error("Программа удалена")
            require(definition.plan == expectedPlan) { "Программа изменилась. Откройте предварительный план заново." }
            val current = dao.allWorkouts().map { gson.fromJson(it.payload, Workout::class.java) }
            val diff = PlanBuilder.preview(expectedPlan, current, today)
            diff.deleteIds.forEach { dao.deleteWorkout(it) }
            (diff.inserts + diff.updates).forEach { dao.putWorkout(WorkoutRow(it.id, gson.toJson(it))) }
        }
    }
    suspend fun recoverRun() { currentRun()?.let { r -> if(r.status == "active") updateRun(r.id) { it.copy(status = "paused", activeSince = 0, timing = null, segment = it.segment + 1) } } } // Never silently count a service interruption as recorded running time.
}
open class FitApp : Application() {
    open val isolatedTestStorage: Boolean = false
    open val backgroundSchedulingEnabled: Boolean = true
    private val databaseName: String get() = if(isolatedTestStorage) "fitprogress-instrumentation.db" else "fitprogress.db"
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var repository: Repository
    lateinit var ready: Deferred<Unit>
    override fun onCreate() { super.onCreate(); val db = Room.databaseBuilder(this, FitDatabase::class.java, databaseName).addMigrations(MIGRATION_1_2).build(); repository = Repository(db, this)
        ready = scope.async { repository.recoverRun(); repository.recoverWorkouts(AndroidClock.read(this@FitApp)) }
        if(!backgroundSchedulingEnabled) return
        scope.launch {
            repository.profile.map { it.reminders }.catch { error -> if(error is CancellationException || error !is Exception) throw error; emit(false) }.distinctUntilChanged().collect { enabled ->
                val wm = WorkManager.getInstance(this@FitApp)
                if(enabled) wm.enqueueUniquePeriodicWork("planned-reminders", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build()) else wm.cancelUniqueWork("planned-reminders")
            }
        }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("weekly-analysis", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<WeeklyWorker>(1, TimeUnit.DAYS).build())
    }
}
class WeeklyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val repo = (applicationContext as FitApp).repository; val snapshot = repo.snapshot()
        val monday = LocalDate.now().with(java.time.DayOfWeek.MONDAY); val from = monday.minusWeeks(1)
        val week = snapshot.workouts.filter { !it.template && LocalDate.parse(it.date) >= from && LocalDate.parse(it.date) < monday }
        val reasons = AdaptiveCoach().options(snapshot.workouts, snapshot.runs, snapshot.wellbeing, LocalDate.now()).joinToString("\n") { "${it.title}: ${it.reason}" }
        applicationContext.settingsStore.edit { it[stringPreferencesKey("weekly-analysis")] = reasons }
        if(repo.profile.first().notifications && (android.os.Build.VERSION.SDK_INT < 33 || applicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            val manager = applicationContext.getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(android.app.NotificationChannel("weekly", "Недельный анализ", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val token = monday.toString()
            val last = applicationContext.settingsStore.data.first()[stringPreferencesKey("last-weekly-notification")]
            if(last != token) {
                val open = android.app.PendingIntent.getActivity(applicationContext, 3, android.content.Intent(applicationContext, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                manager.notify(43, androidx.core.app.NotificationCompat.Builder(applicationContext, "weekly").setSmallIcon(R.drawable.ic_logo).setContentTitle("Недельный анализ готов").setContentText("Откройте предложения следующей недели").setContentIntent(open).setAutoCancel(true).build())
                applicationContext.settingsStore.edit { it[stringPreferencesKey("last-weekly-notification")] = token }
            }
        }
        Result.success()
    } catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
}
