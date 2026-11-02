package ru.fitprogress.ai

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Bounded, semantic navigation. Fixtures are isolated by FitTestRunner. No permissions/settings. */
class AndroidQaTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Before fun requireIsolation() {
        check((ui.activity.application as FitApp).isolatedTestStorage)
    }
    @Test fun boundedNavigationAndRecreation() {
        val tabs = listOf(R.string.nav_catalog, R.string.nav_plan, R.string.nav_progress, R.string.nav_home).map { ui.activity.getString(it) }
        repeat(2) {
            tabs.forEach { tab ->
                ui.onNodeWithContentDescription(tab, useUnmergedTree = true).performClick()
                ui.onNodeWithTag("page").assertIsDisplayed()
                ui.onNodeWithTag("page").performTouchInput { swipeUp() }
                ui.onNodeWithTag("page").performTouchInput { swipeDown() }
            }
        }
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.nav_catalog), useUnmergedTree = true).performClick()
        ui.onNodeWithText("Каталог упражнений").assertIsDisplayed()
        ui.activityRule.scenario.recreate()
        ui.waitForIdle()
        // Recreation must leave a navigable screen; do not assume restoration of a selected tab.
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.nav_catalog), useUnmergedTree = true).performClick()
        ui.onNodeWithText("Каталог упражнений").assertIsDisplayed()
    }
    @Test fun catalogSearchNoResultsAndRecovery() {
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.nav_catalog), useUnmergedTree = true).performClick()
        ui.onNodeWithText("Поиск упражнения").performTextInput("QA_no_match_827361")
        ui.activityRule.scenario.onActivity { activity ->
            val manager = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            manager.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        ui.waitForIdle()
        ui.onNodeWithTag("page").performScrollToNode(hasText("Ничего не найдено. Измените фильтры или создайте своё упражнение."))
        ui.onNodeWithText("Ничего не найдено. Измените фильтры или создайте своё упражнение.").assertIsDisplayed()
        ui.onNodeWithTag("page").performScrollToNode(hasText("Поиск упражнения"))
        ui.onNodeWithText("Поиск упражнения").performTextClearance()
        ui.waitForIdle()
        ui.onNodeWithTag("page").performScrollToNode(hasText("Отжимания", substring = false))
        ui.onNodeWithText("Отжимания", substring = false).assertIsDisplayed()
    }
    @Test fun backgroundAndActivityRotationPreserveSearch() {
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.nav_catalog), useUnmergedTree = true).performClick()
        ui.onNodeWithText("Поиск упражнения").performTextReplacement("QA_orientation")
        ui.activityRule.scenario.onActivity { activity ->
            val manager = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            manager.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        ui.waitForIdle()
        ui.onNodeWithText("QA_orientation").assertExists()
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        ui.waitForIdle()
        ui.onNodeWithText("QA_orientation").assertExists()
        val original = ui.activity.requestedOrientation
        try {
            ui.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            ui.waitForIdle()
            ui.onNodeWithText("QA_orientation").assertExists()
        } finally {
            ui.activityRule.scenario.onActivity { it.requestedOrientation = original }
        }
    }
    @Test fun catalogTypeLabelsAreRussian() {
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.nav_catalog), useUnmergedTree = true).performClick()
        ui.onNodeWithText("strength").assertDoesNotExist()
        ui.onNodeWithText("cardio").assertDoesNotExist()
        ui.onNodeWithText("stretching").assertDoesNotExist()
        ui.onNodeWithText("Силовые").assertIsDisplayed().performClick()
        ui.onNodeWithText("Силовые").assertIsSelected()
    }
    @Test fun isolatedCatalogArtifacts() {
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.nav_catalog), useUnmergedTree = true).performClick()
        ui.onNodeWithText("Каталог упражнений").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.filesDir, "qa-artifacts").apply { mkdirs() }
        // Compose capture excludes notification/status bars and other apps.
        File(dir, "catalog.png").outputStream().use {
            ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val root = ui.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        // Compose hierarchy works without granting accessibility service privileges.
        var count = 0
        fun walk(node: SemanticsNode, depth: Int): String {
            if(depth > 30 || count++ > 1000) return ""
            val rect = node.boundsInRoot
            return "<node clickable=\"${node.config.contains(SemanticsActions.OnClick)}\" bounds=\"${rect.left},${rect.top},${rect.right},${rect.bottom}\">" +
                node.children.joinToString("") { walk(it, depth + 1) } + "</node>"
        }
        File(dir, "hierarchy.xml").writeText("<hierarchy source=\"compose-semantics\">" + walk(root, 0) + "</hierarchy>")
    }
}
