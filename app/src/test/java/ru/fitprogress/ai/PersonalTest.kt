package ru.fitprogress.ai

import org.junit.Test
import org.junit.Assert.*
import java.time.*

class PersonalTest {
    private val today = LocalDate.parse("2026-11-16")
    private fun time(date: String) = LocalDate.parse(date).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun workout(date: String, weight: Double = 20.0, pain: Boolean = false, done: Boolean = true) = Workout(date = date, status = if(done) "done" else "planned", started = time(date) - 600000, ended = if(done) time(date) else 0, accumulatedMs = if(done) 600000 else 0, pain = pain, recovery = 8, effort = 5,
        items = listOf(Training.item(Catalog.exercises.first()).copy(sets = List(3) { SetEntry(reps = 10, weight = weight, done = done) })))
    private fun run(date: String, distance: Double = 2000.0, demo: Boolean = false, mode: String = "gps") = Run(started = time(date) - 600000, ended = time(date), status = "done", accumulatedMs = 600000, distance = distance, demo = demo, recordingMode = mode)
    private fun goal(kind: String = "sessions", target: Double = 3.0) = SportGoal(title = "Моя цель", kind = kind, target = target, from = "2026-11-09", until = "2026-11-15")
    private fun history() = listOf(workout("2026-11-02"), workout("2026-11-04"), workout("2026-11-09"), workout("2026-11-11"))
    private fun diary() = listOf(Wellbeing(date = "2026-11-10", energy = 8, fatigue = 2, sleep = 8, soreness = 1, recovery = 8))
    @Test fun goalCountsOnlyCompletedRealInPeriod() {
        val p = GoalTracker.calculate(goal(), listOf(workout("2026-11-09"), workout("2026-11-10", done = false), workout("2026-11-01")), listOf(run("2026-11-12"), run("2026-11-13", demo = true)), today)
        assertEquals(2.0, p.current, 0.0); assertFalse(p.achieved)
    }
    @Test fun gpsGoalDoesNotCountStrengthOrManualRuns() {
        val p = GoalTracker.calculate(goal().copy(source = "gps"), listOf(workout("2026-11-09")), listOf(run("2026-11-10"), run("2026-11-11", mode = "manual")), today)
        assertEquals(1.0, p.current, 0.0)
    }
    @Test fun distanceGoalUsesKilometersAndFiltersDemo() { val p = GoalTracker.calculate(goal("distance", 2.0), emptyList(), listOf(run("2026-11-12"), run("2026-11-13", demo = true)), today); assertTrue(p.achieved); assertEquals(2.0, p.current, 0.0) }
    @Test fun paceGoalRequiresAtLeastOneKilometer() { val p = GoalTracker.calculate(goal("pace", 5.0), emptyList(), listOf(run("2026-11-09", 500.0), run("2026-11-12")), today); assertTrue(p.achieved); assertEquals(5.0, p.current, .001) }
    @Test fun strengthGoalHonorsMinRepetitions() { val w = workout("2026-11-09"); val g = goal("strength", 20.0).copy(exerciseId = w.items.first().exerciseId, minReps = 11); assertFalse(GoalTracker.calculate(g, listOf(w), emptyList(), today).achieved) }
    @Test fun consistencyCountsSeparateCalendarWeeks() { val g = goal("consistency", 2.0).copy(from = "2026-11-02", weeklySessions = 2); assertEquals(2.0, GoalTracker.calculate(g, history(), emptyList(), today).current, 0.0) }
    @Test fun programGoalCountsOnlyProgramTrainingSlots() { val g = goal("program", 100.0).copy(programId = "p"); val done = workout("2026-11-09").copy(program = "p"); val planned = workout("2026-11-11", done = false).copy(program = "p"); assertEquals(50.0, GoalTracker.calculate(g, listOf(done, planned, done.copy(id = newId(), template = true)), emptyList(), today).current, .001) }
    @Test fun futureGoalHasZeroProgress() { val g = goal().copy(from = "2026-12-01", until = "2026-12-31"); val p = GoalTracker.calculate(g, history(), emptyList(), today); assertEquals(0.0, p.current, 0.0); assertTrue(p.explanation.contains("не начался")) }
    @Test fun emptyPeriodNeverFabricatesGoalAchievement() { val p = GoalTracker.calculate(goal("pace", 5.0), emptyList(), emptyList(), today); assertFalse(p.achieved); assertEquals(0f, p.fraction) }
    @Test fun invalidGoalAndWellbeingAreRejected() { assertThrows(IllegalArgumentException::class.java) { PersonalValidation.goal(goal().copy(target = Double.NaN)) }; assertThrows(IllegalArgumentException::class.java) { PersonalValidation.wellbeing(Wellbeing(energy = 11)) } }
    @Test fun newBackupRoundTripsGoalsAndDiary() { val b = Backup(goals = listOf(goal()), wellbeing = diary()); assertEquals(b, BackupCodec.decode(BackupCodec.encode(b))) }
    @Test fun duplicateDiaryDatesAreRejectedAtImport() { val d = diary().first(); assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(BackupCodec.encode(Backup(wellbeing = listOf(d, d)))) } }
    @Test fun weeklyReviewCountsSavedResultsAndExcludesDemoRuns() {
        val completed = workout("2026-11-09")
        val planned = workout("2026-11-11", done = false)
        val review = AdaptiveCoach().review(
            listOf(completed, planned, workout("2026-11-02")),
            listOf(run("2026-11-10", 2300.0), run("2026-11-12", demo = true)),
            diary(), today
        )
        assertEquals("2026-11-09", review.from)
        assertEquals("2026-11-15", review.until)
        assertEquals(2, review.plannedSessions)
        assertEquals(1, review.completedPlannedSessions)
        assertEquals(1, review.completedSessions)
        assertEquals(1, review.completedRuns)
        assertEquals(2.3, review.runningKm, .001)
        assertEquals(2, review.activeDays)
        assertEquals(1, review.diaryEntries)
        assertFalse(review.painReported)
    }
    @Test fun weeklyReviewReportsPainAndDoesNotIncludeOutsideWeek() {
        val review = AdaptiveCoach().review(
            listOf(workout("2026-11-09", pain = true), workout("2026-11-02")),
            emptyList(), emptyList(), today
        )
        assertTrue(review.painReported)
        assertEquals(1, review.completedSessions)
        assertEquals(0.0, review.runningKm, .0)
    }
    @Test fun sixCoachOptionsAreAlwaysExplained() { val choices = AdaptiveCoach().options(emptyList(), emptyList(), emptyList(), today); assertEquals(6, choices.size); assertTrue(choices.all { it.reason.isNotBlank() && it.metrics.isNotBlank() }); assertTrue(choices.single { it.id == "manual" }.enabled) }
    @Test fun coachProgressRequiresDiaryAndEvidence() { assertFalse(AdaptiveCoach().options(history(), emptyList(), emptyList(), today).single { it.id == "progress" }.enabled); assertTrue(AdaptiveCoach().options(history(), emptyList(), diary(), today).single { it.id == "progress" }.enabled) }
    @Test fun currentPainBlocksProgressEvenOutsideLastWeek() { val ds = diary() + Wellbeing(date = today.toString(), pain = true); assertFalse(AdaptiveCoach().options(history(), emptyList(), ds, today).single { it.id == "progress" }.enabled) }
    @Test fun fatiguePoorSleepAndRecoveryBlockProgress() { for(d in listOf(diary()[0].copy(fatigue = 8), diary()[0].copy(sleep = 3), diary()[0].copy(recovery = 2), diary()[0].copy(energy = 2))) assertFalse(AdaptiveCoach().options(history(), emptyList(), listOf(d), today).single { it.id == "progress" }.enabled) }
    @Test fun loadSpikeBlocksAdditionalProgression() { val h = history().map { if(it.date >= "2026-11-09") it.copy(items = it.items.map { i -> i.copy(sets = i.sets.map { s -> s.copy(weight = 40.0) }) }) else it }; assertFalse(AdaptiveCoach().options(h, emptyList(), diary(), today).single { it.id == "progress" }.enabled) }
    @Test fun runningSpikeAlsoBlocksProgression() { val rs = listOf(run("2026-11-02", 1000.0), run("2026-11-09", 2000.0)); assertFalse(AdaptiveCoach().options(history(), rs, diary(), today).single { it.id == "progress" }.enabled) }
    @Test fun neutralCoachPreviewPreservesLoadsAndSource() { val h = history(); val engine = AdaptiveCoach(); val option = engine.options(h, emptyList(), diary(), today).single { it.id == "maintain" }; val p = engine.preview(option, h, today); assertEquals("2026-11-23", p.from); assertEquals(2, p.workouts.size); assertEquals(20.0, p.workouts.first().items.first().sets.first().weight, 0.0); assertEquals("done", h.first().status) }
    @Test fun progressiveCoachChangesOnlyEligibleSets() { val h = history(); val engine = AdaptiveCoach(); val option = engine.options(h, emptyList(), diary(), today).single { it.id == "progress" }; val p = engine.preview(option, h, today); assertEquals(20.5, p.workouts.first().items.first().sets.first().weight, .001); assertTrue(p.differences.any { it.contains("20.5") }) }
    @Test fun repeatedExerciseUsesItsOwnDayActualResult() { val h = listOf(workout("2026-11-09", 20.0), workout("2026-11-11", 30.0)); val engine = AdaptiveCoach(); val option = engine.options(h, emptyList(), diary(), today).single { it.id == "repeat" }; assertEquals(listOf(20.0, 30.0), engine.preview(option, h, today).workouts.map { it.items.first().sets.first().weight }) }
    @Test fun recoveryPreviewAddsRestWithoutChangingHistory() { val h = history(); val engine = AdaptiveCoach(); val option = engine.options(h, emptyList(), diary(), today).single { it.id == "recovery" }; val p = engine.preview(option, h, today); assertEquals(1, p.workouts.count { it.recoveryDay }); assertEquals(4, h.count { it.status == "done" }) }
    @Test fun analyticsSeparatesMeasuredWeightsAndEstimatedStrength() { val w = workout("2026-11-09"); val a = Analytics.summarize(listOf(w), emptyList(), diary(), today.minusDays(7), today, Catalog.exercises); assertEquals(600.0, a.volume, .001); assertEquals(20.0, a.exercises.first().maxWeight, .001); assertTrue(a.exercises.first().estimated1Rm!! > 20.0); assertEquals(a.volume, a.muscleVolume.values.sum(), .001) }
    @Test fun analyticsSeparatesManualAndGpsAndIgnoresDemo() { val a = Analytics.summarize(emptyList(), listOf(run("2026-11-09"), run("2026-11-10", 3000.0, mode = "manual"), run("2026-11-11", demo = true)), emptyList(), today.minusDays(7), today, Catalog.exercises); assertEquals(2.0, a.gpsKm, .001); assertEquals(3.0, a.manualKm, .001); assertEquals(2, a.activeDays); assertNull(a.fatigue) }
    @Test fun gpsElapsedTimeSurvivesWallClockJump() { val a = GeoPoint(0.0, 0.0, 5f, 100000, elapsedMs = 1000, boot = 1); val b = a.copy(lon = .0001, time = 50000, elapsedMs = 6000); val r = Gps.appendAt(Run(points = listOf(a)), b, ClockStamp(50000, 6000, 1)); assertEquals(2, r.points.size); assertEquals(11.1195, r.distance, .01); assertNotNull(Gps.currentPaceAt(r.points, ClockStamp(50000, 6000, 1))) }
    @Test fun gpsRejectsStaleElapsedTimeEvenIfWallClockLooksFresh() { val p = GeoPoint(0.0, 0.0, 5f, 100000, elapsedMs = 1000, boot = 1); assertTrue(Gps.appendAt(Run(), p, ClockStamp(100000, 50000, 1)).points.isEmpty()) }
    @Test fun gpsBackupSupportsWallClockRollbackWithinOneBoot() { val points = listOf(GeoPoint(0.0, 0.0, 5f, 100000, elapsedMs = 1000, boot = 1), GeoPoint(0.0, .0001, 5f, 50000, elapsedMs = 6000, boot = 1)); val b = Backup(runs = listOf(Run(status = "done", points = points))); assertEquals(points, BackupCodec.decode(BackupCodec.encode(b)).runs.first().points) }
    @Test fun repeatingWeekWorksAcrossAllSupportedWeeks() {
        val p = TrainingPlan(weeks = 52, days = listOf(PlanDay(items = listOf(Training.item(Catalog.exercises.first())))))
        val result = PlanBuilder.repeatThroughEnd(p, 0)
        assertEquals(52, result.days.size); assertEquals(51 * 7, result.days.last().offset)
        assertEquals(52, result.days.map { it.id }.distinct().size); assertEquals(1, p.days.size)
    }
    @Test fun progressionIsBlockedAfterAnEmptyPreviousWeek() {
        val h = history().filter { it.date >= "2026-11-09" }
        assertFalse(AdaptiveCoach().options(h, emptyList(), diary(), today).single { it.id == "progress" }.enabled)
    }
    @Test fun measuredGpsTimePreservesBestSplitsDuringWallRollback() {
        val pts = (0..20).map { i -> GeoPoint(0.0, i * .0001, 5f, 100000L - i * 1000L, elapsedMs = 1000L + i * 5000L, boot = 1) }
        assertEquals(2, Gps.splits(pts).size); assertEquals(44966.0, Gps.bestTime(pts, 100.0)!!.toDouble(), 2.0)
    }

    @Test fun futureProgramGoalNeverCompletesBeforeItsPeriod() {
        val g = goal("program", 100.0).copy(from = "2026-12-01", until = "2026-12-31", programId = "p")
        val w = workout("2026-11-09").copy(date = "2026-12-02", program = "p")
        val result = GoalTracker.calculate(g, listOf(w), emptyList(), today)
        assertFalse(result.achieved); assertEquals(0.0, result.current, 0.0)
    }
    @Test fun coachRoundsSmallWeightsWithoutExceedingProgressionLimit() {
        val engine = AdaptiveCoach(); val option = engine.options(history(), emptyList(), diary(), today).single { it.id == "progress" }
        val source = history().map { it.copy(items = it.items.map { i -> i.copy(sets = i.sets.map { set -> set.copy(weight = .06) }) }) }
        val output = engine.preview(option, source, today).workouts.first().items.first().sets.first().weight
        assertTrue(output >= .06 && output <= .06 * 1.025)
    }
    @Test fun repeatUsesActualCompletionWeekRatherThanPlannedFutureDate() {
        val h = listOf(workout("2026-11-09").copy(date = "2026-12-01"))
        val engine = AdaptiveCoach(); val option = engine.options(h, emptyList(), diary(), today).single { it.id == "repeat" }
        assertTrue(option.enabled); assertEquals("2026-11-23", engine.preview(option, h, today).workouts.single().date)
    }
    @Test fun recoveryReplacesChronologicallyLastTrainingEvenWithReorderedRows() {
        val h = history().reversed(); val engine = AdaptiveCoach(); val option = engine.options(h, emptyList(), diary(), today).single { it.id == "recovery" }
        assertEquals("2026-11-25", engine.preview(option, h, today).workouts.single { it.recoveryDay }.date)
    }

    @Test fun workingWeightGoalExcludesWarmupSets() {
        val w = workout("2026-11-09"); val item = w.items.first(); val sets = item.sets + SetEntry(reps = 1, weight = 100.0, done = true, type = "warmup")
        val g = goal("strength", 100.0).copy(exerciseId = item.exerciseId)
        assertFalse(GoalTracker.calculate(g, listOf(w.copy(items = listOf(item.copy(sets = sets)))), emptyList(), today).achieved)
    }
    @Test fun hiddenActiveDemoOrManualRunCannotEnterBackup() {
        for(r in listOf(Run(demo = true), Run(recordingMode = "manual"))) assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(BackupCodec.encode(Backup(runs = listOf(r)))) }
    }
    @Test fun reminderPolicyRespectsTimeAndOptOut() {
        val now = today.atTime(17, 59)
        assertFalse(ReminderPolicy.shouldNotify(true, 18, now, null, 2)); assertTrue(ReminderPolicy.shouldNotify(true, 18, now.plusMinutes(1), null, 2))
        assertFalse(ReminderPolicy.shouldNotify(false, 18, now.plusHours(1), null, 2))
    }
    @Test fun reminderPolicyAvoidsDuplicatesAndEmptyPlan() {
        val now = today.atTime(20, 0)
        assertFalse(ReminderPolicy.shouldNotify(true, 18, now, today.toString(), 1)); assertFalse(ReminderPolicy.shouldNotify(true, 18, now, null, 0))
        assertTrue(ReminderPolicy.shouldNotify(true, 18, now, today.minusDays(1).toString(), 1))
    }
    @Test fun genuineVersionTwoBackupWithoutPersonalFieldsRemainsReadable() {
        val root = com.google.gson.JsonParser.parseString(BackupCodec.encode(Backup(version = 2, workouts = listOf(workout("2026-11-09"))))).asJsonObject
        listOf("goals", "wellbeing", "coachReceipts", "sha256").forEach { root.remove(it) }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(BackupCodec.gson.toJson(root).toByteArray()).joinToString("") { "%02x".format(it) }
        root.addProperty("sha256", hash)
        val result = BackupCodec.decode(root.toString()); assertEquals(1, result.workouts.size); assertTrue(result.goals.isEmpty()); assertTrue(result.wellbeing.isEmpty())
    }
    @Test fun volumeReductionRetainsWarmupAndOneWorkingSet() {
        val h = history().map { w -> w.copy(items = w.items.map { i -> i.copy(sets = listOf(SetEntry(type = "warmup", done = true), SetEntry(type = "warmup", done = true), SetEntry(type = "working", weight = 20.0, done = true))) }) }
        val engine = AdaptiveCoach(); val choice = engine.options(h, emptyList(), diary(), today).single { it.id == "reduce" }
        val sets = engine.preview(choice, h, today).workouts.first().items.first().sets
        assertEquals(2, sets.count { it.type == "warmup" }); assertTrue(sets.any { it.type == "working" })
    }

    @Test fun exportIsValidatedBeforeWritingAnUnrestorableFile() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.checkedExport(Backup(workouts = listOf(workout("2026-11-09").copy(name = "")))) }
        val valid = Backup(goals = listOf(goal()), wellbeing = diary())
        assertEquals(valid, BackupCodec.decode(BackupCodec.checkedExport(valid)))
    }
    @Test fun versionThreeRequiresPersonalTableArrays() {
        val root = com.google.gson.JsonParser.parseString(BackupCodec.encode(Backup())).asJsonObject
        root.remove("goals"); root.remove("sha256")
        root.addProperty("sha256", java.security.MessageDigest.getInstance("SHA-256").digest(BackupCodec.gson.toJson(root).toByteArray()).joinToString("") { "%02x".format(it) })
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(root.toString()) }
    }

}
