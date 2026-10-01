package io.github.puvon.enetrend

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.puvon.enetrend.health.*
import io.github.puvon.enetrend.ui.DashboardRoute
import io.github.puvon.enetrend.ui.DashboardScreen
import io.github.puvon.enetrend.ui.TodayCard
import io.github.puvon.enetrend.ui.theme.EnetrendTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TodayDashboardTest {
    @get:Rule val compose = createComposeRule()
    private val date = LocalDate.now()
    private val zone = ZoneId.systemDefault()

    private class Source(val date: LocalDate) : HealthDataSource {
        override val requiredPermissions = setOf("read")
        @Volatile var current = CalorieTotals(null, 1600.0)
        override fun availability() = HealthAvailability.AVAILABLE
        override suspend fun grantedPermissions() = requiredPermissions
        override suspend fun readCalorieTotals(range: HealthDataRange) =
            if (range.startDate == date) current else CalorieTotals(2100.0, 2000.0)
        override suspend fun readWeightPage(range: HealthDataRange, pageToken: String?) = WeightPage(emptyList(), null)
    }

    private fun route(source: Source) {
        val loader = DashboardLoader(HealthDataRepository(source))
        compose.setContent { EnetrendTheme {
            var version by remember { mutableStateOf(0) }
            var days by remember { mutableStateOf(7) }
            var average by remember { mutableStateOf(MovingAveragePeriod.SEVEN_DAYS) }
            DashboardRoute(loader, days, average, { days = it }, { average = it }, { version++ }, {}, {}, false,
                remember { SnackbarHostState() }, version)
        } }
    }

    private fun plot() = compose.onNodeWithContentDescription("統合グラフ。", substring = true)
    private fun waitForPlot() = compose.waitUntil(10000) {
        compose.onAllNodesWithContentDescription("統合グラフ。", substring = true).fetchSemanticsNodes().size == 1
    }
    private fun retry() {
        compose.onNodeWithText("再確認").performScrollTo().performClick()
        waitForPlot()
    }
    private fun selectedDate() = plot().fetchSemanticsNode().config[SemanticsProperties.StateDescription].substringBefore('\n')
    private fun status(records: Map<LocalDate, CalorieTotals>, current: CalorieTotals,
        restricted: Set<LocalDate> = emptySet()) = TodayStatus(date, Instant.now(), current,
        DailyCalorieData(HealthDataRange(date.minusDays(7), date, zone), records, restricted))
    private fun fullWeek() = (1L..7L).associate { date.minusDays(it) to CalorieTotals(2100.0, 2000.0) }

    @Test fun refreshKeepsDefaultDateThenPersistsFallbackAfterTodayIsRemoved() {
        val source = Source(date)
        route(source)
        waitForPlot()
        assertTrue(selectedDate().startsWith(date.minusDays(1).toString()))
        compose.onNodeWithText("当日の摂取データが未取得のため", substring = true).assertExists()
        compose.onNodeWithText("摂取可能量：1300.0 kcal").performScrollTo().assertIsDisplayed()
        val cardTop = compose.onNodeWithText("今日の状況（$date）").fetchSemanticsNode().positionInRoot.y
        assertTrue(cardTop < plot().fetchSemanticsNode().positionInRoot.y)

        source.current = CalorieTotals(1200.0, 1600.0)
        retry()
        compose.onNodeWithText("今日まで表示しています。", substring = true).assertExists()
        assertTrue(selectedDate().startsWith(date.minusDays(1).toString()))
        assertTrue(plot().fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("期間累積収支：600.0 kcal"))
        plot().performScrollTo().performTouchInput { click(Offset(width - 1f, height / 2f)) }
        assertTrue(selectedDate().startsWith(date.toString()))

        source.current = CalorieTotals(1800.0, 1600.0)
        retry()
        compose.onNodeWithText("取得済み摂取：1800.0 kcal").assertExists()
        compose.onNodeWithText("摂取可能量：1300.0 kcal").assertExists()
        assertTrue(plot().fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("期間累積収支：800.0 kcal"))
        source.current = CalorieTotals(800.0, 1600.0)
        retry()
        compose.onNodeWithText("取得済み摂取：800.0 kcal").assertExists()
        assertTrue(plot().fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("期間累積収支：-200.0 kcal"))

        source.current = CalorieTotals(null, 1600.0)
        retry()
        assertTrue(selectedDate().startsWith(date.minusDays(1).toString()))
        assertTrue(plot().fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("期間累積収支：700.0 kcal"))
        source.current = CalorieTotals(1200.0, 1600.0)
        retry()
        assertTrue(selectedDate().startsWith(date.minusDays(1).toString()))

        source.current = CalorieTotals(0.0, 1600.0)
        retry()
        compose.onNodeWithText("当日の摂取が0 kcalのため", substring = true).assertExists()
        compose.onNodeWithText("取得済み摂取：0.0 kcal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("取得済み摂取：未取得").assertDoesNotExist()
    }

    @Test fun shiftedStartFallsBackToEndAndPeriodAverageAndSelectionDoNotChangeAllowance() {
        val source = Source(date)
        route(source)
        waitForPlot()
        plot().performScrollTo().performTouchInput { click(Offset(1f, height / 2f)) }
        assertTrue(selectedDate().startsWith(date.minusDays(7).toString()))
        source.current = CalorieTotals(1200.0, 1600.0)
        retry()
        assertTrue(selectedDate().startsWith(date.toString()))
        compose.onNodeWithText("摂取可能量：1300.0 kcal").assertExists()
        compose.onNodeWithText("14日間").performScrollTo().performClick()
        waitForPlot()
        compose.onNodeWithText("30日平均").performScrollTo().performClick()
        waitForPlot()
        plot().performScrollTo().performTouchInput { click(Offset(1f, height / 2f)) }
        compose.onNodeWithText("摂取可能量：1300.0 kcal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("消費記録7/7日・収支算出7/7日").assertExists()
    }

    @Test fun todayCardRemainsVisibleWhenTrendAndThenAllCaloriesAreEmpty() {
        var current by mutableStateOf(CalorieTotals(null, 1600.0))
        val range = HealthDataRange(date.minusDays(7), date, zone)
        compose.setContent { EnetrendTheme {
            val data = DashboardData(CalorieBalanceCalculator.calculate(DailyCalorieData(range, emptyMap())),
                WeightTrendCalculator.calculate(emptyList(), range), false, status(emptyMap(), current))
            DashboardScreen(DashboardState.Ready(data), 7, MovingAveragePeriod.SEVEN_DAYS, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("摂取可能量：1600.0 kcal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("予測消費なし・取得済み消費のみで計算").assertExists()
        compose.onNodeWithText("過去収支の補正なし").assertExists()
        compose.onNodeWithText("この期間に表示できるデータがありません。").performScrollTo().assertIsDisplayed()
        plot().assertDoesNotExist()
        compose.runOnIdle { current = CalorieTotals(null, null) }
        compose.onNodeWithText("摂取可能量：算出不可").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("消費の記録がないため算出できません。").assertExists()
        compose.onNodeWithText("取得済み消費：未取得").assertExists()
        compose.runOnIdle { current = CalorieTotals(0.0, 0.0) }
        compose.onNodeWithText("摂取可能量：0.0 kcal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("取得済み摂取：0.0 kcal").assertExists()
    }

    @Test fun negativeAllowanceAndRecordedConsumptionOverrideRemainExplicit() {
        val records = fullWeek().mapValues { CalorieTotals(2000.0, 2000.0) }.toMutableMap().apply {
            put(date.minusDays(1), CalorieTotals(4500.0, 2000.0))
        }
        var current by mutableStateOf(CalorieTotals(null, 1600.0))
        compose.setContent { EnetrendTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { TodayCard(status(records, current)) }
        } }
        compose.onNodeWithText("摂取可能量：-500.0 kcal").assertIsDisplayed()
        compose.onNodeWithContentDescription("摂取可能量の参考情報").performClick()
        compose.onNodeWithText("負の値は、", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("閉じる").performClick()
        compose.onNodeWithContentDescription("摂取可能量の参考情報").performScrollTo().performClick()
        compose.runOnIdle { current = CalorieTotals(1000.0, 2300.0) }
        compose.onNodeWithText("閉じる").assertDoesNotExist()
        compose.onNodeWithText("摂取可能量：-200.0 kcal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("予測消費：2000.0 kcal").assertExists()
        compose.onNodeWithText("取得済み消費：2300.0 kcal").assertExists()
        compose.onNodeWithText("取得済み消費が予測を上回るため、取得済み消費で計算").assertExists()
    }

    @Test fun partialHistoryShowsSeparateCountsAndAccessRestriction() {
        val restricted = date.minusDays(7)
        val records = fullWeek().filterKeys { it != restricted }.toMutableMap().apply {
            put(date.minusDays(2), CalorieTotals(null, 2000.0))
            put(date.minusDays(3), CalorieTotals(null, 2000.0))
        }
        compose.setContent { EnetrendTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TodayCard(status(records, CalorieTotals(null, null), setOf(restricted)))
            }
        } }
        compose.onNodeWithText("摂取可能量：1600.0 kcal").assertIsDisplayed()
        compose.onNodeWithText("欠測を含む参考値・履歴アクセス制限1/7日").assertExists()
        compose.onNodeWithText("当日消費未取得・予測消費で計算").assertExists()
        compose.onNodeWithText("消費記録6/7日・収支算出4/7日").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("${date.minusDays(7)} ～ ${date.minusDays(1)}").assertExists()
    }

    @Test fun largeTextAndBothThemesKeepAllowanceAndReferenceInformationAccessible() {
        var dark by mutableStateOf(false)
        val input = status(fullWeek(), CalorieTotals(null, 1600.0))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                EnetrendTheme(darkTheme = dark, dynamicColor = false) {
                    Column(Modifier.width(300.dp).height(500.dp).verticalScroll(rememberScrollState())) { TodayCard(input) }
                }
            }
        }
        for (theme in listOf(false, true)) {
            compose.runOnIdle { dark = theme }
            compose.onNodeWithText("摂取可能量：1300.0 kcal").performScrollTo().assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "一日合計の参考値。"))
            compose.onNodeWithText("取得済み摂取：未取得").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("摂取可能量の参考情報").performScrollTo().performClick()
            compose.onNodeWithText("摂取可能量 = 計算に使う消費 − 過去7日収支").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("閉じる").performClick()
        }
    }
}
