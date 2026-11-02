package ru.fitprogress.ai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.*

/** Renders real launch feedback without requesting location or fabricating GPS results. */
class RunStartUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Before fun prepare() {
        val app = ui.activity.application as FitApp
        check(app.isolatedTestStorage) { "Use FitTestRunner" }
        runBlocking {
            app.ready.await()
            app.repository.currentRun()?.let { r -> app.repository.updateRun(r.id) { RunTimers.finish(it, AndroidClock.read(app)) } }
        }
        RunService.launch.idle()
        ui.onNodeWithContentDescription("Бег", useUnmergedTree = true).performClick()
    }
    @After fun resetLaunchOnly() { RunService.launch.idle() }
    @Test fun serviceFailureShowsErrorAndRetryNextToStartControl() {
        ui.runOnIdle { RunService.launch.fail(RunLaunchError.SERVICE, "SecurityException") }
        ui.onNodeWithTag("page").performScrollToNode(hasText("Повторить запуск"))
        ui.onNodeWithText("Повторить запуск").assertIsDisplayed().assertIsEnabled()
        ui.onNodeWithText(RunLaunchError.SERVICE.message).assertIsDisplayed()
        ui.onNodeWithText("Код: SecurityException").assertIsDisplayed()
    }
    @Test fun pendingStartDisablesRepeatedTap() {
        ui.runOnIdle { RunService.launch.request(true) }
        ui.onNodeWithTag("page").performScrollToNode(hasText("Начать пробежку"))
        ui.onNodeWithText("Начать пробежку").assertIsNotEnabled()
        ui.onNodeWithText("Запускаем запись GPS…").assertIsDisplayed()
    }
}
