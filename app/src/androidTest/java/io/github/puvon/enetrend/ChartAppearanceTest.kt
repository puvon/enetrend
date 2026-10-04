package io.github.puvon.enetrend

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.puvon.enetrend.health.*
import io.github.puvon.enetrend.ui.*
import io.github.puvon.enetrend.ui.theme.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChartAppearanceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun allEightVisibilityCombinationsKeepAxesAndSelectionConsistent() {
        var groups by mutableStateOf(DashboardChartGroups.ALL)
        val projected = chart()
        compose.setContent { EnetrendTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DashboardChart(projected, 1, {}, groups)
            }
        } }
        for (mask in 0..DashboardChartGroups.ALL) {
            compose.runOnIdle { groups = mask }
            val graph = compose.onNodeWithContentDescription("統合グラフ。", substring = true).performScrollTo()
            val description = graph.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
            compose.onNodeWithText("凡例を開く").assertExists()
            assertEquals("weight group mask=$mask", mask and DashboardChartGroups.WEIGHT != 0,
                description.contains("体重と移動平均"))
            assertEquals("calorie group mask=$mask", mask and DashboardChartGroups.CALORIES != 0,
                description.contains("摂取カロリー、消費カロリー、日別収支"))
            assertEquals("cumulative group mask=$mask", mask and DashboardChartGroups.CUMULATIVE != 0,
                description.contains("期間累積収支"))
            if (mask and DashboardChartGroups.CALORIES != 0)
                compose.onNodeWithText("摂取・消費 kcal").performScrollTo().assertIsDisplayed()
            else compose.onNodeWithText("摂取・消費 kcal").assertDoesNotExist()
            val rightAxis = when {
                mask and DashboardChartGroups.WEIGHT == 0 -> "換算 kg（体重非表示）"
                projected.baseline == null -> "換算 kg（体重なし）"
                else -> "体重 kg"
            }
            if (mask and (DashboardChartGroups.WEIGHT or DashboardChartGroups.CUMULATIVE) != 0)
                compose.onNodeWithText(rightAxis).performScrollTo().assertIsDisplayed()
            else {
                compose.onNodeWithText("体重 kg").assertDoesNotExist()
                compose.onNodeWithText("換算 kg（体重なし）").assertDoesNotExist()
                compose.onNodeWithText("換算 kg（体重非表示）").assertDoesNotExist()
            }
            if (mask == 0) {
                assertTrue(description.contains("すべての系列は非表示です"))
                assertTrue(description.contains("タップで日付を選択"))
                compose.onNodeWithText("すべての系列は非表示です").assertDoesNotExist()
                compose.onNodeWithText("体重 kg").assertDoesNotExist()
                compose.onNodeWithText("摂取・消費 kcal").assertDoesNotExist()
                val selectedDetails = graph.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
                assertTrue(selectedDetails.contains("2026-09-02"))
                assertTrue(selectedDetails.contains("摂取：1700.0 kcal"))
                assertTrue(selectedDetails.contains("日別収支：-300.0 kcal"))
                assertTrue(selectedDetails.contains("期間累積収支：0.0 kcal"))
            }
        }
    }
    @Test fun legendRestoresExpansionAndKeepsDateSelectionInBothThemes() {
        val restoration = StateRestorationTester(compose)
        var dark by mutableStateOf(false)
        var selected by mutableStateOf(0)
        restoration.setContent {
            EnetrendTheme(darkTheme = dark) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DashboardChart(chart(), selected, { selected = it })
                }
            }
        }
        compose.onNodeWithText("凡例を開く")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "折りたたみ"))
            .performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("凡例を閉じる")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "展開中"))
        compose.runOnIdle { dark = true }
        compose.onNodeWithText("● 体重").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("統合グラフ。", substring = true).performScrollTo()
            .performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 2f, height / 2f)) }
        compose.runOnIdle { assertEquals(2, selected) }
        compose.onNodeWithText("凡例を閉じる").performScrollTo().performClick()
        compose.onNodeWithText("● 体重").assertDoesNotExist()
        compose.onNodeWithText("摂取・消費 kcal").performScrollTo().assertIsDisplayed()
    }
    private val start = LocalDate.of(2026, 9, 1)
    private val range = HealthDataRange(start, start.plusDays(3), ZoneId.of("Asia/Tokyo"))
    private fun chart() = DashboardChartProjector.project(DashboardData(
        CalorieBalanceCalculator.calculate(DailyCalorieData(range, mapOf(
            start to CalorieTotals(2300.0, 2000.0), start.plusDays(1) to CalorieTotals(1700.0, 2000.0),
            start.plusDays(2) to CalorieTotals(2000.0, 2000.0)))),
        WeightTrendCalculator.calculate(emptyList(), range), false))
    private fun pixels() = compose.onNodeWithContentDescription("統合グラフ。", substring = true).captureToImage().toPixelMap()
    private fun count(pixels: PixelMap, color: Color): Int {
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val pixel = pixels[x, y]
            if (kotlin.math.abs(pixel.red - color.red) < .02 && kotlin.math.abs(pixel.green - color.green) < .02 &&
                kotlin.math.abs(pixel.blue - color.blue) < .02) count++
        }
        return count
    }

    @Test fun differenceFillsStayBetweenPointsAtFortyPercentWidthInBothThemes() {
        var dark by mutableStateOf(false)
        var warm = Color.Transparent
        var cool = Color.Transparent
        var inset = 0f
        val chart = chart()
        compose.setContent { EnetrendTheme(darkTheme = dark) {
            inset = rememberTextMeasurer().measure("0", MaterialTheme.typography.labelSmall).size.height / 2f
            val colors = LocalBalanceColors.current
            warm = colors.positive.copy(alpha = .35f).compositeOver(MaterialTheme.colorScheme.surface)
            cool = colors.negative.copy(alpha = .35f).compositeOver(MaterialTheme.colorScheme.surface)
            Surface { Column { DashboardChart(chart, 2, {}) } }
        } }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode }
            val image = pixels()
            assertTrue(count(image, warm) > 100)
            assertTrue(count(image, cool) > 100)
            assertTrue(warm.red > warm.blue)
            assertTrue(cool.blue > cool.red)
            val width = image.width - 2 * inset
            val height = image.height - 2 * inset
            for ((index, color) in listOf(0 to warm, 1 to cool)) {
                val fill = requireNotNull(chart.days[index].balanceFill)
                // Other series cross the fill and their antialiased edges can share its color.
                // Use solid patches wider than a line stroke to identify the filled rectangle.
                val xs = mutableSetOf<Int>()
                val ys = mutableSetOf<Int>()
                for (y in 3 until image.height - 3) for (x in 3 until image.width - 3) {
                    if (matches(image[x, y], color) && (-3..3).all { dy ->
                        (-3..3).all { dx -> matches(image[x + dx, y + dy], color) }
                    }) { xs.add(x); ys.add(y) }
                }
                assertTrue("The difference must be filled between the two values", xs.isNotEmpty())
                val center = inset + width * chart.days[index].x
                val halfWidth = width / 3 * .4 / 2
                assertEquals(center - halfWidth + 3, xs.min().toDouble(), 2.0)
                assertEquals(center + halfWidth - 3, xs.max().toDouble(), 2.0)
                assertEquals(inset + height * fill.topY + 3, ys.min().toDouble(), 3.0)
                assertEquals(inset + height * fill.bottomY - 3, ys.max().toDouble(), 3.0)
                val below = (inset + height * (fill.bottomY + 1.0) / 2).toInt()
                assertFalse("The fill must not extend down to zero", matches(image[center.toInt(), below], color))
            }
            assertNull(chart.days[2].balanceFill)
        }
    }

    private fun matches(pixel: Color, color: Color) = kotlin.math.abs(pixel.red - color.red) < .02 &&
        kotlin.math.abs(pixel.green - color.green) < .02 && kotlin.math.abs(pixel.blue - color.blue) < .02

    @Test fun calorieLinesBreakAtMissingDaysAndKeepIsolatedMarkers() {
        val base = chart()
        var continuous by mutableStateOf(false)
        var intake = Color.Transparent
        var burned = Color.Transparent
        compose.setContent { EnetrendTheme(dynamicColor = false) {
            intake = MaterialTheme.colorScheme.primary
            burned = MaterialTheme.colorScheme.onSurfaceVariant
            val days = base.days.mapIndexed { index, day -> day.copy(
                intakeY = if (index == 1 && !continuous) null else .3,
                burnedY = if (index == 1 && !continuous) null else .7,
                balanceFill = null, periodCumulativeY = null,
            ) }
            Surface { Column { DashboardChart(base.copy(days = days), 2, {}) } }
        } }
        val isolated = pixels()
        assertTrue(count(isolated, intake) > 0)
        val intakeCount = count(isolated, intake)
        val burnedCount = count(isolated, burned)
        for (y in isolated.height / 4 until isolated.height * 3 / 4) {
            assertFalse("Neither series should bridge the missing day", matches(isolated[isolated.width / 2, y], intake) ||
                matches(isolated[isolated.width / 2, y], burned))
        }
        compose.runOnIdle { continuous = true }
        assertTrue(count(pixels(), intake) > intakeCount + 20)
        assertTrue(count(pixels(), burned) > burnedCount + 20)
    }

    @Test fun enlargedLegendAndGraphRemainAccessibleInBothThemes() {
        var dark by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                EnetrendTheme(darkTheme = dark) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { DashboardChart(chart(), 1, {}) }
                }
            }
        }
        compose.onNodeWithText("凡例を開く").performClick()
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode }
            compose.onNodeWithText("● 摂取カロリー").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("■ 消費カロリー").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("2点間の塗りが日別収支", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("摂取・消費 kcal").performScrollTo().assertIsDisplayed()
            val graph = compose.onNodeWithContentDescription("統合グラフ。", substring = true).performScrollTo()
            val description = graph.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            assertTrue(description.contains("摂取：1700.0 kcal"))
            assertTrue(description.contains("消費：2000.0 kcal"))
            assertTrue(description.contains("日別収支：-300.0 kcal"))
        }
    }

    @Test fun isolatedAndMissingAveragePointsPaintNothingButContinuousAveragePaintsLine() {
        val base = chart()
        var values by mutableStateOf(listOf<Double?>(null, null, null))
        var average = Color.Transparent
        compose.setContent { EnetrendTheme(dynamicColor = false) {
            average = MaterialTheme.colorScheme.secondary
            Surface { Column { DashboardChart(base.copy(days = base.days.mapIndexed { index, day ->
                day.copy(movingAverageY = values[index])
            }), 2, {}) } }
        } }
        val without = count(pixels(), average)
        for (isolated in listOf(listOf(null, .3, null), listOf(.3, null, .3))) {
            compose.runOnIdle { values = isolated }
            assertEquals("No markers and no line across a missing day", without, count(pixels(), average))
        }
        compose.runOnIdle { values = listOf(.3, .3, .3) }
        assertTrue(count(pixels(), average) > without + 20)
    }
}
