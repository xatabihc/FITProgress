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
data class Profile(val name: String = "Спортсмен", val level: String = "Новичок", val goals: String = "Здоровье и регулярность", val activities: String = "Силовые, бег", val light: Boolean = false, val notifications: Boolean = false, val rest: Int = 60)
data class Backup(val format: String = "FitProgressAI", val version: Int = 1, val exercises: List<Exercise> = emptyList(), val workouts: List<Workout> = emptyList(), val runs: List<Run> = emptyList())
object BackupCodec {
    val gson = Gson()
    fun encode(backup: Backup): String = gson.toJson(backup)
    fun decode(text: String): Backup {
        require(text.toByteArray().size <= 20_000_000) { "Резервная копия больше 20 МБ" }
        val root = JsonParser.parseString(text).asJsonObject
        require(root.get("format")?.asString == "FitProgressAI" && root.get("version")?.asInt == 1) { "Неизвестный формат или версия" }
        listOf("exercises", "workouts", "runs").forEach { require(root.has(it) && root[it].isJsonArray) { "Отсутствует таблица $it" } }
        val b = gson.fromJson(text, Backup::class.java)
        require(b.exercises.size <= 10000 && b.workouts.size <= 20000 && b.runs.size <= 10000) { "Слишком много записей" }
        require((b.exercises.map { it.id }).distinct().size == b.exercises.size)
        require((b.workouts.map { it.id }).distinct().size == b.workouts.size)
        require((b.runs.map { it.id }).distinct().size == b.runs.size)
        b.exercises.forEach { validateExercise(it); require(!it.builtIn && !it.id.startsWith("builtin-")) }
        val exerciseIds = b.exercises.map { it.id }.toSet() + Catalog.exercises.map { it.id }
        b.workouts.forEach { w ->
            require(w.id.isNotBlank() && w.name.isNotBlank() && w.name.length <= 200)
            LocalDate.parse(w.date)
            require(w.status in listOf("planned", "active", "paused", "done"))
            require(w.accumulatedMs >= 0 && w.started >= 0 && w.ended >= 0 && w.activeSince >= 0 && w.restUntil >= 0)
            require(w.effort in 1..10 && w.recovery in 1..10 && w.items.size <= 300)
            require(w.items.map { it.id }.distinct().size == w.items.size)
            w.items.forEach { i -> require(i.id.isNotBlank() && i.name.isNotBlank() && i.exerciseId.isNotBlank()); require(i.exerciseId in exerciseIds || i.name.isNotBlank()); require(i.rest in 0..3600 && i.sets.size <= 100); i.sets.forEach { s -> require(s.id.isNotBlank() && s.reps in 0..10000 && s.weight.isFinite() && s.weight in 0.0..2000.0 && s.seconds in 0..86400) } }
        }
        require(b.workouts.count { it.status in listOf("active", "paused") } <= 1) { "Несколько активных тренировок" }
        b.runs.forEach { r -> require(r.id.isNotBlank() && r.status in listOf("active", "paused", "done") && r.started > 0 && r.ended >= 0 && r.accumulatedMs >= 0 && r.activeSince >= 0 && r.distance.isFinite() && r.distance >= 0 && r.points.size <= 200000); r.points.forEach { require(Gps.valid(it)) }; require(r.points.zipWithNext().all { it.first.time < it.second.time }) }
        require(b.runs.count { it.status != "done" } <= 1)
        return b
    }
    fun validateExercise(e: Exercise) { require(e.id.isNotBlank() && e.name.isNotBlank() && e.name.length <= 200 && e.category in categories && e.sets in 1..100 && e.reps in 0..10000 && e.weight.isFinite() && e.weight in 0.0..2000.0 && e.seconds in 0..86400 && e.rest in 0..3600) { "Проверьте название и параметры упражнения" } }
    private fun cell(s: Any?) = "\"" + s.toString().replace("\"", "\"\"").let { if(it.startsWith("=") || it.startsWith("+") || it.startsWith("-") || it.startsWith("@")) "'$it" else it } + "\""
    fun csv(b: Backup): String = buildString {
        append("тип,дата,название,упражнение,подход,повторения,вес_кг,секунды,выполнен,дистанция_м,время_мс\r\n")
        b.workouts.filter { it.status == "done" }.forEach { w -> w.items.forEach { i -> i.sets.forEachIndexed { index, s -> append(listOf("тренировка", w.date, w.name, i.name, index + 1, s.reps, s.weight, s.seconds, s.done, "", w.accumulatedMs).joinToString(",") { cell(it) }); append("\r\n") } } }
        b.runs.filter { it.status == "done" && !it.demo }.forEach { r -> append(listOf("бег", r.started, "Бег", "", "", "", "", "", true, r.distance, r.accumulatedMs).joinToString(",") { cell(it) }); append("\r\n") }
    }
}
class Repository(private val db: FitDatabase, private val context: Context) {
    private val dao = db.dao(); private val gson = BackupCodec.gson; private val mutex = Mutex()
    val exercises = dao.exercises().map { rows -> Catalog.exercises + rows.map { gson.fromJson(it.payload, Exercise::class.java) } }
    val workouts = dao.workouts().map { rows -> rows.map { gson.fromJson(it.payload, Workout::class.java) }.sortedBy { it.date } }
    val runs = dao.runs().map { rows -> rows.map { gson.fromJson(it.payload, Run::class.java) }.sortedByDescending { it.started } }
    val profile = context.settingsStore.data.map { p -> Profile(p[stringPreferencesKey("name")] ?: "Спортсмен", p[stringPreferencesKey("level")] ?: "Новичок", p[stringPreferencesKey("goals")] ?: "Здоровье и регулярность", p[stringPreferencesKey("activities")] ?: "Силовые, бег", p[booleanPreferencesKey("light")] ?: false, p[booleanPreferencesKey("notifications")] ?: false, p[intPreferencesKey("rest")] ?: 60) }
    suspend fun saveProfile(p: Profile) { context.settingsStore.edit { it[stringPreferencesKey("name")] = p.name; it[stringPreferencesKey("level")] = p.level; it[stringPreferencesKey("goals")] = p.goals; it[stringPreferencesKey("activities")] = p.activities; it[booleanPreferencesKey("light")] = p.light; it[booleanPreferencesKey("notifications")] = p.notifications; it[intPreferencesKey("rest")] = p.rest } }
    suspend fun save(e: Exercise) = mutex.withLock { BackupCodec.validateExercise(e); require(!e.builtIn); dao.putExercise(ExerciseRow(e.id, gson.toJson(e))) }
    suspend fun deleteExercise(id: String) = mutex.withLock { require(!id.startsWith("builtin-")); dao.deleteExercise(id) } // Sessions retain a complete name/sets snapshot.
    suspend fun save(w: Workout) = mutex.withLock { val old = dao.workout(w.id)?.let { gson.fromJson(it.payload, Workout::class.java) }; require(old?.status != "done") { "Завершённую историю нельзя менять" }; dao.putWorkout(WorkoutRow(w.id, gson.toJson(w))) }
    suspend fun saveAll(ws: List<Workout>) = mutex.withLock { db.withTransaction { ws.forEach { require(dao.workout(it.id) == null); dao.putWorkout(WorkoutRow(it.id, gson.toJson(it))) } } }
    suspend fun deleteWorkout(id: String) = mutex.withLock { val w = dao.workout(id)?.let { gson.fromJson(it.payload, Workout::class.java) }; require(w?.status == "planned"); dao.deleteWorkout(id) }
    suspend fun updateWorkout(id: String, change: (Workout) -> Workout) = mutex.withLock {
        dao.workout(id)?.let { row -> val w = gson.fromJson(row.payload, Workout::class.java); if(w.status != "done") { val next = change(w); if(next.status == "active") require(dao.allWorkouts().none { it.id != id && gson.fromJson(it.payload, Workout::class.java).status in listOf("active", "paused") }) { "Сначала завершите текущую тренировку" }; dao.putWorkout(WorkoutRow(id, gson.toJson(next))) } }
    }
    suspend fun saveRun(r: Run) = mutex.withLock { dao.putRun(RunRow(r.id, gson.toJson(r))) }
    suspend fun updateRun(id: String, change: (Run) -> Run): Run? = mutex.withLock {
        val r = dao.run(id)?.let { gson.fromJson(it.payload, Run::class.java) } ?: return@withLock null
        if(r.status == "done") return@withLock r
        change(r).also { dao.putRun(RunRow(it.id, gson.toJson(it))) }
    }
    suspend fun currentRun(): Run? = dao.allRuns().map { gson.fromJson(it.payload, Run::class.java) }.firstOrNull { it.status != "done" && !it.demo }
    suspend fun snapshot(): Backup = mutex.withLock { db.withTransaction { Backup(exercises = dao.allExercises().map { gson.fromJson(it.payload, Exercise::class.java) }, workouts = dao.allWorkouts().map { gson.fromJson(it.payload, Workout::class.java) }, runs = dao.allRuns().map { gson.fromJson(it.payload, Run::class.java) }) } }
    suspend fun restore(b: Backup) = mutex.withLock { // Validated additive import; existing history is never overwritten.
        db.withTransaction {
            require(dao.allRuns().none { gson.fromJson(it.payload, Run::class.java).status != "done" } && dao.allWorkouts().none { gson.fromJson(it.payload, Workout::class.java).status in listOf("active", "paused") }) { "Сначала завершите активные занятия" }
            val existingE = dao.allExercises().map { it.id }.toSet(); val existingW = dao.allWorkouts().map { it.id }.toSet(); val existingR = dao.allRuns().map { it.id }.toSet()
            b.exercises.filter { it.id !in existingE }.forEach { dao.putExercise(ExerciseRow(it.id, gson.toJson(it))) }
            b.workouts.filter { it.id !in existingW }.forEach { val w = if(it.status in listOf("active", "paused")) it.copy(status = "paused", activeSince = 0, restUntil = 0) else it; dao.putWorkout(WorkoutRow(w.id, gson.toJson(w))) }
            b.runs.filter { it.id !in existingR }.forEach { val r = if(it.status != "done") it.copy(status = "paused", activeSince = 0, segment = it.segment + 1) else it; dao.putRun(RunRow(r.id, gson.toJson(r))) }
        }
    }
    suspend fun erase() = mutex.withLock { db.withTransaction { dao.clearExercises(); dao.clearWorkouts(); dao.clearRuns() }; context.settingsStore.edit { it.clear() } }
    suspend fun recoverRun() { currentRun()?.let { r -> if(r.status == "active") updateRun(r.id) { it.copy(status = "paused", activeSince = 0, segment = it.segment + 1) } } } // Never silently count a service interruption as recorded running time.
}
class FitApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var repository: Repository
    lateinit var ready: Deferred<Unit>
    override fun onCreate() { super.onCreate(); val db = Room.databaseBuilder(this, FitDatabase::class.java, "fitprogress.db").build(); repository = Repository(db, this)
        ready = scope.async { repository.recoverRun() }
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
        Result.success()
    } catch (_: Exception) { Result.retry() }
}
