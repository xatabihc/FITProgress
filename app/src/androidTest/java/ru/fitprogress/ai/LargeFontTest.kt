package ru.fitprogress.ai

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Two times font size and both themes, without changing system settings. */
class LargeFontTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    @Test fun primaryScreensAndSettingsRemainScrollableWithLargeFontInBothThemes() {
        val app = ui.activity.application as FitApp
        check(app.isolatedTestStorage)
        val vm = FitViewModel(app)
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { FitProgress(vm) }
        }
        awaitCompose(ui)
        try {
            for(light in listOf(false, true)) {
                runBlocking { app.repository.updateProfile { it.copy(light = light) } }
                ui.onNodeWithContentDescription("Настройки").performClick()
                ui.onNodeWithTag("page").performScrollToNode(hasText("Экспорт резервной копии JSON"))
                ui.onNodeWithText("Экспорт резервной копии JSON").assertIsDisplayed().assertHasClickAction()
                ui.onNodeWithContentDescription("На главную").performClick()
                ui.onNodeWithTag("page").performScrollToNode(hasText("Создать тренировку"))
                ui.onNodeWithText("Создать тренировку").assertIsDisplayed()
                for(tab in listOf(R.string.nav_catalog, R.string.nav_plan, R.string.nav_progress, R.string.nav_home)) {
                    ui.onNodeWithContentDescription(ui.activity.getString(tab), useUnmergedTree = true).performClick()
                    ui.onNodeWithTag("page").assertIsDisplayed().performTouchInput { swipeUp() }
                }
            }
        } finally {
            // AndroidViewModel owns active Room collectors; clear via a store on main.
            ui.runOnIdle { androidx.lifecycle.ViewModelStore().apply { put("font", vm); clear() } }
        }
    }
}
