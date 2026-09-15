package io.github.puvon.enetrend

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.puvon.enetrend.health.*
import io.github.puvon.enetrend.ui.DashboardScreen
import io.github.puvon.enetrend.ui.HealthConnectionScreen
import io.github.puvon.enetrend.ui.theme.EnetrendTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DashboardFailureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readFailuresAreDistinctFromEmptyDataAndOfferRetry() {
        var state: DashboardState by mutableStateOf(DashboardState.Loading)
        var retries = 0
        compose.setContent { EnetrendTheme {
            DashboardScreen(state, 30, MovingAveragePeriod.SEVEN_DAYS, {}, {}, { retries++ }, {}, {})
        } }
        compose.onNodeWithText("データを読み込んでいます。").performScrollTo().assertIsDisplayed()
        val failures = listOf(
            HealthDataResult.PermissionsRequired(setOf("read")) to "読み取り権限がありません。",
            HealthDataResult.Unavailable to "この端末では Health Connect を利用できません。",
            HealthDataResult.UpdateRequired to "Health Connect の更新が必要です。",
            HealthDataResult.AccessDenied to "データへのアクセスが制限されています。",
            HealthDataResult.Error to "データを取得できませんでした。",
        )
        failures.forEach { (reason, message) ->
            compose.runOnIdle { state = DashboardState.Failed(reason) }
            compose.onNodeWithText(message, substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("この期間に表示できるデータがありません。").assertDoesNotExist()
            compose.onAllNodesWithContentDescription("統合グラフ。", substring = true).assertCountEquals(0)
            compose.onNodeWithText("再確認").performScrollTo().performClick()
        }
        compose.runOnIdle { assertEquals(failures.size, retries) }
    }

    @Test fun connectionCheckingUnavailableAndDeniedStatesHaveRecoveryActions() {
        var state: HealthConnectionState by mutableStateOf(HealthConnectionState.Checking)
        var requests = 0
        var retries = 0
        compose.setContent { EnetrendTheme {
            HealthConnectionScreen(state, false, { requests++ }, { retries++ }, {}, {}, {})
        } }
        compose.onNodeWithText("接続と権限を確認しています。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("再確認").assertDoesNotExist()
        compose.runOnIdle { state = HealthConnectionState.Unavailable }
        compose.onNodeWithText("この端末では Health Connect を利用できません。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("再確認").performScrollTo().performClick()
        compose.runOnIdle { state = HealthConnectionState.Error }
        compose.onNodeWithText("接続を確認できませんでした。", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("再確認").performScrollTo().performClick()
        compose.runOnIdle { state = HealthConnectionState.PermissionsRequired(0) }
        compose.onNodeWithText("0/3 許可済み", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("読み取り権限を許可").performClick()
        compose.runOnIdle {
            assertEquals(2, retries)
            assertEquals(1, requests)
        }
    }
}
