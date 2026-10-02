package io.github.puvon.enetrend

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.puvon.enetrend.health.*
import io.github.puvon.enetrend.ui.*
import io.github.puvon.enetrend.ui.theme.EnetrendTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MetabolismScreenTest {
    @get:Rule val compose = createComposeRule()
    private val day = LocalDate.of(2026, 10, 1)
    private val range = HealthDataRange(day, day.plusDays(3), ZoneId.of("Asia/Tokyo"))
    private fun chart(): DashboardChartData {
        val estimate = MetabolismEstimate(1450.0, 500.0, 1950.0, 3, day, null,
            OptionalReadState.AVAILABLE, emptySet(), 2200.0)
        val input = DailyCalorieData(range, mapOf(
            day to CalorieTotals(2000.0, 2200.0, metabolism = estimate),
            day.plusDays(1) to CalorieTotals(2100.0, 2300.0, metabolism = estimate.copy(
                restingKilocaloriesPerDay = 1471.6, totalKilocalories = 1971.6)),
            day.plusDays(2) to CalorieTotals(null, 1200.0, metabolism = estimate.copy(
                restingKilocaloriesPerDay = null, totalKilocalories = null, fallback = MetabolismFallback.TODAY)),
        ))
        return DashboardChartProjector.project(DashboardData(CalorieBalanceCalculator.calculate(input), emptyList(), false))
    }

    @Test fun stackedBarsAndFallbackRemainSelectableInBothThemes() {
        var dark by mutableStateOf(false)
        var selected by mutableStateOf(0)
        var resting = Color.Transparent
        var activity = Color.Transparent
        val chart = chart()
        compose.setContent { EnetrendTheme(darkTheme = dark, dynamicColor = false) {
            resting = MaterialTheme.colorScheme.primary
            activity = MaterialTheme.colorScheme.tertiary
            Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
                DashboardChart(chart, selected) { selected = it }
            } }
        } }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode }
            val graph = compose.onNodeWithContentDescription("統合グラフ。", substring = true).performScrollTo()
            val pixels = graph.captureToImage().toPixelMap()
            fun near(a: Color, b: Color) = kotlin.math.abs(a.red - b.red) < .02 &&
                kotlin.math.abs(a.green - b.green) < .02 && kotlin.math.abs(a.blue - b.blue) < .02
            val restingRows = mutableListOf<Int>()
            val activeRows = mutableListOf<Int>()
            // Both components have the same x center. Their vertical order is observable in the bitmap.
            val centerX = pixels.width / 6
            for (y in 0 until pixels.height) for (x in centerX - 10..centerX + 10) {
                if (near(pixels[x, y], resting)) restingRows.add(y)
                if (near(pixels[x, y], activity)) activeRows.add(y)
            }
            assertTrue(restingRows.isNotEmpty())
            assertTrue(activeRows.isNotEmpty())
            assertTrue(activeRows.average() < restingRows.average())
            graph.performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 2f, height / 2f)) }
            compose.runOnIdle { assertEquals(2, selected) }
            graph.assert(SemanticsMatcher("fallback consumption is described") {
                it.config[SemanticsProperties.StateDescription].contains("消費：1200.0 kcal")
            })
        }
    }

    @Test fun enlargedDetailsExplainEstimateFallbackAndBaselineInBothThemes() {
        var dark by mutableStateOf(false)
        var selected by mutableStateOf(1)
        val chart = chart()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                EnetrendTheme(darkTheme = dark) {
                    Column(Modifier.width(300.dp).height(400.dp).verticalScroll(rememberScrollState())) {
                        DashboardDetails(chart.days[selected])
                    }
                }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode; selected = 1 }
            compose.onNodeWithText("補正後の推定総消費カロリー：1971.6 kcal").performScrollTo().assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "推定・日数不足"))
            compose.onNodeWithText("基準日比の推定安静時代謝増減：21.6 kcal/day").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("基準日比の推定安静時代謝増減の参考情報").performScrollTo().performClick()
            compose.onNodeWithText("基準日：2026-10-01。", substring = true).assertExists()
            compose.onNodeWithText("閉じる").performClick()
            compose.runOnIdle { selected = 2 }
            compose.onNodeWithContentDescription("補正後の推定総消費カロリーの参考情報").performScrollTo().performClick()
            compose.onNodeWithText("当日は補正せず、途中の従来取得値を使用します。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("閉じる").performClick()
        }
    }

    @Test fun optionalPermissionActionDoesNotGateExistingDashboard() {
        var requested = false
        val data = DashboardData(CalorieBalanceCalculator.calculate(DailyCalorieData(range,
            mapOf(day to CalorieTotals(2000.0, 2200.0)))), emptyList(), false)
        compose.setContent { EnetrendTheme {
            DashboardScreen(DashboardState.Ready(data), 7, MovingAveragePeriod.SEVEN_DAYS, {}, {}, {}, {}, {},
                onMetabolismPermissions = { requested = true })
        } }
        compose.onNodeWithText("体組成による補正の読み取り権限（任意）").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(requested) }
        compose.onNodeWithContentDescription("統合グラフ。", substring = true).performScrollTo().assertIsDisplayed()
    }
}
