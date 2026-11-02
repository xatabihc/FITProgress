package ru.fitprogress.ai

import org.junit.Assert.*
import org.junit.Test

class ExerciseSearchTest {
    private val library = listOf(
        Exercise(id = "x1", name = "Жим гантелей лёжа", category = "Грудь", muscles = "грудные мышцы, трицепс", equipment = "гантели", technique = "dumbbell bench press"),
        Exercise(id = "x2", name = "Приседание со штангой", category = "Квадрицепсы", muscles = "quadriceps, glutes", equipment = "штанга", technique = "barbell squat"),
        Exercise(id = "x3", name = "Растяжка 90/90", category = "Растяжка", muscles = "hamstrings", equipment = "без оборудования", technique = "hamstring stretch")
    )
    private val index by lazy {
        ExerciseSourceMetadata.put("x1", ExerciseSourceInfo("Начинающий", "strength", "Dumbbell Bench Press", listOf("triceps", "pectorals")))
        ExerciseSearchIndex(library)
    }
    @Test fun matchesRussianAndEnglishNamesAndPartialWords() {
        assertEquals("x1", index.filter("гантел лёжа").single().id)
        assertEquals("x1", index.filter("dumbbell bench").single().id)
        assertEquals("x2", index.filter("barbell squ").single().id)
    }
    @Test fun foldsYoAndMatchesMusclesAndCategory() {
        assertEquals("x1", index.filter("грудь").single().id)
        assertEquals("x1", index.filter("triceps").single().id)
        assertEquals("x1", index.filter("лёжа").single().id)
    }
    @Test fun combinesCategoryAndEquipmentFilters() {
        assertEquals("x2", index.filter(category = "Квадрицепсы", equipment = "штанга").single().id)
        assertTrue(index.filter(category = "Грудь", equipment = "штанга").isEmpty())
    }
    @Test fun partialMultiwordQueryRequiresAllWords() {
        assertTrue(index.filter("жим икры").isEmpty())
        assertEquals(3, index.size)
    }
}
