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
}
