package ru.fitprogress.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ImportTest {
    private fun row(status: String = "completed", extra: String = "") = """{"id":"source-1","name":"Сила","date":"2026-11-04","status":"$status","exercises":[{"name":"Отжимания","sets":[{"reps":12,"weight":"2,5","done":true},{"reps":8}]}]$extra}"""
    @Test fun preservesResultsAndMatchesCatalog() {
        val p = WorkoutImport.parse("[${row()}]", Catalog.exercises)
        assertEquals(0, p.skipped); assertTrue(p.backup.exercises.isEmpty())
        val w = p.backup.workouts.single(); assertEquals("done", w.status)
        assertEquals(Catalog.exercises.first().id, w.items.single().exerciseId)
        assertEquals(2.5, w.items.single().sets.first().weight, 0.0)
        assertEquals(1, w.completedSets()); assertEquals(30.0, w.volume(), 0.0)
    }
    @Test fun deterministicIdsProtectRepeatedImportsAndJsonKeyOrder() {
        val first = WorkoutImport.parse("[${row()}]", Catalog.exercises)
        val second = WorkoutImport.parse("{\"trainings\":[${row()}]}", Catalog.exercises)
        assertEquals(first.backup, second.backup)
        assertEquals(1, WorkoutImport.parse("[${row()},${row()}]", Catalog.exercises).skipped)
    }
    @Test fun unknownExerciseIsRetainedAndCanBeMapped() {
        val text = "[${row().replace("Отжимания", "Мой жим")}]"
        val p = WorkoutImport.parse(text, Catalog.exercises)
        assertEquals("Мой жим", p.backup.exercises.single().name)
        val mapped = WorkoutImport.parse(text, Catalog.exercises, mapOf("Мой жим" to "builtin-1"))
        assertTrue(mapped.backup.exercises.isEmpty()); assertEquals("builtin-1", mapped.backup.workouts.single().items.single().exerciseId)
    }
    @Test fun invalidRowIsReportedWithoutDiscardingOtherWorkouts() {
        val p = WorkoutImport.parse("[${row()},${row().replace("source-1", "bad").replace("2026-11-04", "not-date")}]", Catalog.exercises)
        assertEquals(1, p.skipped); assertEquals(1, p.issues.size); assertEquals(1, p.backup.workouts.size)
    }
    @Test fun unsupportedStatusAndMissingDatesAreNeverInvented() {
        assertThrows(IllegalArgumentException::class.java) { WorkoutImport.parse("[${row("nonsense")}]", Catalog.exercises) }
        assertThrows(IllegalArgumentException::class.java) { WorkoutImport.parse("[${row().replace("\"date\":\"2026-11-04\",", "")}]", Catalog.exercises) }
    }
    @Test fun preservesFuturePlansAndUnfinishedResults() {
        assertEquals("planned", WorkoutImport.parse("[${row("planned").replace("2026-11-04", "2027-01-10")}]", Catalog.exercises).backup.workouts.single().status)
        val paused = WorkoutImport.parse("[${row("active")}]", Catalog.exercises).backup.workouts.single()
        assertEquals("paused", paused.status); assertEquals(1, paused.completedSets()); assertNull(paused.timing)
    }
    @Test fun rejectsFractionalRepetitionsAndNonFiniteWeight() {
        for(text in listOf(row().replace("\"reps\":12", "\"reps\":1.5"), row().replace("2,5", "NaN"))) {
            assertThrows(IllegalArgumentException::class.java) { WorkoutImport.parse("[$text]", Catalog.exercises) }
        }
    }
    @Test fun readLimitIsEnforcedBeforeAllocatingWholeFile() {
        assertEquals("тест", readLimitedUtf8(ByteArrayInputStream("тест".toByteArray()), 8))
        assertThrows(IllegalArgumentException::class.java) { readLimitedUtf8(ByteArrayInputStream(ByteArray(100000)), 8000) }
    }
    @Test fun gpsSignalGapStartsNewSegmentWithoutDistanceOrPace() {
        val a = GeoPoint(56.0, 60.0, 5f, 100000)
        val b = a.copy(lat = 56.001, time = 130000)
        val r = Gps.append(Run(points = listOf(a)), b, b.time)
        assertEquals(0.0, r.distance, 0.0); assertEquals(1, r.segment); assertEquals(2, r.points.size)
        assertNull(Gps.currentPace(r.points, b.time)); assertNull(Gps.bestTime(r.points, 50.0))
    }
    @Test fun gpsDuplicateAndPoorAccuracyAfterGapAreRejected() {
        val a = GeoPoint(56.0, 60.0, 5f, 100000); val r = Run(points = listOf(a))
        assertEquals(r, Gps.append(r, a, a.time))
        assertEquals(r, Gps.append(r, a.copy(time = 140000, accuracy = 100f), 140000))
    }
    @Test fun allBuiltInExercisesCanBeSavedAsUserCopies() {
        Catalog.exercises.forEach { BackupCodec.validateExercise(it.copy(id = newId(), builtIn = false)) }
    }
    @Test fun copiedProgramIsIndependentAndRetainsSchedule() {
        val day = PlanDay(items = listOf(Training.item(Catalog.exercises.first())))
        val source = TrainingPlan(days = listOf(day))
        val copy = PlanBuilder.duplicate(source)
        assertNotEquals(source.id, copy.id); assertNotEquals(day.id, copy.days.single().id)
        assertNotEquals(day.items.single().sets.first().id, copy.days.single().items.single().sets.first().id)
        assertEquals(source.startDate, copy.startDate); assertEquals(day.offset, copy.days.single().offset)
        assertEquals(day.items.single().sets.first().reps, copy.days.single().items.single().sets.first().reps)
    }

}
