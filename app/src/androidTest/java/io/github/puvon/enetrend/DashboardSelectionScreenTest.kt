package io.github.puvon.enetrend

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.puvon.enetrend.health.*
import io.github.puvon.enetrend.ui.DashboardScreen
import io.github.puvon.enetrend.ui.theme.EnetrendTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DashboardSelectionScreenTest {
    @get:Rule val compose = createComposeRule()
    private val start = LocalDate.of(2026, 9, 1)
    private fun show(days: Int = 3) {
        val range = HealthDataRange(start, start.plusDays(days.toLong()), ZoneId.of("Asia/Tokyo"))
        val calories = DailyCalorieData(range, (0 until days).associate {
            start.plusDays(it.toLong()) to CalorieTotals(if (it == 1) null else 100.0 + it * 100, 200.0)
        })
        val weights = (0 until days).filter { it != 1 }.map {
            WeightMeasurement("$it", start.plusDays(it.toLong()).atTime(8, 0).atZone(range.zoneId).toInstant(), null,
                70.0 + it, "test", Instant.EPOCH)
        }
        val data = DashboardData(CalorieBalanceCalculator.calculate(calories), WeightTrendCalculator.calculate(weights, range), false)
        compose.setContent { EnetrendTheme {
            DashboardScreen(DashboardState.Ready(data), 7, MovingAveragePeriod.SEVEN_DAYS, {}, {}, {}, {}, {})
        } }
    }
    private fun plot() = compose.onNodeWithContentDescription("統合グラフ。", substring = true)
    private fun slider() = compose.onNodeWithContentDescription("詳細を表示する日付")
    private fun description() = plot().fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    @Test fun tapsAtBothEdgesUpdateSliderAndAllDetails() {
        show()
        plot().performScrollTo().performTouchInput { click(Offset(1f, height / 2f)) }
        assertTrue(description().contains("2026-09-01。"))
        assertTrue(description().contains("日別収支：-100.0 kcal"))
        assertTrue(description().contains("期間累積収支：-100.0 kcal"))
        assertTrue(description().contains("体重：70.0 kg"))
        slider().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "2026-09-01"))
        compose.onNodeWithText("期間累積収支：-100.0 kcal").performScrollTo().assertIsDisplayed()
        plot().performScrollTo().performTouchInput { click(Offset(width - 1f, height / 2f)) }
        slider().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "2026-09-03"))
        assertTrue(description().contains("日別収支：100.0 kcal"))
    }

    @Test fun missingDayAndInterpolatedWeightAreSelectableAndSliderUpdatesChart() {
        show()
        plot().performScrollTo().performTouchInput { click(center) }
        assertTrue(description().contains("2026-09-02。"))
        assertTrue(description().contains("摂取：欠測"))
        assertTrue(description().contains("期間累積収支：-100.0 kcal"))
        assertTrue(description().contains("体重：71.0 kg（補間："))
        compose.onNodeWithText("摂取：欠測").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "欠測"))
        compose.onNodeWithText("期間累積収支：-100.0 kcal").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "参考累積"))
        compose.onNodeWithText("体重：71.0 kg").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "補間"))
        compose.onNodeWithText("体重：71.0 kg").performScrollTo().assertIsDisplayed()
        slider().performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        assertTrue(description().contains("2026-09-01。"))
        plot().performScrollTo().assertIsDisplayed()
    }

    @Test fun accessibilityActionsMoveAcrossDaysAndStopAtEdges() {
        show()
        plot().performScrollTo()
        val lastActions = plot().fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("前の日"), lastActions.map { it.label })
        compose.runOnIdle { assertTrue(lastActions.single().action()) }
        assertTrue(description().contains("2026-09-02。"))
        val middleActions = plot().fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("前の日", "次の日"), middleActions.map { it.label })
        compose.runOnIdle { assertTrue(middleActions.first().action()) }
        assertEquals(listOf("次の日"), plot().fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label })
    }

    @Test fun verticalSwipeScrollsWithoutChangingSelectedDate() {
        show()
        plot().performScrollTo()
        val before = description()
        val top = plot().fetchSemanticsNode().positionInRoot.y
        plot().performTouchInput { swipeUp() }
        assertEquals(before, description())
        assertTrue(plot().fetchSemanticsNode().positionInRoot.y < top)
    }

    @Test fun singleDayTapIsSafeAndSliderIsDisabled() {
        show(1)
        plot().performScrollTo().performTouchInput { click(center) }
        assertTrue(description().contains("2026-09-01。"))
        assertTrue(plot().fetchSemanticsNode().config[SemanticsActions.CustomActions].isEmpty())
        slider().performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("日別収支：-100.0 kcal").performScrollTo().assertIsDisplayed()
    }
}
