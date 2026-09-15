package io.github.puvon.enetrend

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class PrivacyNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun privacyExplainsCurrentReadsAndReturnsToMainScreen() {
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("再確認").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("データの利用とプライバシー").performScrollTo().performClick()
        compose.onNodeWithText("許可後、Health Connect から必要なデータを読み取り、端末内で集計・表示します。", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Health Connect への書き込みは行いません。", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("閉じる").performScrollTo().performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("再確認").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("EneTrend").performScrollTo().assertIsDisplayed()
    }
}
