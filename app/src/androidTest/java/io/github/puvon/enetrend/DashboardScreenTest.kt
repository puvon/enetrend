package io.github.puvon.enetrend

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import io.github.puvon.enetrend.health.*
import io.github.puvon.enetrend.ui.DashboardScreen
import io.github.puvon.enetrend.ui.theme.EnetrendTheme
import io.github.puvon.enetrend.ui.theme.LocalBalanceColors
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DashboardScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allMissingBalancesPaintNoCumulativeOriginAndRefillRestoresIt() {
        val start = LocalDate.of(2026, 9, 1)
        val range = HealthDataRange(start, start.plusDays(3), ZoneId.of("Asia/Tokyo"))
        var filled by mutableStateOf(false)
        var cumulativeColor = Color.Transparent
        compose.setContent { EnetrendTheme {
            cumulativeColor = MaterialTheme.colorScheme.tertiary
            val calories = DailyCalorieData(range, range.days().associate {
                it.date to CalorieTotals(if (filled) 200.0 else null, 200.0)
            })
            val data = DashboardData(CalorieBalanceCalculator.calculate(calories),
                WeightTrendCalculator.calculate(emptyList(), range), false)
            DashboardScreen(DashboardState.Ready(data), 7, MovingAveragePeriod.SEVEN_DAYS, {}, {}, {}, {}, {})
        } }
        val plot = compose.onNodeWithContentDescription("統合グラフ。", substring = true)
        val pixels = plot.performScrollTo().captureToImage().toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val p = pixels[x, y]
            if (kotlin.math.abs(p.red - cumulativeColor.red) < 0.02 && kotlin.math.abs(p.green - cumulativeColor.green) < 0.02 && kotlin.math.abs(p.blue - cumulativeColor.blue) < 0.02) count++
        }
        assertEquals("No cumulative line or zero origin for an entirely missing period", 0, count)
        compose.onNodeWithText("期間累積収支：算出できる日がありません。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("期間開始（", substring = true).assertDoesNotExist()
        compose.runOnIdle { filled = true }
        compose.onNodeWithText("期間累積収支：0.0 kcal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("期間開始（2026-09-01）：0.0 kcal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("欠測日を除いた参考累積", substring = true).assertDoesNotExist()
    }

    @Test fun singlePlotDrawsAllLinesAndPrecedesPeriodControls() {
        val start = LocalDate.of(2026, 9, 1)
        val range = HealthDataRange(start, start.plusDays(7), ZoneId.of("Asia/Tokyo"))
        val calories = DailyCalorieData(range, (0L..6L).associate {
            start.plusDays(it) to CalorieTotals(if (it % 2 == 0L) 1700.0 else 2200.0, 2000.0)
        })
        val records = (0L..6L).map {
            WeightMeasurement("$it", start.plusDays(it).atTime(8, 0).atZone(range.zoneId).toInstant(), null,
                70.0 + (it % 3) * 0.15, "test", java.time.Instant.EPOCH)
        }
        val data = DashboardData(CalorieBalanceCalculator.calculate(calories), WeightTrendCalculator.calculate(records, range), false)
        var lineColors = emptyList<Color>()
        compose.setContent { EnetrendTheme {
            lineColors = listOf(MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.secondary,
                LocalBalanceColors.current.positive.copy(alpha = 0.35f).compositeOver(MaterialTheme.colorScheme.background),
                LocalBalanceColors.current.negative.copy(alpha = 0.35f).compositeOver(MaterialTheme.colorScheme.background))
            DashboardScreen(DashboardState.Ready(data), 7, MovingAveragePeriod.SEVEN_DAYS, {}, {}, {}, {}, {})
        } }
        val plot = compose.onNodeWithContentDescription("統合グラフ。", substring = true)
        compose.onAllNodesWithContentDescription("統合グラフ。", substring = true).assertCountEquals(1)
        plot.performScrollTo().assertIsDisplayed()
        val pixels = plot.captureToImage().toPixelMap()
        lineColors.forEach { color ->
            var count = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val p = pixels[x, y]
                if (kotlin.math.abs(p.red - color.red) < 0.02 && kotlin.math.abs(p.green - color.green) < 0.02 && kotlin.math.abs(p.blue - color.blue) < 0.02) count++
            }
            assertTrue("Bars and each line must be painted inside the same plot", count > 20)
        }
        val plotTop = plot.fetchSemanticsNode().positionInRoot.y
        val controlsTop = compose.onNodeWithText("表示期間（今日まで）").fetchSemanticsNode().positionInRoot.y
        assertTrue(plotTop < controlsTop)
        compose.onNodeWithText("体重 kg").performScrollTo().assertIsDisplayed()
    }

    @Test fun referenceCumulativeContinuesAfterMissingDay() {
        val start = LocalDate.of(2026, 9, 1)
        val range = HealthDataRange(start, start.plusDays(3), ZoneId.of("Asia/Tokyo"))
        val calories = DailyCalorieData(range, mapOf(start to CalorieTotals(100.0, 200.0),
            start.plusDays(2) to CalorieTotals(300.0, 200.0)))
        val data = DashboardData(CalorieBalanceCalculator.calculate(calories), WeightTrendCalculator.calculate(emptyList(), range), false)
        var cumulativeColor = Color.Transparent
        compose.setContent { EnetrendTheme {
            cumulativeColor = MaterialTheme.colorScheme.tertiary
            DashboardScreen(DashboardState.Ready(data), 7, MovingAveragePeriod.SEVEN_DAYS, {}, {}, {}, {}, {})
        } }
        val plot = compose.onNodeWithContentDescription("統合グラフ。", substring = true)
        plot.performScrollTo()
        val pixels = plot.captureToImage().toPixelMap()
        var coloredPixels = 0
        for (y in 0 until pixels.height) for (x in pixels.width / 2 until pixels.width) {
            val p = pixels[x, y]
            if (kotlin.math.abs(p.red - cumulativeColor.red) < 0.02 && kotlin.math.abs(p.green - cumulativeColor.green) < 0.02 && kotlin.math.abs(p.blue - cumulativeColor.blue) < 0.02) coloredPixels++
        }
        assertTrue("Reference cumulative must continue after missing days", coloredPixels > 20)
        compose.onNodeWithText("欠測日を除いた参考累積（不完全・欠測", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun changingDisplayPeriodUpdatesOriginAndDisplayedTotal() {
        val end = LocalDate.of(2026, 9, 15)
        val loaded = HealthDataRange(end.minusDays(30), end, ZoneId.of("Asia/Tokyo"))
        val calories = DailyCalorieData(loaded, (1L..30L).associate { end.minusDays(it) to CalorieTotals(2000.0, 2100.0) })
        compose.setContent { EnetrendTheme {
            var days by remember { mutableStateOf(30) }
            val selected = HealthDataRange(end.minusDays(days.toLong()), end, loaded.zoneId)
            val data = DashboardData(CalorieBalanceCalculator.calculate(calories, selected),
                WeightTrendCalculator.calculate(emptyList(), selected), false)
            DashboardScreen(DashboardState.Ready(data), days, MovingAveragePeriod.SEVEN_DAYS, { days = it }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("期間開始（2026-08-16）：0.0 kcal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("期間累積収支：-3000.0 kcal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("7日間").performScrollTo().performClick()
        compose.onNodeWithText("期間開始（2026-09-08）：0.0 kcal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("期間累積収支：-700.0 kcal").performScrollTo().assertIsDisplayed()
    }

    @Test fun independentPeriodControlsAndLoading() {
        var display = 30
        var average = MovingAveragePeriod.SEVEN_DAYS
        compose.setContent { EnetrendTheme {
            DashboardScreen(DashboardState.Loading, display, average, { display = it }, { average = it }, {}, {}, {})
        } }
        compose.onNodeWithText("30日間").assertIsSelected()
        compose.onNodeWithText("7日平均").assertIsSelected()
        compose.onNodeWithText("14日間").performClick()
        compose.onNodeWithText("30日平均").performClick()
        compose.runOnIdle {
            assertEquals(14, display)
            assertEquals(MovingAveragePeriod.THIRTY_DAYS, average)
        }
        compose.onNodeWithText("データを読み込んでいます。").performScrollTo().assertIsDisplayed()
    }

    @Test fun emptyDataMessage() {
        val date = LocalDate.of(2026, 9, 14)
        val range = HealthDataRange(date.minusDays(6), date.plusDays(1), ZoneId.of("Asia/Tokyo"))
        val data = DashboardData(CalorieBalanceCalculator.calculate(DailyCalorieData(range, emptyMap())),
            WeightTrendCalculator.calculate(emptyList(), range), false)
        compose.setContent { EnetrendTheme {
            DashboardScreen(DashboardState.Ready(data), 7, MovingAveragePeriod.SEVEN_DAYS, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("この期間に表示できるデータがありません。").performScrollTo().assertIsDisplayed()
    }

    @Test fun chartsAndMissingCumulativeDetailsAreAccessible() {
        val date = LocalDate.of(2026, 9, 14)
        val range = HealthDataRange(date.minusDays(6), date.plusDays(1), ZoneId.of("Asia/Tokyo"))
        val calories = DailyCalorieData(range, mapOf(date to CalorieTotals(2000.0, 2200.0)))
        val data = DashboardData(CalorieBalanceCalculator.calculate(calories), WeightTrendCalculator.calculate(emptyList(), range), true)
        compose.setContent { EnetrendTheme {
            DashboardScreen(DashboardState.Ready(data), 7, MovingAveragePeriod.SEVEN_DAYS, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("EneTrend").assertDoesNotExist()
        compose.onNodeWithText("収支 = 摂取", substring = true).assertDoesNotExist()
        compose.onNodeWithText("■ 日別カロリー収支").assertDoesNotExist()
        compose.onNodeWithText("凡例を開く").performScrollTo().performClick()
        compose.onNodeWithText("■ 日別カロリー収支").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("━ 期間累積収支").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("期間開始（2026-09-08）：0.0 kcal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("欠測日を除いた参考累積（不完全・欠測", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("期間累積は独立スケール（体重との連動なし）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("日別収支：-200.0 kcal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("算出可能日の小計：", substring = true).assertDoesNotExist()
        compose.onNodeWithText("体重：欠測").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("凡例を閉じる").performScrollTo().performClick()
        compose.onNodeWithText("■ 日別カロリー収支").assertDoesNotExist()
        compose.onNodeWithText("日別 kcal").performScrollTo().assertIsDisplayed()
    }

    @Test fun accessFailureOffersRetry() {
        var retries = 0
        compose.setContent { EnetrendTheme {
            DashboardScreen(DashboardState.Failed(HealthDataResult.AccessDenied), 30,
                MovingAveragePeriod.SEVEN_DAYS, {}, {}, { retries++ }, {}, {})
        } }
        compose.onNodeWithText("データへのアクセスが制限されています。", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("再確認").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
