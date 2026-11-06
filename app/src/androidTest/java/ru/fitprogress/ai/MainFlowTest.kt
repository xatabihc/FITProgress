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
        awaitCompose(ui)
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
        RunService.launch.idle()
        ui.waitForIdle()
    }
    private fun awaitExerciseIndex(name: String) {
        lateinit var vm: FitViewModel
        ui.runOnIdle { vm = androidx.lifecycle.ViewModelProvider(ui.activity)[FitViewModel::class.java] }
        ui.waitUntil(10000) { vm.exerciseSearch.value.filter(name).any { it.name == name } }
    }
    private fun scroll(text: String) { ui.onNodeWithTag("page").performScrollToNode(hasText(text, substring = false)) }
    @Test fun createAndRenameExercise() {
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.nav_catalog), useUnmergedTree = true).performClick()
        scroll("Создать упражнение"); ui.onNodeWithText("Создать упражнение").performClick()
        scroll("Название"); ui.onNodeWithText("Название").performTextInput("Мой присед")
        scroll("Сохранить упражнение"); ui.onNodeWithText("Сохранить упражнение").performClick(); ui.waitForIdle()
        awaitExerciseIndex("Мой присед"); scroll("Мой присед"); ui.onNodeWithText("Мой присед").performClick()
        scroll("Название"); ui.onNodeWithText("Название").performTextReplacement("Присед с паузой")
        scroll("Сохранить упражнение"); ui.onNodeWithText("Сохранить упражнение").performClick(); ui.waitForIdle()
        awaitExerciseIndex("Присед с паузой"); scroll("Присед с паузой"); ui.onNodeWithText("Присед с паузой").assertIsDisplayed()
    }
    @Test fun createExecuteSaveAndReopenWorkout() {
        scroll("Создать тренировку"); ui.onNodeWithText("Создать тренировку").performClick(); ui.waitForIdle()
        scroll("Добавить упражнение"); ui.onNodeWithText("Добавить упражнение").performClick()
        ui.onAllNodesWithText("Отжимания")[0].performClick()
        scroll("Начать тренировку"); ui.onNodeWithText("Начать тренировку").performClick(); ui.waitForIdle()
        scroll("Текущий подход: 1")
        ui.onAllNodesWithContentDescription("Отметить подход выполненным")[0].performScrollTo().performClick()
        ui.onNodeWithText("Выполнено", useUnmergedTree = true).performClick(); ui.waitForIdle()
        scroll("Завершить и сохранить"); ui.onNodeWithText("Завершить и сохранить").performClick()
        ui.onNodeWithText("Сохранить результат").performClick(); ui.waitForIdle()
        scroll("Тренировка сохранена"); ui.onNodeWithText("Тренировка сохранена").assertIsDisplayed()
        ui.activityRule.scenario.recreate(); ui.waitForIdle()
        scroll("Тренировка сохранена"); ui.onNodeWithText("Тренировка сохранена").assertIsDisplayed()
    }
    @Test fun emptyGpsScreenDoesNotGenerateDistance() {
        ui.onNodeWithContentDescription("Бег", useUnmergedTree = true).performClick()
        ui.onNode(hasText("0.00 км") or hasText("0,00 км")).assertIsDisplayed()
        scroll("Начать пробежку"); ui.onNodeWithText("Начать пробежку").assertIsDisplayed()
    }
    @Test fun runRouteBuilderCanOpenAndReturnWithoutGpsPermission() {
        ui.onNodeWithContentDescription("Бег", useUnmergedTree = true).performClick()
        ui.onNodeWithText("Бег в твоём ритме").assertIsDisplayed()
        ui.onNodeWithTag("page").performScrollToNode(hasText("Построить маршрут"))
        ui.onNodeWithText("Построить маршрут").performClick()
        ui.onNodeWithText("Выберите точки на карте или найдите место").assertIsDisplayed()
        ui.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
        ui.onNodeWithText("Бег в твоём ритме").assertIsDisplayed()
    }
    @Test fun createAndApplyUserProgram() {
        scroll("Программы"); ui.onNodeWithText("Программы").performClick()
        scroll("Создать свою программу"); ui.onNodeWithText("Создать свою программу").performClick(); ui.waitForIdle()
        scroll("Добавить занятие / восстановление"); ui.onNodeWithText("Добавить занятие / восстановление").performClick()
        ui.onNodeWithText("Добавить упражнение").performScrollTo().performClick(); ui.onAllNodesWithText("Отжимания")[0].performClick()
        ui.onNodeWithText("Сохранить день").performClick(); ui.waitForIdle()
        scroll("Просмотреть и применить будущий план"); ui.onNodeWithText("Просмотреть и применить будущий план").performClick()
        ui.onNodeWithText("Подтвердить изменения").performClick(); ui.waitForIdle()
        ui.onNodeWithContentDescription("План", useUnmergedTree = true).performClick(); ui.waitForIdle()
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
        ui.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; ui.waitForIdle()
        scroll("Дневник самочувствия"); ui.onNodeWithText("Дневник самочувствия").performClick()
        ui.onNodeWithText("Заполнить дневник").performClick(); ui.onNodeWithText("Сохранить дневник").performClick(); ui.waitForIdle()
        scroll("Изменить запись"); ui.onNodeWithText("Изменить запись").assertIsDisplayed()
    }

    @Test fun homeTabAlwaysReturnsHomeFromNestedScreensRepeatedly() {
        repeat(3) {
            scroll("Программы"); ui.onNodeWithText("Программы").performClick()
            ui.onNodeWithContentDescription(ui.activity.getString(R.string.nav_catalog), useUnmergedTree = true).performClick()
            ui.onNodeWithContentDescription(ui.activity.getString(R.string.nav_home), useUnmergedTree = true).performClick()
            scroll("Создать тренировку"); ui.onNodeWithText("Создать тренировку").assertIsDisplayed()
            ui.onNodeWithContentDescription("Настройки").performClick()
            ui.onNodeWithContentDescription("На главную").performClick()
            scroll("Создать тренировку"); ui.onNodeWithText("Создать тренировку").assertIsDisplayed()
        }
    }

    @Test fun savedRunOpensResultWhenCurrentRunDisappears() {
        val app = ui.activity.application as FitApp
        ui.onNodeWithContentDescription("Бег", useUnmergedTree = true).performClick()
        repeat(2) {
            val draft = Run(status = "paused", activeSince = 0)
            runBlocking { app.repository.saveRun(draft) }
            ui.runOnIdle { RunService.launch.active() }
            ui.waitUntil(10000) { ui.onAllNodesWithText("Карта, темп и секундомер").fetchSemanticsNodes().isNotEmpty() }
            scroll("Завершить пробежку"); ui.onNodeWithText("Завершить пробежку").assertIsEnabled()
            runBlocking { app.repository.updateRun(draft.id) { RunTimers.finish(it, AndroidClock.read(app)) } }
            ui.runOnIdle { RunService.launch.idle() }
            ui.waitUntil(10000) { ui.onAllNodesWithText("Сохранённая пробежка").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Сохранённая пробежка").assertIsDisplayed()
            // Return through the previous active page to the original running tab.
            repeat(2) { ui.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; ui.waitForIdle() }
            ui.onNodeWithText("Бег в твоём ритме").assertIsDisplayed()
        }
    }

    @Test fun dailyStatisticsExcludeYesterdaysCompletedWorkout() {
        val app = ui.activity.application as FitApp
        val today = java.time.LocalDate.now()
        fun result(day: java.time.LocalDate): Workout {
            val end = day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + 7200000
            return Workout(name = "QA day boundary", date = day.toString(), status = "done", started = end - 60000, ended = end, accumulatedMs = 60000)
        }
        runBlocking { app.repository.restore(BackupCodec.decode(BackupCodec.encode(Backup(workouts = listOf(result(today), result(today.minusDays(1))))))) }
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.nav_progress), useUnmergedTree = true).performClick()
        ui.onNodeWithText("День").performClick().assertIsSelected()
        ui.onNodeWithText("1", substring = false).assertIsDisplayed()
        ui.onNodeWithText("2", substring = false).assertDoesNotExist()
    }

}
