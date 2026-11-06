package ru.fitprogress.ai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** An Activity may be RESUMED before Samsung attaches its first Compose root. */
fun awaitCompose(rule: ComposeContentTestRule) {
    rule.waitUntil(15000) {
        runCatching { rule.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
    }
}
