package ru.fitprogress.ai

import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.*

class StabilityTest {
    private val now = ClockStamp(100000, 1000, 1)
    @Test fun completedRunCannotBeReopened() { assertThrows(IllegalArgumentException::class.java) { RunTimers.resume(Run(status = "done"), now) } }
    @Test fun finishingRunTwiceDoesNotChangeItsHistory() { val run = RunTimers.finish(RunTimers.resume(Run(status = "paused"), now), now.copy(elapsedMs = 5000)); assertEquals(run, RunTimers.finish(run, now.copy(wallMs = 999999, elapsedMs = 999999))) }
    @Test fun deletingTimedExerciseCancelsItsDeadline() {
        val e = Training.item(Catalog.exercises.first()); val w = SessionTimers.startStage(SessionTimers.resume(Workout(items = listOf(e)), now), e.sets.first().copy(seconds = 60), now)
        val changed = SessionEdits.cancelMissingStage(w.copy(items = emptyList()))
        assertEquals("", changed.timerSetId); assertEquals(0L, changed.timing!!.stageDeadlineMs); assertEquals(0L, changed.timerRemaining)
    }
    @Test fun validTimedSetIsNotCancelledByReordering() {
        val e = Training.item(Catalog.exercises.first()); val second = Training.item(Catalog.exercises[1])
        val w = SessionTimers.startStage(SessionTimers.resume(Workout(items = listOf(e, second)), now), e.sets.first().copy(seconds = 60), now)
        val reordered = w.copy(items = w.items.reversed())
        assertEquals(reordered, SessionEdits.cancelMissingStage(reordered))
    }
    @Test fun importNeverKeepsACompletedWorkoutMonotonicAnchor() { val w = Workout(status = "done", accumulatedMs = 7000, timing = MonotonicTiming(1, 1000)); val imported = SessionTimers.forImport(w); assertNull(imported.timing); assertEquals(7000L, imported.accumulatedMs) }
    @Test fun queuedUiMutationsPreserveCallOrder() = runBlocking {
        val queue = OrderedMutations(); val gate = CompletableDeferred<Unit>(); val calls = mutableListOf<String>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { queue.run { calls += "set"; gate.await(); calls += "saved" } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { queue.run { calls += "finish" } }
        assertEquals(listOf("set"), calls); gate.complete(Unit); first.await(); second.await(); assertEquals(listOf("set", "saved", "finish"), calls)
    }
    @Test fun failedMutationDoesNotBlockFollowingSave() = runBlocking {
        val queue = OrderedMutations(); val result = runCatching { queue.run { error("disk") } }; assertTrue(result.isFailure)
        assertEquals("next", queue.run { "next" }); Unit
    }
    @Test fun cancelledWaitingMutationNeverRuns() = runBlocking {
        val queue = OrderedMutations(); val gate = CompletableDeferred<Unit>(); var touched = false
        val first = launch(start = CoroutineStart.UNDISPATCHED) { queue.run { gate.await() } }
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { queue.run { touched = true } }
        waiting.cancelAndJoin(); gate.complete(Unit); first.join(); assertFalse(touched); assertEquals(1, queue.run { 1 }); Unit
    }
}
