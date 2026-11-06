package ru.fitprogress.ai

import org.junit.Assert.*
import org.junit.Test

class ProgramInterchangeTest {
    @Test fun parsesVersionedJsonWithProgressionAcrossWeeks() {
        val p = ProgramInterchange.parseJson("""{"schemaVersion":1,"name":"База","direction":"Зал","level":"Новичок","weeks":2,"days":[{"week":1,"weekday":1,"title":"A","exercises":[{"name":"Приседания","sets":3,"reps":8,"weightKg":20,"restSeconds":90}]},{"week":2,"weekday":1,"title":"A","exercises":[{"name":"Приседания","sets":3,"reps":9}]}]}""")
        assertEquals(2, p.days.size); assertEquals(9, p.days.last().exercises.single().reps); assertEquals(60, p.days.last().exercises.single().restSeconds)
    }
    @Test fun rejectsUnknownJsonVersionAndUnsafeParameters() {
        assertThrows(IllegalArgumentException::class.java) { ProgramInterchange.parseJson("""{"schemaVersion":2}""") }
        val p = ImportedProgram("x", "x", "x", 1, listOf(ImportedProgramDay(1, 1, "A", listOf(ImportedProgramExercise("Squat", 100, 8)))))
        assertThrows(IllegalArgumentException::class.java) { ProgramInterchange.validate(p) }
    }
    @Test fun parsesQuotedCsvAndGroupsExercisesIntoSessions() {
        val p = ProgramInterchange.parseCsv("""week,weekday,session,exercise,sets,reps,weight_kg,seconds,rest_seconds
1,1,"Тяга, день A",Приседания,3,8,20,0,90
1,1,"Тяга, день A",Планка,3,0,0,30,30""")
        assertEquals("Импортированная программа", p.name); assertEquals(1, p.days.size); assertEquals("Тяга, день A", p.days.single().title); assertEquals(2, p.days.single().exercises.size)
    }
    @Test fun csvMayOmitOptionalLoadAndTimerColumns() {
        val p = ProgramInterchange.parseCsv("week,weekday,session,exercise,sets,reps\n1,2,A,Планка,2,1")
        val e = p.days.single().exercises.single(); assertEquals(0.0, e.weightKg, 0.0); assertEquals(60, e.restSeconds)
    }
    @Test fun rejectsMalformedCsvAndDuplicateDayDefinitions() {
        assertThrows(IllegalArgumentException::class.java) { ProgramInterchange.parseCsv("week,exercise\n1,Squat") }
        val d = ImportedProgramDay(1, 1, "A", listOf(ImportedProgramExercise("Squat", 3, 8)))
        assertThrows(IllegalArgumentException::class.java) { ProgramInterchange.validate(ImportedProgram("x", "", "", 1, listOf(d, d.copy(exercises = d.exercises.toList())))) }
    }
    @Test fun acceptsDistanceAndIndividualSetsWithoutInventedRepetitions() {
        val p = ProgramInterchange.parseJson(
            """{"schemaVersion":1,"name":"Рубеж12","weeks":1,"days":[{"week":1,"weekday":2,
            "title":"Интервальный бег","exercises":[{"name":"Интервалы 400 м","sets":2,
            "reps":0,"distanceMeters":400,"target":"2 × 400 м, отдых 90 с",
            "setDetails":[{"reps":0,"distanceMeters":400,"restSeconds":90},
                          {"reps":0,"distanceMeters":450,"restSeconds":90}]}]}]}"""
        )
        val e = p.days.single().exercises.single()
        assertEquals(400.0, e.distanceMeters, 0.0)
        assertEquals(2, e.setDetails.size)
        assertEquals(450.0, e.setDetails[1].distanceMeters, 0.0)
        assertEquals(0, e.setDetails[0].reps)
        assertEquals("2 × 400 м, отдых 90 с", e.target)
    }
    @Test fun rejectsLostOrInvalidIndividualSetDetails() {
        val broken = ImportedProgramExercise("бег", 3, 0, setDetails = listOf(
            ImportedProgramSet(distanceMeters = 400.0)))
        assertThrows(IllegalArgumentException::class.java) {
            ProgramInterchange.validate(ImportedProgram("Рубеж12", "", "", 1,
                listOf(ImportedProgramDay(1, 2, "бег", listOf(broken)))))
        }
        val nan = ImportedProgramExercise("бег", 1, 0, distanceMeters = Double.NaN)
        assertThrows(IllegalArgumentException::class.java) {
            ProgramInterchange.validate(ImportedProgram("Рубеж12", "", "", 1,
                listOf(ImportedProgramDay(1, 2, "бег", listOf(nan)))))
        }
    }


    @Test fun individualPrescriptionSurvivesBackupWithOriginalNameAndNoCompletedSets() {
        val source = ImportedProgramExercise("Исходное название", 2, 0, restSeconds = 75,
            target = "Исходная цель", note = "Примечание", setDetails = listOf(
                ImportedProgramSet(reps = 7, weightKg = 22.5, restSeconds = 90, note = "Первый"),
                ImportedProgramSet(seconds = 45, distanceMeters = 300.0, restSeconds = 120, note = "Второй")))
        val canonical = Exercise(name = "Сопоставленное название", category = "Другие")
        val item = ProgramInterchange.item(source, canonical)
        val workout = Workout(name = "План", date = "2026-11-02", items = listOf(item))
        val decoded = BackupCodec.decode(BackupCodec.checkedExport(Backup(workouts = listOf(workout))))
            .workouts.single()
        assertEquals(workout, decoded)
        assertEquals(canonical.id, item.exerciseId); assertEquals(source.name, item.name)
        assertEquals("Исходная цель\nПримечание", item.notes)
        assertEquals(22.5, item.sets[0].weight, 0.0)
        assertEquals(300.0, item.sets[1].distanceMeters, 0.0)
        assertEquals(120, item.sets[1].restSeconds)
        assertTrue(item.sets.none { it.done }); assertEquals("planned", decoded.status)
    }
    @Test fun rejectsFractionalIntegersRatherThanTruncatingSourceData() {
        for(value in listOf("1.5", "4294967297")) {
            assertThrows(IllegalArgumentException::class.java) {
                ProgramInterchange.parseJson("""{"schemaVersion":1,"name":"План","weeks":1,
                    "days":[{"week":1,"weekday":1,"exercises":[{"name":"Бег","sets":$value,"seconds":30}]}]}""")
            }
        }
    }
}
