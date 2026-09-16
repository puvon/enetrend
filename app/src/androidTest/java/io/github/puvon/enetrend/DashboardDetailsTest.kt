package io.github.puvon.enetrend

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import org.junit.Rule
import org.junit.Test

class DashboardDetailsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun enlargedDetailsKeepValuesAndStatesReadableInBothThemes() {
        val start = LocalDate.of(2026, 9, 1)
        val range = HealthDataRange(start, start.plusDays(2), ZoneId.of("Asia/Tokyo"))
        val chart = DashboardChartProjector.project(DashboardData(
            CalorieBalanceCalculator.calculate(DailyCalorieData(range, mapOf(start to CalorieTotals(0.0, 0.0)))),
            WeightTrendCalculator.calculate(emptyList(), range), false))
        var dark by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                EnetrendTheme(darkTheme = dark) {
                    Column(Modifier.width(300.dp).height(300.dp).verticalScroll(rememberScrollState())) {
                        DashboardDetails(chart.days[0])
                    }
                }
            }
        }
        for (theme in listOf(false, true)) {
            compose.runOnIdle { dark = theme }
            compose.onNodeWithText("日別収支：0.0 kcal").performScrollTo().assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "値あり"))
            compose.onNodeWithText("体重：欠測").performScrollTo().assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "欠測"))
            compose.onNodeWithText("7日移動平均：未算出", substring = true).performScrollTo().assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "未算出・日数不足"))
        }
    }
}
