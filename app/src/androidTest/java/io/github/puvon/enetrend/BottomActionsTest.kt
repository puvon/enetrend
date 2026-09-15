package io.github.puvon.enetrend

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.puvon.enetrend.health.*
import io.github.puvon.enetrend.ui.*
import io.github.puvon.enetrend.ui.theme.EnetrendTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BottomActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shortLoadingScreenPlacesActionsBelowContentNearBottom() {
        compose.setContent { EnetrendTheme {
            DashboardScreen(DashboardState.Loading, 30, MovingAveragePeriod.SEVEN_DAYS, {}, {}, {}, {}, {})
        } }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val retry = compose.onNodeWithText("再確認")
        retry.assertIsDisplayed()
        assertTrue(retry.fetchSemanticsNode().boundsInRoot.top > root.center.y)
        assertTrue(retry.fetchSemanticsNode().boundsInRoot.top >
            compose.onNodeWithText("30日平均").fetchSemanticsNode().boundsInRoot.bottom)
    }

    @Test fun largeTextOnShortViewportKeepsPartialPermissionActionsReachable() {
        var retries = 0
        var settings = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                EnetrendTheme { Box(Modifier.fillMaxWidth().height(300.dp)) {
                    HealthConnectionScreen(HealthConnectionState.PermissionsRequired(1), false,
                        {}, { retries++ }, { settings++ }, {}, {})
                } }
            }
        }
        compose.onNodeWithText("1/3 許可済み", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Health Connect の設定").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("再確認").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, settings) }
    }
}
