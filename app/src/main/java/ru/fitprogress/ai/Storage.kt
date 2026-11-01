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

@Entity(tableName = "exercises") data class ExerciseRow(@PrimaryKey val id: String, val payload: String)
@Entity(tableName = "workouts") data class WorkoutRow(@PrimaryKey val id: String, val payload: String)
@Entity(tableName = "runs") data class RunRow(@PrimaryKey val id: String, val payload: String)
@Dao interface FitDao {
    @Query("SELECT * FROM exercises") fun exercises(): Flow<List<ExerciseRow>>
    @Query("SELECT * FROM workouts") fun workouts(): Flow<List<WorkoutRow>>
    @Query("SELECT * FROM runs") fun runs(): Flow<List<RunRow>>
    @Query("SELECT * FROM exercises") suspend fun allExercises(): List<ExerciseRow>
    @Query("SELECT * FROM workouts") suspend fun allWorkouts(): List<WorkoutRow>
    @Query("SELECT * FROM runs") suspend fun allRuns(): List<RunRow>
    @Query("SELECT * FROM workouts WHERE id=:id") suspend fun workout(id: String): WorkoutRow?
    @Query("SELECT * FROM runs WHERE id=:id") suspend fun run(id: String): RunRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putExercise(row: ExerciseRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putWorkout(row: WorkoutRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRun(row: RunRow)
    @Query("DELETE FROM exercises WHERE id=:id") suspend fun deleteExercise(id: String)
    @Query("DELETE FROM workouts WHERE id=:id") suspend fun deleteWorkout(id: String)
    @Query("DELETE FROM exercises") suspend fun clearExercises()
    @Query("DELETE FROM workouts") suspend fun clearWorkouts()
    @Query("DELETE FROM runs") suspend fun clearRuns()
}
@Database(entities = [ExerciseRow::class, WorkoutRow::class, RunRow::class], version = 1, exportSchema = true)
abstract class FitDatabase : RoomDatabase() { abstract fun dao(): FitDao }
val Context.settingsStore by preferencesDataStore("profile")
data class Profile(val name: String = "Спортсмен", val level: String = "Новичок", val goals: String = "Здоровье и регулярность", val activities: String = "Силовые, бег", val light: Boolean = false, val notifications: Boolean = false, val rest: Int = 60, val timerSound: Boolean = true, val timerVibration: Boolean = true)
class Repository(private val db: FitDatabase, private val context: Context) {
    private val dao = db.dao(); private val gson = BackupCodec.gson; private val mutex = Mutex()
    private suspend fun <T> storage(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    val exercises = dao.exercises().map { rows -> Catalog.exercises + rows.map { gson.fromJson(it.payload, Exercise::class.java) } }.flowOn(Dispatchers.Default)
    val workouts = dao.workouts().map { rows -> rows.map { gson.fromJson(it.payload, Workout::class.java) }.sortedBy { it.date } }.flowOn(Dispatchers.Default)
    val runs = dao.runs().map { rows -> rows.map { gson.fromJson(it.payload, Run::class.java) }.sortedByDescending { it.started } }.flowOn(Dispatchers.Default)
    val profile = context.settingsStore.data.map { p -> Profile(p[stringPreferencesKey("name")] ?: "Спортсмен", p[stringPreferencesKey("level")] ?: "Новичок", p[stringPreferencesKey("goals")] ?: "Здоровье и регулярность", p[stringPreferencesKey("activities")] ?: "Силовые, бег", p[booleanPreferencesKey("light")] ?: false, p[booleanPreferencesKey("notifications")] ?: false, p[intPreferencesKey("rest")] ?: 60, p[booleanPreferencesKey("timerSound")] ?: true, p[booleanPreferencesKey("timerVibration")] ?: true) }
    suspend fun saveProfile(p: Profile) { context.settingsStore.edit { it[stringPreferencesKey("name")] = p.name; it[stringPreferencesKey("level")] = p.level; it[stringPreferencesKey("goals")] = p.goals; it[stringPreferencesKey("activities")] = p.activities; it[booleanPreferencesKey("light")] = p.light; it[booleanPreferencesKey("notifications")] = p.notifications; it[intPreferencesKey("rest")] = p.rest; it[booleanPreferencesKey("timerSound")] = p.timerSound; it[booleanPreferencesKey("timerVibration")] = p.timerVibration } }
    suspend fun save(e: Exercise) = storage { BackupCodec.validateExercise(e); require(!e.builtIn); dao.putExercise(ExerciseRow(e.id, gson.toJson(e))) }
    suspend fun deleteExercise(id: String) = storage { require(!id.startsWith("builtin-")); dao.deleteExercise(id) } // Sessions retain a complete name/sets snapshot.
    suspend fun save(w: Workout) = storage { BackupCodec.validateWorkout(w); val old = dao.workout(w.id)?.let { gson.fromJson(it.payload, Workout::class.java) }; require(w.status == "planned" && (old == null || old.status == "planned")) { "Активную или завершённую историю нельзя перезаписывать" }; dao.putWorkout(WorkoutRow(w.id, gson.toJson(w))) }
    suspend fun saveAll(ws: List<Workout>) = storage { db.withTransaction { ws.forEach { BackupCodec.validateWorkout(it); require(dao.workout(it.id) == null); dao.putWorkout(WorkoutRow(it.id, gson.toJson(it))) } } }
    suspend fun deleteWorkout(id: String) = storage { val w = dao.workout(id)?.let { gson.fromJson(it.payload, Workout::class.java) }; require(w?.status == "planned"); dao.deleteWorkout(id) }
    suspend fun updateWorkout(id: String, change: (Workout) -> Workout) = storage {
        dao.workout(id)?.let { row -> val w = gson.fromJson(row.payload, Workout::class.java); if(w.status != "done") { val next = change(w); require(next.id == id) { "Идентификатор тренировки нельзя менять" }; BackupCodec.validateWorkout(next); if(next.status == "active") require(dao.allWorkouts().none { it.id != id && gson.fromJson(it.payload, Workout::class.java).status in listOf("active", "paused") }) { "Сначала завершите текущую тренировку" }; dao.putWorkout(WorkoutRow(id, gson.toJson(next))) } }
    }
    suspend fun saveRun(r: Run) = storage { dao.putRun(RunRow(r.id, gson.toJson(r))) }
    suspend fun updateRun(id: String, change: (Run) -> Run): Run? = storage {
        val r = dao.run(id)?.let { gson.fromJson(it.payload, Run::class.java) } ?: return@storage null
        if(r.status == "done") return@storage r
        change(r).also { dao.putRun(RunRow(it.id, gson.toJson(it))) }
    }
    suspend fun currentRun(): Run? = withContext(Dispatchers.IO) { dao.allRuns().map { gson.fromJson(it.payload, Run::class.java) }.firstOrNull { it.status != "done" && !it.demo } }
    suspend fun snapshot(): Backup = storage { db.withTransaction { Backup(exercises = dao.allExercises().map { gson.fromJson(it.payload, Exercise::class.java) }, workouts = dao.allWorkouts().map { gson.fromJson(it.payload, Workout::class.java) }, runs = dao.allRuns().map { gson.fromJson(it.payload, Run::class.java) }) } }
    suspend fun restore(b: Backup) = storage { // Validated additive import; existing history is never overwritten.
        db.withTransaction {
            require(dao.allRuns().none { gson.fromJson(it.payload, Run::class.java).status != "done" } && dao.allWorkouts().none { gson.fromJson(it.payload, Workout::class.java).status in listOf("active", "paused") }) { "Сначала завершите активные занятия" }
            val existingE = dao.allExercises().map { it.id }.toSet(); val existingW = dao.allWorkouts().map { it.id }.toSet(); val existingR = dao.allRuns().map { it.id }.toSet()
            b.exercises.filter { it.id !in existingE }.forEach { dao.putExercise(ExerciseRow(it.id, gson.toJson(it))) }
            b.workouts.filter { it.id !in existingW }.forEach { val w = SessionTimers.forImport(it); dao.putWorkout(WorkoutRow(w.id, gson.toJson(w))) }
            b.runs.filter { it.id !in existingR }.forEach { val r = if(it.status != "done") it.copy(status = "paused", activeSince = 0, timing = null, segment = it.segment + 1) else it; dao.putRun(RunRow(r.id, gson.toJson(r))) }
        }
    }
    suspend fun erase() = storage { db.withTransaction { require(dao.allRuns().none { gson.fromJson(it.payload, Run::class.java).status != "done" } && dao.allWorkouts().none { gson.fromJson(it.payload, Workout::class.java).status in listOf("active", "paused") }) { "Сначала завершите активные занятия" }; dao.clearExercises(); dao.clearWorkouts(); dao.clearRuns() }; context.settingsStore.edit { it.clear() } }
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
class FitApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var repository: Repository
    lateinit var ready: Deferred<Unit>
    override fun onCreate() { super.onCreate(); val db = Room.databaseBuilder(this, FitDatabase::class.java, "fitprogress.db").build(); repository = Repository(db, this)
        ready = scope.async { repository.recoverRun(); repository.recoverWorkouts(AndroidClock.read(this@FitApp)) }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("weekly-analysis", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<WeeklyWorker>(1, TimeUnit.DAYS).build())
    }
}
class WeeklyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val repo = (applicationContext as FitApp).repository; val snapshot = repo.snapshot()
        val monday = LocalDate.now().with(java.time.DayOfWeek.MONDAY); val from = monday.minusWeeks(1)
        val week = snapshot.workouts.filter { !it.template && LocalDate.parse(it.date) >= from && LocalDate.parse(it.date) < monday }
        val reasons = LocalRecommendations().suggest(week, snapshot.runs).joinToString("\n") { "${it.title}: ${it.reason}" }
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
    } catch (_: Exception) { Result.retry() }
}
