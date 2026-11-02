package ru.fitprogress.ai

import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

/** Real Android ViewModel/main dispatcher + an independent in-memory Room fixture. */
class ViewModelStateTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: FitApp
    private lateinit var db: FitDatabase
    private lateinit var repo: Repository
    private var store = ViewModelStore()
    private lateinit var vm: FitViewModel
    private fun newViewModel() { instrumentation.runOnMainSync { vm = FitViewModel(app, repo); store.put("fixture", vm) } }
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    @Before fun setup() {
        app = instrumentation.targetContext.applicationContext as FitApp
        check(app.isolatedTestStorage) { "Use FitTestRunner; production storage is forbidden" }
        db = Room.inMemoryDatabaseBuilder(app, FitDatabase::class.java).build(); repo = Repository(db, app); newViewModel()
    }
    @After fun close() { onMain { store.clear() }; db.close() }
    @Test fun completeAndFinishCannotLoseTheQueuedSet() = runBlocking {
        val w = Workout(items = listOf(Training.item(Catalog.exercises.first())))
        repo.save(w); val now = AndroidClock.read(app); repo.updateWorkout(w.id) { SessionTimers.resume(it, now) }
        val set = w.items.first().sets.first().copy(reps = 8, weight = 20.0)
        lateinit var saved: Job; lateinit var finished: Job
        onMain { saved = vm.completeSet(w, w.items.first().id, set.id, set); finished = vm.finish(w, 5, 8, false) }
        saved.join(); finished.join()
        val result = repo.workouts.first().single(); assertEquals("done", result.status); assertEquals(1, result.completedSets()); assertEquals(160.0, result.volume(), 0.0)
    }
    @Test fun freshViewModelRestoresPausedResultAndCountdown() = runBlocking {
        val w = Workout(items = listOf(Training.item(Catalog.exercises.first())))
        repo.save(w); repo.updateWorkout(w.id) { SessionTimers.resume(it, AndroidClock.read(app)) }
        lateinit var saved: Job; lateinit var paused: Job
        onMain { saved = vm.completeSet(w, w.items[0].id, w.items[0].sets[0].id, w.items[0].sets[0]); paused = vm.pause(w) }
        saved.join(); paused.join()
        val before = repo.workouts.first().single(); assertTrue(before.restRemaining > 0)
        onMain { store.clear(); store = ViewModelStore() }; newViewModel()
        val restored = withTimeout(10000) { vm.workouts.first { it.any { row -> row.id == w.id } }.single() }
        assertEquals(before, restored); assertEquals("paused", restored.status); assertEquals(1, restored.completedSets())
    }
    @Test fun malformedRowReportsFailureWithoutErasingOtherHistory() = runBlocking {
        val w = Workout(name = "Сохранённая история"); repo.save(w)
        val before = db.dao().workout(w.id)!!
        db.dao().putWorkout(WorkoutRow("broken-fixture", "{broken-json"))
        withTimeout(10000) { vm.loadFailure.filterNotNull().first() }
        lateinit var attempt: Job; onMain { attempt = vm.update(w.id) { it.copy(name = "Нельзя записывать поверх ошибки чтения") } }; attempt.join()
        assertEquals(before, db.dao().workout(w.id)); assertNotNull(db.dao().workout("broken-fixture"))
    }
    @Test fun exclusiveRejectsSecondOperationWhileFirstIsPending() = runBlocking {
        val gate = CompletableDeferred<Unit>(); var calls = 0
        onMain { vm.exclusive { gate.await(); calls++ }; vm.exclusive { calls += 100 } }
        assertTrue(vm.busy.value); gate.complete(Unit)
        withTimeout(10000) { vm.busy.first { !it } }; assertEquals(1, calls)
    }
}
