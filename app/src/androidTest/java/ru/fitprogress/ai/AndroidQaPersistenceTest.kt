package ru.fitprogress.ai

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Host invokes seed/update/verify in separate instrumentation processes. Only isolated storage. */
class AndroidQaPersistenceTest {
    @Test fun isolatedHistoryAcrossApkUpdate() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("qaPhase")
        assumeTrue("Host-controlled persistence phase required", phase == "seed" || phase == "verify")
        val marker = requireNotNull(args.getString("qaMarker"))
        require(marker.matches(Regex("qa-[0-9a-f]{32}")))
        val app = instrumentation.targetContext.applicationContext as FitApp
        check(app.isolatedTestStorage)
        app.ready.await()
        val repo = app.repository
        if(phase == "seed") {
            check(repo.workouts.first().none { it.id == marker })
            val w = Workout(id = marker, name = "QA update marker", items = listOf(Training.item(Catalog.exercises.first())))
            repo.save(w)
            val stamp = AndroidClock.read(app)
            repo.updateWorkout(w.id) { SessionTimers.resume(it, stamp) }
            val item = w.items.first()
            repo.updateWorkout(w.id) { SessionEdits.complete(it, item.id, item.sets.first().copy(reps = 8, weight = 20.0), stamp) }
            repo.updateWorkout(w.id) { SessionTimers.finish(it, stamp) }
        }
        val stored = repo.workouts.first().single { it.id == marker }
        assertEquals("done", stored.status)
        assertEquals(1, stored.completedSets())
        assertEquals(160.0, stored.volume(), 0.0)
        assertEquals("QA update marker", stored.name)
    }
}
