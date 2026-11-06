package ru.fitprogress.ai

import androidx.room.Room
import androidx.datastore.preferences.core.*
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
        val baseline = repo.exercises.first().map { it.id }.toSet()
        val e = Exercise(name = "Тестовое", category = "Грудь")
        repo.save(e); assertEquals("Тестовое", repo.exercises.first().first { it.id == e.id }.name)
        repo.save(e.copy(name = "Новое название")); assertEquals("Новое название", repo.exercises.first().first { it.id == e.id }.name)
        repo.deleteExercise(e.id); assertFalse(repo.exercises.first().any { it.id == e.id }); assertEquals(baseline, repo.exercises.first().map { it.id }.toSet())
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

    @Test fun roomMigrationFromVersionOnePreservesHistoryAndAddsPersonalRecords() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext; val filename = "migration-${newId()}.db"
        val w = Workout(name = "История до 2.1", items = listOf(Training.item(Catalog.exercises.first())))
        val payload = BackupCodec.gson.toJson(w)
        val legacy = context.openOrCreateDatabase(filename, 0, null)
        for(table in listOf("exercises", "workouts", "runs")) legacy.execSQL("CREATE TABLE IF NOT EXISTS `$table` (`id` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))")
        legacy.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        legacy.execSQL("INSERT OR REPLACE INTO room_master_table VALUES (42,'63d071eabbf9c1eb45970e5d513cfeca')")
        legacy.execSQL("INSERT INTO workouts VALUES (?,?)", arrayOf(w.id, payload)); legacy.version = 1; legacy.close()
        val migrated = Room.databaseBuilder(context, FitDatabase::class.java, filename).addMigrations(MIGRATION_1_2).build()
        try {
            val r = Repository(migrated, context)
            assertEquals(payload, migrated.dao().allWorkouts().single().payload)
            assertEquals(w, r.workouts.first().single())
            assertTrue(migrated.dao().allPersonal().isEmpty())
            val goal = SportGoal(title = "Три занятия"); r.saveGoal(goal)
            r.saveWellbeing(Wellbeing())
            assertEquals(goal, r.goals.first().single()); assertEquals(1, r.wellbeing.first().size)
        } finally { migrated.close(); context.deleteDatabase(filename) }
    }
    @Test fun personalBackupImportDoesNotOverwriteOrDuplicateRecords() = runBlocking {
        val goal = SportGoal(title = "Моя цель"); val entry = Wellbeing(comment = "Оригинал")
        repo.saveGoal(goal); repo.saveWellbeing(entry)
        val b = BackupCodec.decode(BackupCodec.encode(Backup(goals = listOf(goal.copy(title = "Из файла")), wellbeing = listOf(entry.copy(comment = "Из файла")))) )
        repo.restore(b); repo.restore(b)
        assertEquals(goal, repo.goals.first().single()); assertEquals(entry, repo.wellbeing.first().single())
    }
    @Test fun applyingCoachTwiceDoesNotDuplicateNextWeek() = runBlocking {
        val today = java.time.LocalDate.now(); val start = weekOf(today).minusWeeks(1)
        val old = Workout(date = start.toString(), items = listOf(Training.item(Catalog.exercises.first())))
        repo.save(old)
        val engine = AdaptiveCoach(); val snapshot = repo.snapshot(); val option = engine.options(snapshot.workouts, snapshot.runs, snapshot.wellbeing, today).first { it.id == "maintain" }
        val preview = engine.preview(option, snapshot.workouts, today)
        repo.applyCoach(preview, snapshot)
        val before = repo.snapshot(); assertEquals(2, before.workouts.size)
        assertTrue(runCatching { repo.applyCoach(preview, before) }.isFailure)
        assertEquals(before.workouts, repo.snapshot().workouts)
    }

    @Test fun oldDataStoreKeysRemainReadableAndUpdatesAreAtomic() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext; val previous = context.settingsStore.data.first()
        try {
            context.settingsStore.edit { it.clear(); it[stringPreferencesKey("name")] = "Имя из 2.0"; it[booleanPreferencesKey("light")] = true }
            val old = repo.profile.first(); assertEquals("Имя из 2.0", old.name); assertTrue(old.light); assertFalse(old.reminders)
            repo.updateProfile { it.copy(accent = "cyan") }; repo.updateProfile { it.copy(timerSound = false) }
            val updated = repo.profile.first(); assertEquals("cyan", updated.accent); assertFalse(updated.timerSound); assertEquals(old.name, updated.name); assertTrue(updated.light)
        } finally { context.settingsStore.updateData { previous } }
    }

    @Test fun savedRunIsImmutableAndNewRecordingUsesAnotherId() = runBlocking {
        val stamp = ClockStamp(100000, 1000, 1)
        val completed = RunTimers.finish(RunTimers.resume(Run(status = "paused"), stamp), stamp.copy(elapsedMs = 5000))
        repo.saveRun(completed); repo.saveRun(completed)
        assertTrue(runCatching { repo.saveRun(completed.copy(distance = 12345.0)) }.isFailure)
        val next = RunTimers.resume(Run(status = "paused"), stamp)
        repo.saveRun(next)
        assertTrue(runCatching { repo.saveRun(RunTimers.resume(Run(status = "paused"), stamp)) }.isFailure)
        val all = repo.runs.first(); assertEquals(2, all.size); assertEquals(completed, all.first { it.id == completed.id }); assertNotEquals(completed.id, repo.currentRun()!!.id)
    }

    @Test fun favoriteRoutePersistsInExistingPersonalRecordsWithoutChangingWorkoutHistory() = runBlocking {
        val planned = Workout(name = "Историческая тренировка")
        repo.save(planned)
        val clock = AndroidClock.read(InstrumentationRegistry.getInstrumentation().targetContext)
        repo.updateWorkout(planned.id) { SessionTimers.resume(it, clock) }
        repo.updateWorkout(planned.id) { SessionTimers.finish(it, clock) }
        val workout = repo.workouts.first().single()
        assertEquals("done", workout.status)
        val favorite = NavigationRoute(name = "Парк", mode = RouteMode.WALK.name, points = listOf(RouteCoordinate(55.0, 37.0), RouteCoordinate(55.001, 37.0)), distanceMeters = 111.2)
        repo.saveRoute(favorite)
        assertEquals(favorite, repo.savedRoutes.first().single())
        assertEquals(workout, repo.workouts.first().single())
        repo.deleteRoute(favorite.id)
        assertTrue(repo.savedRoutes.first().isEmpty())
        assertEquals(workout, repo.workouts.first().single())
    }

    @Test fun externalImportIsAtomicDeduplicatedAndRetainsUnfinishedSets() = runBlocking {
        val source = """[{"id":"one","name":"Импорт","date":"2026-11-04","status":"active","exercises":[{"name":"Моё движение","sets":[{"reps":8,"weight":20,"done":true}]}]}]"""
        val imported = WorkoutImport.parse(source, Catalog.exercises).backup
        assertEquals(1 to 0, repo.importWorkouts(imported))
        val saved = repo.workouts.first().single()
        assertEquals("planned", saved.status); assertEquals(160.0, saved.volume(), 0.0)
        assertEquals(0 to 1, repo.importWorkouts(imported))
        assertEquals(saved, repo.workouts.first().single())
        assertEquals(1, repo.exercises.first().count { it.id == imported.exercises.single().id })
        val invalid = imported.copy(workouts = imported.workouts + Workout(name = ""))
        assertTrue(runCatching { repo.importWorkouts(invalid) }.isFailure)
        assertEquals(saved, repo.workouts.first().single())
    }

}
