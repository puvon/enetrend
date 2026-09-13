package io.github.puvon.enetrend

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class HealthReconnectTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun retryReportsCompletionEvenWhenStateDoesNotChange() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("再確認").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("再確認").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("再確認しました。", substring = true)
                .fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("再確認に失敗しました。", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("再確認").assertIsDisplayed()
    }
}
