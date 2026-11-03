package ru.fitprogress.ai

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DomainTest {
    private fun exercise() = Exercise(name = "Тест", category = "Ноги", sets = 3, reps = 12, weight = 20.0)
    private fun workout() = Workout(name = "Сила", date = "2026-10-26", items = listOf(Training.item(exercise())))
    private fun done(pain: Boolean = false, effort: Int = 5, recovery: Int = 8) = workout().copy(status = "done", pain = pain, effort = effort, recovery = recovery)
    @Test fun createExerciseAndPreserveDefaults() { val e = exercise(); BackupCodec.validateExercise(e); assertTrue(e.id.isNotBlank()); assertEquals(3, Training.item(e).sets.size) }
    @Test fun editExerciseKeepsIdentity() { val e = exercise(); val changed = e.copy(name = "Новое имя", reps = 8); assertEquals(e.id, changed.id); assertEquals("Новое имя", changed.name); assertEquals(12, e.reps) }
    @Test fun invalidExerciseIsRejected() { for(e in listOf(exercise().copy(name = ""), exercise().copy(weight = Double.NaN), exercise().copy(sets = 0), exercise().copy(rest = -1))) { assertThrows(IllegalArgumentException::class.java) { BackupCodec.validateExercise(e) } } }
    @Test fun addingSetsDoesNotChangeExisting() { val item = Training.item(exercise()); val added = item.copy(sets = item.sets + SetEntry(reps = 7, weight = 30.0)); assertEquals(4, added.sets.size); assertEquals(3, item.sets.size); assertEquals(7, added.sets.last().reps) }
    @Test fun onlyCompletedSetsContributeToVolume() { val w = workout(); val i = w.items[0]; val result = w.copy(items = listOf(i.copy(sets = listOf(SetEntry(reps = 10, weight = 20.0, done = true), SetEntry(reps = 8, weight = 25.0, done = true), SetEntry(reps = 100, weight = 100.0))))); assertEquals(400.0, result.volume(), 0.001); assertEquals(2, result.completedSets()) }
    @Test fun timerPauseResumeAndFinish() { val w = Training.resume(workout(), 1000); val paused = Training.pause(w, 6000); assertEquals(5000, paused.elapsed(999999)); val resumed = Training.resume(paused, 10000); val finished = Training.finish(resumed, 13000); assertEquals(8000, finished.elapsed(999999)); assertEquals("done", finished.status) }
    @Test fun restAndIntervalPauseKeepRemainingTime() { val w = Training.resume(workout(), 1000).copy(restUntil = 21000, timerUntil = 11000, timerSetId = "a"); val p = Training.pause(w, 6000); assertEquals(15000, p.restRemaining); assertEquals(5000, p.timerRemaining); val r = Training.resume(p, 100000); assertEquals(115000, r.restUntil); assertEquals(105000, r.timerUntil) }
    @Test fun clockRollbackCannotMakeNegativeDuration() { assertEquals(0, Training.resume(workout(), 10000).elapsed(1)) }
    @Test fun duplicationResetsResultsAndIds() { val source = done().copy(items = listOf(Training.item(exercise()).copy(sets = listOf(SetEntry(done = true))))); val copy = Training.duplicate(source); assertNotEquals(source.id, copy.id); assertNotEquals(source.items[0].id, copy.items[0].id); assertFalse(copy.items[0].sets[0].done); assertEquals("planned", copy.status); assertEquals(0, copy.started) }
    @Test fun copyWeekPreservesHistoryAndOffsets() { val w = done().copy(date = "2026-10-28"); val copies = Training.copyWeek(listOf(w, workout().copy(template = true), workout().copy(date = "2026-11-02")), LocalDate.parse("2026-10-26"), LocalDate.parse("2026-11-09")); assertEquals(1, copies.size); assertEquals("2026-11-11", copies[0].date); assertEquals("done", w.status); assertEquals("2026-10-28", w.date) }
    @Test fun programsHaveConcreteSchedulesAndRecoveryDays() { assertEquals(25, Catalog.programs.size); assertEquals(Catalog.programs.size, Catalog.programs.map { it.id }.distinct().size); for(p in Catalog.programs) { val plan = Catalog.schedule(p, LocalDate.parse("2026-11-02")); assertEquals(p.weeks * p.weekdays.size, plan.size); assertTrue(plan.all { it.items.isNotEmpty() && LocalDate.parse(it.date).dayOfWeek.value in p.weekdays }); assertTrue(p.weekdays.size <= 4); assertTrue(p.progression.isNotBlank()); assertTrue(p.advice.isNotBlank()) } }
    @Test fun newProgramsHaveProgressiveWeeksAndDeloadWithoutChangingBaseDefinitions() {
        val start = LocalDate.parse("2026-11-02")
        val ids = listOf("home-strength-2", "gym-foundation-3", "calisthenics-steady-2", "functional-steady-3")
        ids.forEach { id ->
            val p = Catalog.programs.single { it.id == id }
            val calendar = Catalog.schedule(p, start)
            assertEquals(p.weekdays.size * p.weeks, calendar.size)
            assertTrue(calendar.all { it.items.isNotEmpty() })
            assertEquals(p.reps, calendar.first().items.first().sets.first().reps)
            val thirdWeek = calendar.first { it.date == start.plusDays(14 + p.weekdays.first().toLong() - 1).toString() }
            assertEquals(p.reps + 1, thirdWeek.items.first().sets.first().reps)
            val lastWeek = calendar.first { it.date == start.plusDays((p.weeks - 1) * 7L + p.weekdays.first() - 1).toString() }
            assertEquals(p.sets - 1, lastWeek.items.first().sets.size)
            assertTrue(lastWeek.items.all { item -> item.sets.all { !it.done } })
            assertEquals(p.reps, Catalog.programs.single { it.id == id }.reps)
        }
    }
    @Test fun advancedPlansKeepDifferentDaysAndOfferDeload() {
        val start = LocalDate.parse("2026-11-02")
        val ids = listOf("gym-upper-lower-advanced", "calisthenics-advanced-control", "home-advanced-strength")
        for(id in ids) {
            val plan = Catalog.programs.single { it.id == id }
            assertEquals("Продвинутый", plan.level)
            assertEquals(plan.weekdays.size, plan.dayExercises.size)
            assertEquals(plan.weekdays.size, plan.dayExercises.distinct().size)
            val schedule = Catalog.schedule(plan, start)
            assertEquals(plan.weeks * plan.weekdays.size, schedule.size)
            assertTrue(schedule.all { it.items.isNotEmpty() && it.items.all { item -> item.sets.none { it.done } } })
            assertEquals(plan.sets, schedule.first().items.first().sets.size)
            assertEquals(plan.reps, schedule.first().items.first().sets.first().reps)
            val last = schedule.first { it.date == start.plusDays((plan.weeks - 1) * 7L + plan.weekdays.first() - 1).toString() }
            assertEquals(plan.sets - 1, last.items.first().sets.size)
            assertTrue(last.items.first().sets.first().reps > plan.reps)
        }
    }
    @Test fun strengthProgramDaysUseDistinctTemplatesAndKeepHistorySeparate() { val p = Catalog.programs.first { it.id == "Тренажёрный зал-1" }; assertEquals(4, p.dayExercises.size); assertNotEquals(p.dayExercises[0], p.dayExercises[1]); val plan = Catalog.schedule(p, LocalDate.parse("2026-11-02")); assertNotEquals(plan[0].items.map { it.name }, plan[1].items.map { it.name }); assertTrue(plan.all { it.items.all { item -> item.sets.all { set -> !set.done } } }) }
    @Test fun retiredProgramIdsRemainReadableForExistingHistory() { assertTrue(Catalog.programTitle("Бег-2").contains("сохранённый план")); assertEquals("Подготовка к 5 км · общий план", Catalog.programTitle("running-4")) }
    @Test fun runningProgramContainsWarmupWorkRecoveryCooldown() { val p = Catalog.programs.first { it.id == "running-0" }; val w = Catalog.schedule(p, LocalDate.parse("2026-11-02")).first(); assertEquals(300, w.items.first().sets.first().seconds); assertEquals(300, w.items.last().sets.first().seconds); assertEquals(14, w.items.size) }
    @Test fun haversineDistanceMatchesKnownEquatorialArc() { val a = GeoPoint(0.0, 0.0, 5f, 1000); val b = a.copy(lon = 0.001); assertEquals(111.195, Gps.distance(a, b), 0.1); assertEquals(Gps.distance(a, b), Gps.distance(b, a), 0.0001) }
    @Test fun paceAndSpeedUseCorrectUnits() { assertEquals(5.0, Gps.pace(1000.0, 300000)!!, 0.001); assertEquals(12.0, Gps.speed(1000.0, 300000), 0.001); assertNull(Gps.pace(0.0, 300000)); assertEquals(0.0, Gps.speed(1000.0, 0), 0.0) }
    @Test fun gpsRejectsNoiseJumpsStaleAndOutOfOrder() { val a = GeoPoint(0.0, 0.0, 5f, 100000); assertFalse(Gps.accept(a, a.copy(lon = 1.0, time = 105000), 105000)); assertFalse(Gps.accept(a, a.copy(accuracy = 100f, time = 105000), 105000)); assertFalse(Gps.accept(a, a.copy(time = 99999), 100000)); assertFalse(Gps.accept(null, a, 200000)); assertFalse(Gps.accept(null, a.copy(lat = Double.NaN), 100000)) }
    @Test fun gpsAcceptsPlausibleMovement() { val a = GeoPoint(0.0, 0.0, 5f, 100000); val b = a.copy(lon = 0.0001, time = 105000); assertTrue(Gps.accept(a, b, 105000)); val r = Gps.append(Run(started = 100000, points = listOf(a)), b, 105000); assertEquals(11.1195, r.distance, 0.1) }
    @Test fun pauseSegmentDoesNotBridgeDistance() { val a = GeoPoint(0.0, 0.0, 5f, 100000); val b = a.copy(lon = 1.0, time = 105000, segment = 1); val r = Gps.append(Run(points = listOf(a), segment = 1), b, 105000); assertEquals(0.0, r.distance, 0.0); assertEquals(2, r.points.size) }
    @Test fun gpsRecoversAfterLongSignalLossWithoutInventingDistance() { val a = GeoPoint(0.0, 0.0, 5f, 100000); val b = a.copy(lon = 1.0, time = 300000); val r = Gps.append(Run(points = listOf(a)), b, 300000); assertEquals(0.0, r.distance, 0.0); assertEquals(1, r.segment); assertEquals(2, r.points.size) }
    @Test fun signalGapOverThirtySecondsDoesNotInventStraightLineDistance() {
        val first = GeoPoint(0.0, 0.0, 5f, 100_000, elapsedMs = 1000, boot = 1)
        val returned = first.copy(lon = 0.001, time = 145_000, elapsedMs = 46_000)
        val afterReturn = returned.copy(lon = 0.0011, time = 150_000, elapsedMs = 51_000)
        val start = Run(points = listOf(first), distance = 0.0, segment = 0)
        val gap = Gps.append(start, returned, returned.time)
        assertEquals(0.0, gap.distance, 0.0)
        assertEquals(1, gap.segment)
        assertEquals(1, gap.points.last().segment)
        val continued = Gps.append(gap, afterReturn, afterReturn.time)
        assertEquals(1, continued.points.last().segment)
        assertEquals(11.1195, continued.distance, 0.1)
    }
    @Test fun shortGpsGapCountsPlausibleProgressNormally() {
        val first = GeoPoint(0.0, 0.0, 5f, 100_000)
        val next = first.copy(lon = 0.0002, time = 120_000)
        val result = Gps.append(Run(points = listOf(first)), next, next.time)
        assertEquals(0, result.segment)
        assertEquals(22.239, result.distance, 0.1)
    }
    @Test fun noProgressionWhenPainFatigueOrPoorRecovery() { for(w in listOf(done(pain = true), done(effort = 9), done(recovery = 3))) { val choices = LocalRecommendations().suggest(listOf(w, done()), emptyList()); assertFalse(choices.any { it.factor != null && it.factor > 1 }); assertTrue(choices.any { it.factor == 0.8 }) } }
    @Test fun progressionRequiresAdherenceAndEvidence() { assertFalse(LocalRecommendations().suggest(emptyList(), emptyList()).any { it.factor == 1.05 }); assertTrue(LocalRecommendations().suggest(listOf(done(), done()), emptyList()).any { it.factor == 1.05 }) }
    @Test fun recommendationPreviewDoesNotMutateHistory() { val source = done(); val preview = LocalRecommendations().preview(listOf(source), LocalDate.parse("2026-10-26"), LocalDate.parse("2026-11-02"), 1.05); assertEquals(20.0, source.items[0].sets[0].weight, 0.0); assertEquals(21.0, preview.first().items[0].sets[0].weight, 0.0) }
    @Test fun backupRoundTripPreservesActualResultsAndRoute() { val e = exercise(); val w = done().copy(items = listOf(Training.item(e))); val r = Run(status = "done", points = listOf(GeoPoint(56.0, 60.0, 5f, 1000))); val b = Backup(exercises = listOf(e), workouts = listOf(w), runs = listOf(r)); assertEquals(b, BackupCodec.decode(BackupCodec.encode(b))) }
    @Test fun corruptedBackupIsRejected() { val encoded = BackupCodec.encode(Backup(exercises = listOf(exercise()))); assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(encoded.replace("Тест", "Взлом")) } }
    @Test fun duplicateBackupIdsAreRejected() { val e = exercise(); assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(BackupCodec.encode(Backup(exercises = listOf(e, e)))) } }
    @Test fun csvEscapesQuotesAndPreventsSpreadsheetFormula() { val w = done().copy(name = "=HYPERLINK(\"bad\")", items = listOf(Training.item(exercise()))); val csv = BackupCodec.csv(Backup(workouts = listOf(w))); assertTrue(csv.contains("\"'=HYPERLINK(\"\"bad\"\")\"")); assertEquals(5, csv.split("\r\n").size) }
    @Test fun largeHistoryCanBeCopiedWithoutChangingSource() { val source = (0 until 10000).map { workout().copy(date = LocalDate.parse("2020-01-01").plusDays(it.toLong()).toString()) }; val copy = Training.copyWeek(source, LocalDate.parse("2026-10-26"), LocalDate.parse("2026-11-02")); assertEquals(7, copy.size); assertEquals(10000, source.size) }
    @Test fun gpsSplitsUseEqualDistanceAndCorrectPace() {
        val points = (0..20).map { GeoPoint(0.0, it * 0.0001, 5f, 100000L + it * 5000L) }
        val splits = Gps.splits(points)
        assertEquals(2, splits.size); assertEquals(100.0, splits[0].distanceMeters, 0.001)
        assertEquals(200.0, splits[1].distanceMeters, 0.001)
        assertEquals(7.494, splits[0].minutesPerKm, 0.01)
    }
    @Test fun bestTimeInterpolatesTargetAndDoesNotCrossPause() {
        val points = (0..20).map { GeoPoint(0.0, it * 0.0001, 5f, 100000L + it * 5000L) }
        assertEquals(44966.0, Gps.bestTime(points, 100.0)!!.toDouble(), 2.0)
        assertNull(Gps.bestTime(points.mapIndexed { i, p -> p.copy(segment = i / 5) }, 100.0))
    }
    @Test fun currentPaceUsesRecentWindowAndRejectsStaleSignal() {
        val points = (0..5).map { GeoPoint(0.0, it * 0.0001, 5f, 100000L + it * 5000L) }
        assertNotNull(Gps.currentPace(points, 125000)); assertNull(Gps.currentPace(points, 200000))
    }
    @Test fun recoveryDaysDoNotReduceTrainingAdherence() {
        val choices = LocalRecommendations().suggest(listOf(done(), done(), workout().copy(recoveryDay = true)), emptyList())
        assertTrue(choices.any { it.factor == 1.05 })
    }

    private fun stamp(wall: Long = 100000, elapsed: Long = 1000, boot: Long = 4) = ClockStamp(wall, elapsed, boot)
    @Test fun monotonicWorkoutIgnoresWallClockChanges() {
        val w = SessionTimers.resume(workout(), stamp())
        assertEquals(5000, SessionTimers.elapsed(w, stamp(wall = 999999999, elapsed = 6000)))
        assertEquals(5000, SessionTimers.elapsed(w, stamp(wall = 1, elapsed = 6000)))
    }
    @Test fun monotonicRestAndStagePauseAndResume() {
        val start = stamp(); val set = SetEntry(seconds = 30)
        val w = SessionTimers.startStage(SessionTimers.startRest(SessionTimers.resume(workout(), start), 60, start), set, start)
        val p = SessionTimers.pause(w, stamp(elapsed = 11000))
        assertEquals(50000, p.restRemaining); assertEquals(20000, p.timerRemaining)
        val r = SessionTimers.resume(p, stamp(elapsed = 100000))
        assertEquals(40000, SessionTimers.rest(r, stamp(elapsed = 110000))); assertEquals(10000, SessionTimers.stage(r, stamp(elapsed = 110000)))
    }
    @Test fun monotonicCheckpointDoesNotDoubleCount() {
        val w = SessionTimers.resume(workout(), stamp())
        val checkpoint = SessionTimers.checkpoint(w, stamp(elapsed = 6000))
        assertEquals(9000, SessionTimers.elapsed(checkpoint, stamp(elapsed = 10000)))
        assertEquals(9000, SessionTimers.finish(checkpoint, stamp(elapsed = 10000)).accumulatedMs)
    }
    @Test fun changedBootRecoversPausedAtDurableCheckpoint() {
        val w = SessionTimers.startRest(SessionTimers.resume(workout(), stamp()), 60, stamp())
        val saved = SessionTimers.checkpoint(w, stamp(elapsed = 6000))
        val recovered = SessionTimers.recover(saved, stamp(elapsed = 100, boot = 5))
        assertEquals("paused", recovered.status); assertEquals(5000, recovered.accumulatedMs); assertEquals(55000, recovered.restRemaining)
        assertEquals(55000, SessionTimers.rest(SessionTimers.resume(recovered, stamp(boot = 5)), stamp(boot = 5)))
    }
    @Test fun importedTimersHaveNoForeignElapsedAnchors() {
        val w = SessionTimers.checkpoint(SessionTimers.startRest(SessionTimers.resume(workout(), stamp()), 30, stamp()), stamp(elapsed = 6000))
        val imported = SessionTimers.forImport(w)
        assertEquals("paused", imported.status); assertNull(imported.timing); assertEquals(25000, imported.restRemaining)
    }
    @Test fun duplicateCompleteDoesNotOverwriteResultsOrRestartRest() {
        val w = SessionTimers.resume(workout(), stamp()); val i = w.items.first(); val set = i.sets.first()
        val once = SessionEdits.complete(w, i.id, set.copy(reps = 8), stamp(elapsed = 2000))
        val twice = SessionEdits.complete(once, i.id, set.copy(reps = 99), stamp(elapsed = 8000))
        assertEquals(once, twice); assertEquals(8, twice.items.first().sets.first().reps)
    }
    @Test fun completedExerciseCannotBeReplacedDuringSession() {
        val w = SessionTimers.resume(workout(), stamp()); val i = w.items.first()
        val next = SessionEdits.complete(w, i.id, i.sets.first(), stamp())
        assertThrows(IllegalArgumentException::class.java) { SessionEdits.replace(next, i.id, Catalog.exercises.first()) }
    }
    @Test fun sessionAllowsAddingAndReorderingWhileActive() {
        val w = SessionTimers.resume(workout().copy(items = listOf(Training.item(exercise()), Training.item(Catalog.exercises.first()))), stamp())
        val added = SessionEdits.addSet(w, w.items.first().id); assertEquals(4, added.items.first().sets.size)
        val moved = SessionEdits.move(added, w.items.first().id, 1); assertEquals(w.items.first().id, moved.items.last().id)
    }
    @Test fun copyPastResultPreservesAlreadyCompletedSets() {
        val w = workout(); val i = w.items.first(); val first = i.sets.first().copy(done = true, reps = 5)
        val current = w.copy(items = listOf(i.copy(sets = listOf(first, i.sets[1]))))
        val past = w.copy(id = newId(), status = "done", ended = 1234, items = listOf(i.copy(sets = listOf(SetEntry(reps = 20, weight = 50.0, done = true)))))
        val copy = SessionEdits.lastResult(current, i.id, listOf(past))
        assertEquals(first, copy.items.first().sets.first()); assertEquals(20, copy.items.first().sets[1].reps); assertFalse(copy.items.first().sets[1].done)
    }
    @Test fun expandedSetFieldsRoundTrip() {
        val e = exercise(); val s = SetEntry(type = "distance", distanceMeters = 400.0, restSeconds = 90, rpe = 8, rir = 2, comment = "Ровно")
        val w = workout().copy(items = listOf(Training.item(e).copy(sets = listOf(s))))
        assertEquals(s, BackupCodec.decode(BackupCodec.encode(Backup(exercises = listOf(e), workouts = listOf(w)))).workouts.first().items.first().sets.first())
    }
    @Test fun invalidRpeAndDistanceAreRejected() {
        listOf(SetEntry(rpe = 11), SetEntry(rir = -1), SetEntry(distanceMeters = Double.NaN), SetEntry(restSeconds = -1), SetEntry(type = "unknown")).forEach { set -> assertThrows(IllegalArgumentException::class.java) { SessionEdits.validate(set) } }
    }
    @Test fun neutralCoachPreviewIsActuallyNeutral() {
        val timed = workout().copy(items = listOf(Training.item(exercise()).copy(sets = listOf(SetEntry(reps = 10, weight = 0.0), SetEntry(reps = 1, seconds = 60)))))
        val copy = LocalRecommendations().preview(listOf(timed), LocalDate.parse("2026-10-26"), LocalDate.parse("2026-11-02"), 1.0).first()
        assertEquals(timed.items.first().sets.map { it.reps to it.seconds }, copy.items.first().sets.map { it.reps to it.seconds })
    }
    private fun plan() = TrainingPlan(name = "Цикл", weeks = 4, startDate = "2026-11-02", days = listOf(PlanDay(offset = 0, name = "Сила", items = listOf(Training.item(exercise()))), PlanDay(offset = 2, name = "Отдых", recovery = true)))
    @Test fun programDefinitionAndScheduleRoundTrip() {
        val p = plan(); PlanBuilder.validate(p); val b = Backup(workouts = listOf(PlanBuilder.definition(p)))
        assertEquals(p, BackupCodec.decode(BackupCodec.encode(b)).workouts.first().plan)
        val rows = PlanBuilder.schedule(p); assertEquals(listOf("2026-11-02", "2026-11-04"), rows.map { it.date }); assertTrue(rows.last().recoveryDay)
    }
    @Test fun programSupportsOneAndFiftyTwoWeeks() { PlanBuilder.validate(plan().copy(weeks = 1)); PlanBuilder.validate(plan().copy(weeks = 52)); assertThrows(IllegalArgumentException::class.java) { PlanBuilder.validate(plan().copy(weeks = 53)) } }
    @Test fun programWeekCopyUsesIndependentIds() {
        val p = plan(); val copied = PlanBuilder.copyWeek(p, 0, 3)
        assertEquals(4, copied.days.size); assertEquals(listOf(21, 23), copied.days.filter { it.offset >= 21 }.map { it.offset }); assertNotEquals(p.days[0].id, copied.days[2].id)
        assertNotEquals(p.days[0].items[0].sets[0].id, copied.days[2].items[0].sets[0].id)
    }
    @Test fun programShiftOnlyChangesDefinitionStart() { val p = plan(); val shifted = PlanBuilder.shift(p, 7); assertEquals("2026-11-09", shifted.startDate); assertEquals(p.days, shifted.days); assertEquals("2026-11-02", p.startDate) }
    @Test fun deloadReducesSetsOnlyOnce() { val p = plan(); val d = PlanBuilder.deload(p, 0); assertEquals(2, d.days[0].items[0].sets.size); assertEquals(d, PlanBuilder.deload(d, 0)); assertEquals(3, p.days[0].items[0].sets.size) }
    @Test fun programEditsPreserveCompletedActiveAndPastSessions() {
        val p = plan(); val scheduled = PlanBuilder.schedule(p)
        val completed = scheduled.first().copy(status = "done"); val changed = PlanBuilder.shift(p, 7)
        val diff = PlanBuilder.preview(changed, listOf(completed, scheduled.last()), LocalDate.parse("2026-11-01"))
        assertTrue(diff.inserts.isEmpty()); assertEquals(1, diff.updates.size); assertFalse(diff.deleteIds.contains(completed.id)); assertEquals("2026-11-11", diff.updates.first().date)
        val active = scheduled.first().copy(status = "active"); assertTrue(PlanBuilder.preview(changed, listOf(active), LocalDate.parse("2026-11-01")).updates.isEmpty())
    }
    @Test fun applyingProgramTwiceCreatesNoDuplicateSlots() { val p = plan(); val existing = PlanBuilder.schedule(p); val diff = PlanBuilder.preview(p, existing, LocalDate.parse("2026-11-01")); assertTrue(diff.inserts.isEmpty()); assertEquals(existing.map { it.id }.toSet(), diff.updates.map { it.id }.toSet()) }
    @Test fun deletingFutureProgramDayNeverDeletesPastHistory() { val p = plan(); val existing = PlanBuilder.schedule(p); val diff = PlanBuilder.preview(p.copy(days = emptyList()), existing, LocalDate.parse("2026-11-03")); assertEquals(listOf(existing.last().id), diff.deleteIds) }
    @Test fun runningClockIgnoresWallTimeAndPausedTime() {
        val r = RunTimers.resume(Run(status = "paused", activeSince = 0), stamp()); val p = RunTimers.pause(r, stamp(wall = 1, elapsed = 6000))
        assertEquals(5000, p.accumulatedMs); val done = RunTimers.finish(RunTimers.resume(p, stamp(elapsed = 100000)), stamp(elapsed = 105000)); assertEquals(10000, done.accumulatedMs)
    }

    @Test fun manualTreadmillIsNeverRepresentedAsGps() {
        val r = ManualRuns.create(5000.0, 1800000, 10000000, 10000000)
        assertEquals("manual", r.recordingMode); assertTrue(r.points.isEmpty()); assertEquals(5000.0, r.distance, 0.0)
        assertEquals("done", r.status); assertNull(Gps.bestTime(r.points, 1000.0))
    }
    @Test fun manualTreadmillRejectsFutureAndInvalidDistance() {
        assertThrows(IllegalArgumentException::class.java) { ManualRuns.create(Double.NaN, 10000, 100000, 100000) }
        assertThrows(IllegalArgumentException::class.java) { ManualRuns.create(1000.0, 10000, 200000, 100000) }
    }
    @Test fun versionOneBackupStillImportsAndFutureVersionIsRejected() {
        val legacy = Backup(version = 1, workouts = listOf(workout()))
        assertEquals(1, BackupCodec.decode(BackupCodec.encode(legacy)).version)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(BackupCodec.encode(Backup(version = 99))) }
    }
    @Test fun duplicateSetIdentifiersAreRejected() {
        val set = SetEntry(); val w = workout().copy(items = listOf(Training.item(exercise()).copy(sets = listOf(set, set))))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validateWorkout(w) }
    }

    @Test fun programCannotOverwriteAnUnrelatedWorkoutWithSameId() {
        val p = plan(); val colliding = PlanBuilder.schedule(p).first().copy(program = "unrelated", status = "done")
        assertThrows(IllegalArgumentException::class.java) { PlanBuilder.preview(p, listOf(colliding), LocalDate.parse("2026-11-01")) }
    }
    @Test fun trainingPlanRequiresExercisesOrExplicitRecovery() {
        assertThrows(IllegalArgumentException::class.java) { PlanBuilder.validate(plan().copy(days = listOf(PlanDay()))) }
        PlanBuilder.validate(plan().copy(days = listOf(PlanDay(recovery = true))))
    }

    @Test fun actualStatisticsDateUsesFinishRatherThanFutureSchedule() {
        val end = java.time.Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()
        val w = workout().copy(date = "2027-01-01", status = "done", ended = end)
        assertEquals(LocalDate.parse("2026-11-01"), w.completedDate(java.time.ZoneOffset.UTC))
        assertEquals("2027-01-01", w.date)
    }

}
