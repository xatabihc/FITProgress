package ru.fitprogress.ai

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class RepositoryTest {
    private lateinit var db: FitDatabase
    private lateinit var repo: Repository
    @Before fun setup() { val context = InstrumentationRegistry.getInstrumentation().targetContext; db = Room.inMemoryDatabaseBuilder(context, FitDatabase::class.java).build(); repo = Repository(db, context) }
    @After fun cleanup() { db.close() }
    @Test fun exerciseCreateEditDelete() = runBlocking {
        val e = Exercise(name = "Тестовое", category = "Грудь")
        repo.save(e); assertEquals("Тестовое", repo.exercises.first().first { it.id == e.id }.name)
        repo.save(e.copy(name = "Новое название")); assertEquals("Новое название", repo.exercises.first().first { it.id == e.id }.name)
        repo.deleteExercise(e.id); assertFalse(repo.exercises.first().any { it.id == e.id }); assertEquals(Catalog.exercises.size, repo.exercises.first().size)
    }
    @Test fun resultsSurviveExerciseDeletionAndCannotBeOverwritten() = runBlocking {
        val e = Exercise(name = "Сохранённое"); repo.save(e)
        val w = Workout(items = listOf(Training.item(e))); repo.save(w)
        repo.updateWorkout(w.id) { Training.resume(it, 1000) }
        repo.updateWorkout(w.id) { it.copy(items = it.items.map { i -> i.copy(sets = listOf(SetEntry(reps = 8, weight = 20.0, done = true))) }) }
        repo.updateWorkout(w.id) { Training.finish(it, 5000) }
        repo.deleteExercise(e.id)
        val saved = repo.workouts.first().first { it.id == w.id }; assertEquals(160.0, saved.volume(), 0.0); assertEquals("Сохранённое", saved.items.first().name)
        assertTrue(runCatching { repo.save(saved.copy(name = "Порча истории")) }.isFailure)
        repo.updateWorkout(w.id) { it.copy(name = "Порча истории") }; assertEquals(w.name, repo.workouts.first().first { it.id == w.id }.name)
    }
    @Test fun duplicateImportDoesNotOverwriteExisting() = runBlocking {
        val e = Exercise(name = "Исходное"); repo.save(e)
        repo.restore(BackupCodec.decode(BackupCodec.encode(Backup(exercises = listOf(e.copy(name = "Из копии"))))))
        assertEquals("Исходное", repo.exercises.first().first { it.id == e.id }.name)
    }
    @Test fun databaseReopenPreservesWorkout() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext; val filename = "test-reopen-${newId()}.db"
        var disk = Room.databaseBuilder(context, FitDatabase::class.java, filename).build()
        try {
            val w = Workout(name = "После перезапуска", items = listOf(Training.item(Catalog.exercises.first())))
            Repository(disk, context).save(w); disk.close()
            disk = Room.databaseBuilder(context, FitDatabase::class.java, filename).build()
            assertEquals(w, Repository(disk, context).workouts.first().single())
        } finally { disk.close(); context.deleteDatabase(filename) }
    }
    @Test fun legacyPayloadLoadsWithoutDeletingHistory() = runBlocking {
        val e = Exercise(name = "Старая история"); val w = Workout(items = listOf(Training.item(e)))
        val json = BackupCodec.gson.toJsonTree(w).asJsonObject
        listOf("timing", "plan", "programDayId").forEach { json.remove(it) }
        json["items"].asJsonArray.forEach { item -> item.asJsonObject["sets"].asJsonArray.forEach { set -> listOf("type", "distanceMeters", "restSeconds", "rpe", "rir", "comment").forEach { set.asJsonObject.remove(it) } } }
        db.dao().putWorkout(WorkoutRow(w.id, json.toString()))
        val loaded = repo.workouts.first().single()
        assertEquals(w.id, loaded.id); assertEquals("normal", loaded.items.first().sets.first().type); assertNull(loaded.plan); assertNull(loaded.timing)
    }
    @Test fun programApplyIsAtomicAndPreservesCompletedSessions() = runBlocking {
        val p = TrainingPlan(startDate = java.time.LocalDate.now().toString(), days = listOf(PlanDay(items = listOf(Training.item(Catalog.exercises.first())))))
        repo.save(PlanBuilder.definition(p)); repo.applyPlan(p.id, p, java.time.LocalDate.now()); repo.applyPlan(p.id, p, java.time.LocalDate.now())
        assertEquals(2, repo.workouts.first().size)
        val session = repo.workouts.first().first { !it.template }
        val stamp = ClockStamp(100000, 1000, 1)
        repo.updateWorkout(session.id) { SessionTimers.resume(it, stamp) }; repo.updateWorkout(session.id) { SessionTimers.finish(it, stamp.copy(elapsedMs = 6000)) }
        val before = repo.workouts.first().first { it.id == session.id }
        val shifted = PlanBuilder.shift(p, 7); repo.save(PlanBuilder.definition(shifted)); repo.applyPlan(p.id, shifted, java.time.LocalDate.now())
        assertEquals(before, repo.workouts.first().first { it.id == session.id })
        assertEquals(2, repo.workouts.first().size)
    }

}
