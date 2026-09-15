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

    @Test fun signedBarsStayWarmAndCoolInBothThemes() {
        var dark by mutableStateOf(false)
        var warm = Color.Transparent
        var cool = Color.Transparent
        compose.setContent { EnetrendTheme(darkTheme = dark) {
            val colors = LocalBalanceColors.current
            warm = colors.positive.copy(alpha = .35f).compositeOver(MaterialTheme.colorScheme.surface)
            cool = colors.negative.copy(alpha = .35f).compositeOver(MaterialTheme.colorScheme.surface)
            Surface { Column { DashboardChart(chart(), 2, {}) } }
        } }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode }
            val image = pixels()
            assertTrue(count(image, warm) > 100)
            assertTrue(count(image, cool) > 100)
            assertTrue(warm.red > warm.blue)
            assertTrue(cool.blue > cool.red)
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
