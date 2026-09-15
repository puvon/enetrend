package io.github.puvon.enetrend

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses the emulator's existing readable Health Connect data; never inserts health records. */
class DashboardRotationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun plot() = compose.onNodeWithContentDescription("統合グラフ。", substring = true)
    private fun waitForPlot() = compose.waitUntil(15000) {
        compose.onAllNodesWithContentDescription("統合グラフ。", substring = true).fetchSemanticsNodes().size == 1
    }

    @Test fun realActivityRotationRestoresPeriodsAndSelectedDay() {
        waitForPlot()
        compose.onNodeWithText("7日間").performScrollTo().performClick()
        waitForPlot()
        compose.onNodeWithText("14日平均").performScrollTo().performClick()
        waitForPlot()
        plot().performScrollTo().performTouchInput { click(Offset(1f, height / 2f)) }
        val date = plot().fetchSemanticsNode().config[SemanticsProperties.StateDescription].substringBefore("。")
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(15000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            waitForPlot()
            compose.onNodeWithText("7日間").assertIsSelected()
            compose.onNodeWithText("14日平均").assertIsSelected()
            assertTrue(plot().fetchSemanticsNode().config[SemanticsProperties.StateDescription].startsWith(date))
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }
}
