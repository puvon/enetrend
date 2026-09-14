package io.github.puvon.enetrend

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.puvon.enetrend.health.*
import io.github.puvon.enetrend.ui.DashboardScreen
import io.github.puvon.enetrend.ui.theme.EnetrendTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DashboardScreenTest {
    @get:Rule val compose = createComposeRule()

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
        compose.onNodeWithText("日別カロリー収支").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("累積カロリー収支").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("体重・移動平均").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("日別収支：-200.0 kcal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("算出可能日の小計：", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("体重：欠測").performScrollTo().assertIsDisplayed()
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
