package ru.fitprogress.ai

import org.junit.Assert.*
import org.junit.Test

class ExerciseSearchTest {
    private val library = listOf(
        Exercise(id = "x1", name = "Жим гантелей лёжа", category = "Грудь", muscles = "грудные мышцы, трицепс", equipment = "гантели", technique = "dumbbell bench press", builtIn = true),
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
    @Test fun localizedTypeLabelsUseStableSourceIdentifiers() {
        assertEquals("x1", index.filter(type = "Силовые").single().id)
        assertEquals("x1", index.filter(type = "СИЛОВЫЕ").single().id)
        assertEquals("x1", index.filter(type = "strength").single().id)
        assertTrue(index.filter(type = "Растяжка").isEmpty())
    }
    @Test fun categoryAndEquipmentFiltersIgnoreCaseAndYoVariants() {
        assertEquals("x1", index.filter(category = "грудь", equipment = "ГАНТЕЛИ").single().id)
        assertEquals("x2", index.filter(category = "КВАДРИЦЕПСЫ", equipment = "ШТАНГА").single().id)
        assertEquals(3, index.filter(category = "ВСЕ", equipment = "ЛЮБОЕ").size)
    }
    @Test fun partialMultiwordQueryRequiresAllWords() {
        assertTrue(index.filter("жим икры").isEmpty())
        assertEquals(3, index.size)
    }
    @Test fun filtersByMovementTypeAndBuiltInOrUserOrigin() {
        assertEquals("x1", index.filter(type = "strength", origin = "Встроенные").single().id)
        assertTrue(index.filter(type = "cardio").isEmpty())
        assertEquals(2, index.filter(origin = "Мои").size)
    }
}
