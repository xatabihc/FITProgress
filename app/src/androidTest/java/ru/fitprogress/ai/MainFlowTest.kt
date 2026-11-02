package ru.fitprogress.ai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MainFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Before fun reset() {
        lateinit var app: FitApp
        ui.activityRule.scenario.onActivity { activity ->
            app = activity.application as FitApp
            check(app.isolatedTestStorage) { "Refusing to erase production data: use FitTestRunner" }
            app.stopService(android.content.Intent(app, RunService::class.java))
            app.stopService(android.content.Intent(app, WorkoutTimerService::class.java))
        }
        ui.waitForIdle()
        runBlocking {
            app.ready.await()
            val stamp = AndroidClock.read(app)
            app.repository.snapshot().workouts.filter { it.status in listOf("active", "paused") }.forEach { w -> app.repository.updateWorkout(w.id) { SessionTimers.finish(it, stamp) } }
            app.repository.currentRun()?.let { r -> app.repository.updateRun(r.id) { RunTimers.finish(it, stamp) } }
            app.repository.erase()
        }
        ui.waitForIdle()
    }
    private fun scroll(text: String) { ui.onNodeWithTag("page").performScrollToNode(hasText(text, substring = false)) }
    @Test fun createAndRenameExercise() {
        ui.onNodeWithContentDescription("Каталог").performClick()
        ui.onNodeWithText("Создать упражнение").performClick()
        ui.onNodeWithText("Название").performTextInput("Мой присед")
        scroll("Сохранить упражнение"); ui.onNodeWithText("Сохранить упражнение").performClick(); ui.waitForIdle()
        scroll("Мой присед"); ui.onNodeWithText("Мой присед").performClick()
        ui.onNodeWithText("Название").performTextReplacement("Присед с паузой")
        scroll("Сохранить упражнение"); ui.onNodeWithText("Сохранить упражнение").performClick(); ui.waitForIdle()
        scroll("Присед с паузой"); ui.onNodeWithText("Присед с паузой").assertIsDisplayed()
    }
    @Test fun createExecuteSaveAndReopenWorkout() {
        scroll("Создать тренировку"); ui.onNodeWithText("Создать тренировку").performClick(); ui.waitForIdle()
        scroll("Добавить упражнение"); ui.onNodeWithText("Добавить упражнение").performClick()
        ui.onNodeWithText("Отжимания").performClick()
        scroll("Начать тренировку"); ui.onNodeWithText("Начать тренировку").performClick(); ui.waitForIdle()
        scroll("Текущий подход: 1")
        ui.onAllNodesWithContentDescription("Отметить подход выполненным")[0].performClick()
        ui.onNodeWithText("Выполнено", useUnmergedTree = true).performClick(); ui.waitForIdle()
        scroll("Завершить и сохранить"); ui.onNodeWithText("Завершить и сохранить").performClick()
        ui.onNodeWithText("Сохранить результат").performClick(); ui.waitForIdle()
        scroll("Тренировка сохранена"); ui.onNodeWithText("Тренировка сохранена").assertIsDisplayed()
        ui.activityRule.scenario.recreate(); ui.waitForIdle()
        scroll("Тренировка сохранена"); ui.onNodeWithText("Тренировка сохранена").assertIsDisplayed()
    }
    @Test fun emptyGpsScreenDoesNotGenerateDistance() {
        ui.onNodeWithContentDescription("Бег").performClick()
        ui.onNode(hasText("0.00 км") or hasText("0,00 км")).assertIsDisplayed()
        ui.onNodeWithText("Начать пробежку").assertExists()
    }
    @Test fun runRouteBuilderCanOpenAndReturnWithoutGpsPermission() {
        ui.onNodeWithContentDescription("Бег").performClick()
        ui.onNodeWithText("Бег в твоём ритме").assertIsDisplayed()
        ui.onNodeWithTag("page").performScrollToNode(hasText("Построить маршрут"))
        ui.onNodeWithText("Построить маршрут").performClick()
        ui.onNodeWithText("Планировщик маршрута").assertIsDisplayed()
        ui.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
        ui.onNodeWithText("Бег в твоём ритме").assertIsDisplayed()
    }
    @Test fun createAndApplyUserProgram() {
        scroll("Программы"); ui.onNodeWithText("Программы").performClick()
        scroll("Создать свою программу"); ui.onNodeWithText("Создать свою программу").performClick(); ui.waitForIdle()
        scroll("Добавить занятие / восстановление"); ui.onNodeWithText("Добавить занятие / восстановление").performClick()
        ui.onNodeWithText("Добавить упражнение").performClick(); ui.onNodeWithText("Отжимания").performClick()
        ui.onNodeWithText("Сохранить день").performClick(); ui.waitForIdle()
        scroll("Просмотреть и применить будущий план"); ui.onNodeWithText("Просмотреть и применить будущий план").performClick()
        ui.onNodeWithText("Подтвердить изменения").performClick(); ui.waitForIdle()
        ui.onNodeWithContentDescription("План").performClick(); ui.waitForIdle()
        scroll("Тренировка"); ui.onNodeWithText("Тренировка").assertIsDisplayed()
    }

    @Test fun createGoalAndDiarySurviveActivityRecreation() {
        scroll("Персональные цели"); ui.onNodeWithText("Персональные цели").performClick(); ui.waitForIdle()
        ui.onNodeWithText("Добавить цель").performClick()
        ui.onNodeWithText("Название").performTextInput("Три тренировки")
        ui.onNodeWithText("Сохранить цель").performClick(); ui.waitForIdle()
        scroll("Три тренировки"); ui.onNodeWithText("Три тренировки").assertIsDisplayed()
        ui.activityRule.scenario.recreate(); ui.waitForIdle()
        scroll("Три тренировки"); ui.onNodeWithText("Три тренировки").assertIsDisplayed()
        ui.onNodeWithContentDescription("Главная").performClick(); ui.waitForIdle()
        scroll("Дневник самочувствия"); ui.onNodeWithText("Дневник самочувствия").performClick()
        ui.onNodeWithText("Заполнить дневник").performClick(); ui.onNodeWithText("Сохранить дневник").performClick(); ui.waitForIdle()
        scroll("Изменить запись"); ui.onNodeWithText("Изменить запись").assertIsDisplayed()
    }

}
