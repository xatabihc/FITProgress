package ru.fitprogress.ai

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ExerciseLibraryTest {
    @Test fun bundlesUniqueOpenLicensedOfflineExerciseRecordsAndIndexesEnglish() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FitApp
        check(app.isolatedTestStorage) { "Use FitTestRunner; production data is never needed for this test" }
        val rows = withTimeout(15000) { app.repository.exercises.first { it.size >= 800 } }
        val open = rows.filter { it.id.startsWith("open-") }
        assertTrue(open.size >= 800)
        assertEquals(open.size, open.map { it.id }.distinct().size)
        assertTrue(open.all { it.builtIn && it.name.isNotBlank() && it.technique.isNotBlank() })
        assertEquals("Unlicense", OpenExerciseLibrary.LICENSE)
        val index = ExerciseSearchIndex(rows)
        assertTrue(index.filter("incline dumbbell curl").any { it.id.startsWith("open-") })
        assertTrue(index.filter("hamstrings").isNotEmpty())
        assertTrue(index.filter(category = "Грудь", equipment = "гантели").isNotEmpty())
    }
    @Test fun russianGymNamesPreserveSourceIdsAndEnglishSearch() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FitApp
        check(app.isolatedTestStorage)
        val rows = withTimeout(15000) { app.repository.exercises.first { it.size >= 800 } }
        val byId = rows.associateBy { it.id }
        assertEquals("Жим штанги лёжа узким хватом", byId["open-Close-Grip_Barbell_Bench_Press"]?.name)
        assertEquals("Выпады с гантелями", byId["open-Dumbbell_Lunges"]?.name)
        assertEquals("Скручивания на верхнем блоке", byId["open-Cable_Crunch"]?.name)
        val index = ExerciseSearchIndex(rows)
        assertTrue(index.filter("Cable Crunch").any { it.id == "open-Cable_Crunch" })
        assertTrue(index.filter("жим штанги лёжа узким").any { it.id == "open-Close-Grip_Barbell_Bench_Press" })
    }
    @Test fun strengthFilterIncludesAllStrengthRecordsFromBundledSource() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FitApp
        check(app.isolatedTestStorage)
        val rows = withTimeout(15000) { app.repository.exercises.first { it.size >= 800 } }
        val source = app.assets.open("exercises-open.json").bufferedReader().use { com.google.gson.JsonParser.parseReader(it).asJsonObject.getAsJsonArray("exercises") }
        val expected = source.filter { it.asJsonObject.get("category").asString == "strength" }.map { "open-" + it.asJsonObject.get("id").asString }.toSet()
        val actual = ExerciseSearchIndex(rows).filter(type = "strength").map { it.id }.toSet()
        assertEquals("Strength category must include compound and isolation movements", expected.size, actual.size)
        assertEquals(expected, actual)
    }

}
