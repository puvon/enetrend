package io.github.puvon.enetrend

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import io.github.puvon.enetrend.health.*
import io.github.puvon.enetrend.ui.DashboardScreen
import io.github.puvon.enetrend.ui.theme.EnetrendTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DashboardQualityTest {
    @get:Rule val compose = createComposeRule()
    private val start = LocalDate.of(2026, 9, 1)
    private val zone = ZoneId.of("Asia/Tokyo")
    private fun range(n: Int) = HealthDataRange(start, start.plusDays(n.toLong()), zone)
    private fun record(day: Int) = WeightMeasurement("$day", start.plusDays(day.toLong()).atTime(8, 0).atZone(zone).toInstant(), null,
        70.0 + day / 10.0, "test", Instant.EPOCH)
    private fun show(range: HealthDataRange, calories: Map<LocalDate, CalorieTotals>, weights: List<WeightMeasurement>, fontScale: Float = 1f) {
        val data = DashboardData(CalorieBalanceCalculator.calculate(DailyCalorieData(range, calories)),
            WeightTrendCalculator.calculate(weights, range), false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                EnetrendTheme { DashboardScreen(DashboardState.Ready(data), 30, MovingAveragePeriod.SEVEN_DAYS, {}, {}, {}, {}, {}) }
            }
        }
    }
    private fun summary() = compose.onNodeWithContentDescription("統合グラフ。", substring = true).fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    @Test fun weightOnlyDoesNotInventCalorieBalances() {
        show(range(3), emptyMap(), listOf(record(0), record(2)))
        assertTrue(summary().contains("日別収支：未算出"))
        assertTrue(summary().contains("期間累積収支：未算出"))
        assertTrue(summary().contains("体重：70.2 kg"))
        compose.onAllNodesWithContentDescription("統合グラフ。", substring = true).assertCountEquals(1)
        compose.onNodeWithText("この期間の摂取カロリーは欠測です。", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("この期間に表示できる総消費カロリーがありません。", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun intakeOnlyExplainsMissingBurnedAndWeight() {
        val range = range(3)
        show(range, range.days().associate { it.date to CalorieTotals(1800.0, null) }, emptyList())
        assertTrue(summary().contains("日別収支：未算出"))
        compose.onNodeWithText("この期間に表示できる総消費カロリーがありません。", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("この期間に表示できる体重がありません。", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun burnedOnlyExplainsMissingIntake() {
        val range = range(3)
        show(range, range.days().associate { it.date to CalorieTotals(null, 2000.0) }, emptyList())
        assertTrue(summary().contains("期間累積収支：未算出"))
        compose.onNodeWithText("この期間の摂取カロリーは欠測です。", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun caloriesOnlyKeepBalanceAndExplainMissingWeight() {
        val range = range(3)
        show(range, range.days().associate { it.date to CalorieTotals(1800.0, 2000.0) }, emptyList())
        assertTrue(summary().contains("期間累積収支：-600.0 kcal"))
        compose.onNodeWithText("この期間に表示できる体重がありません。", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun earlierMeasurementStillAllowsAverageWhenPeriodHasNoWeight() {
        show(range(3), emptyMap(), listOf(record(-1)))
        assertTrue(summary().contains("体重：欠測"))
        assertTrue(summary().contains("7日移動平均：69.9 kg（実測1/7日・日数不足）"))
        compose.onNodeWithText("移動平均の実測日数が不足する日があります。", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun interpolationOnlyBaselineIsLabelled() {
        val range = range(3)
        show(range, range.days().associate { it.date to CalorieTotals(1800.0, 2000.0) }, listOf(record(-1), record(3)))
        compose.onNodeWithText("累積0の基準：2026-09-01 70.0 kg（補間）").performScrollTo().assertIsDisplayed()
        assertTrue(summary().contains("体重：70.2 kg（補間："))
        assertTrue(summary().contains("期間累積収支：-600.0 kcal"))
    }

    @Test fun longWeightGapAndMissingIntakeStayMissing() {
        val range = range(10)
        show(range, range.days().associate { it.date to CalorieTotals(if (it.date == start.plusDays(4)) null else 1800.0, 2000.0) },
            listOf(record(0), record(9)))
        compose.onNodeWithContentDescription("詳細を表示する日付").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        assertTrue(summary().contains("摂取：欠測"))
        assertTrue(summary().contains("期間累積収支：未算出"))
        assertTrue(summary().contains("体重：欠測"))
        assertTrue(summary().contains("算出可能日の小計："))
    }

    @Test fun enlargedTextAndLargeCumulativeKeepAxisLabelsAndControlsReadable() {
        val range = range(30)
        show(range, range.days().associate { it.date to CalorieTotals(600.0, 2000.0) }, listOf(record(0), record(29)), 2f)
        val axis = compose.onNodeWithText("-1400.0")
        axis.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        axis.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse("Axis clipped: size=${layout.size}, width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, intrinsic=${layout.multiParagraph.maxIntrinsicWidth}", layout.hasVisualOverflow)
        assertTrue(summary().contains("期間累積収支：-42000.0 kcal"))
        listOf("7日間", "14日間", "30日間", "7日平均", "14日平均", "30日平均").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
    }
}
