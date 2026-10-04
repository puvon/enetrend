package io.github.puvon.enetrend.ui

import io.github.puvon.enetrend.health.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DashboardChartTest {
    private val start = LocalDate.of(2026, 9, 1)
    private fun range(n: Int) = HealthDataRange(start, start.plusDays(n.toLong()), ZoneId.of("Asia/Tokyo"))
    private fun weight(day: Int, value: DisplayValue, average: Double? = null) = DailyWeightTrend(
        start.plusDays(day.toLong()), null, value,
        WeightMovingAverage(average, if (average == null) 0 else 1, MovingAveragePeriod.SEVEN_DAYS, false),
    )
    private fun data(values: List<Double?>, weights: List<DailyWeightTrend> = emptyList()): DashboardData {
        val input = DailyCalorieData(range(values.size), values.mapIndexed { i, value ->
            start.plusDays(i.toLong()) to CalorieTotals(value?.let { 20000.0 + it }, 20000.0)
        }.toMap())
        return DashboardData(CalorieBalanceCalculator.calculate(input), weights, false)
    }
    private fun close(expected: Double, actual: Double?) = assertEquals(expected, requireNotNull(actual), 1e-10)

    @Test fun joinsByDateEvenWhenAllInputsAreReordered() {
        val original = data(listOf(-300.0, 100.0, 0.0), listOf(
            weight(2, DisplayValue.Recorded(72.0), 71.0), weight(0, DisplayValue.Recorded(70.0), 70.0)))
        val input = original.copy(balances = original.balances.copy(
            daily = original.balances.daily.reversed(), periodCumulative = original.balances.periodCumulative.reversed()))
        val chart = DashboardChartProjector.project(input)
        assertEquals(listOf(start, start.plusDays(1), start.plusDays(2)), chart.days.map { it.date })
        close(-200.0, chart.days[2].periodCumulative?.kilocalories)
        assertEquals(DisplayValue.Recorded(72.0), chart.days[2].weight?.display)
        close(chart.weightScale!!.y(71.0), chart.days[2].movingAverageY)
        assertNull(chart.days[1].weightY)
        close(1.0 / 6, chart.days.first().x)
        close(5.0 / 6, chart.days.last().x)
        close(0.0, chart.periodStartX)
    }

    @Test fun cumulativeUsesWeightScaleAndPreservesRealValues() {
        val input = data(listOf(-7000.0, 14000.0), listOf(weight(0, DisplayValue.Recorded(70.0))))
        val chart = DashboardChartProjector.project(input)
        assertTrue(chart.isPeriodCumulativeWeightLinked)
        assertNull(chart.independentPeriodCumulativeScale)
        close(69.0, chart.weightScale?.min)
        close(71.0, chart.weightScale?.max)
        close(0.5, chart.periodStartY)
        close(1.0, chart.days[0].periodCumulativeY)
        close(0.0, chart.days[1].periodCumulativeY)
        assertSame(input.balances.periodCumulative[0], chart.days[0].periodCumulative)
    }

    @Test fun earliestInterpolationCentersAxisAndCumulative() {
        val interpolated = DisplayValue.Interpolated(69.0, start.minusDays(1), start.plusDays(1))
        val chart = DashboardChartProjector.project(data(listOf(0.0, 0.0), listOf(
            weight(0, interpolated), weight(1, DisplayValue.Recorded(70.0)))))
        assertEquals(start, chart.baseline?.date)
        close(69.0, chart.baseline?.kilograms)
        close(0.5, chart.periodStartY)
        assertEquals(interpolated, chart.days[0].weight?.display)
    }

    @Test fun interpolationOnlyBaselineRetainsItsProvenance() {
        val source = DisplayValue.Interpolated(70.0, start.minusDays(1), start.plusDays(2))
        val chart = DashboardChartProjector.project(data(listOf(-300.0), listOf(weight(0, source))))
        assertEquals(source, chart.baseline?.source)
        close(70.0, chart.baseline?.kilograms)
    }

    @Test fun noWeightUsesZeroCenteredKilogramConversion() {
        val chart = DashboardChartProjector.project(data(listOf(-300.0, -14000.0)))
        assertFalse(chart.isPeriodCumulativeWeightLinked)
        assertNull(chart.independentPeriodCumulativeScale)
        close(-14300.0 / 7000, chart.weightScale?.min)
        close(14300.0 / 7000, chart.weightScale?.max)
        close(0.5, chart.periodStartY)
        close(0.0, chart.dailyScale.min)
        assertTrue(chart.days.all { it.weightY == null })
    }

    @Test fun dailyScaleIsIndependentOfCumulativeMagnitudeAndCoefficient() {
        val input = data(List(30) { -300.0 }, listOf(weight(0, DisplayValue.Recorded(70.0))))
        val default = DashboardChartProjector.project(input)
        val changed = DashboardChartProjector.project(input, 3500.0)
        assertEquals(default.dailyScale, changed.dailyScale)
        close(0.0, default.dailyScale.min)
        close(20000.0, default.dailyScale.max)
        close(70.0 - 9000.0 / 3500.0, changed.weightScale?.min)
        assertEquals(default.days.map { it.periodCumulative }, changed.days.map { it.periodCumulative })
    }

    @Test fun missingDailyKeepsReferenceCumulativeCoordinates() {
        val chart = DashboardChartProjector.project(data(listOf(-300.0, null, 200.0)))
        assertNull(chart.days[1].intakeY)
        assertNotNull(chart.days[1].burnedY)
        assertNull(chart.days[1].balanceFill)
        close(chart.days[0].periodCumulativeY!!, chart.days[1].periodCumulativeY)
        assertNotNull(chart.days[2].periodCumulativeY)
        close(-100.0, chart.days[2].periodCumulative?.availableDaysSubtotalKilocalories)
        assertEquals(setOf(start.plusDays(1)), chart.days[2].periodCumulative?.missingDates)
    }

    @Test fun emptyDataKeepsDatesAndNullCoordinates() {
        val chart = DashboardChartProjector.project(DashboardData(CalorieBalanceSeries(range(2), emptyList(), emptyList()), emptyList(), false))
        assertEquals(2, chart.days.size)
        assertTrue(chart.days.all { it.intakeY == null && it.burnedY == null && it.balanceFill == null &&
            it.periodCumulativeY == null && it.weightY == null && it.movingAverageY == null })
        assertTrue(chart.periodStartY.isFinite())
        assertFalse(chart.hasPeriodCumulative)
        assertNull(chart.weightScale)
    }

    @Test fun singleZeroDayAndConstantWeightHaveFiniteCoordinates() {
        val chart = DashboardChartProjector.project(data(listOf(0.0), listOf(weight(0, DisplayValue.Recorded(70.0), 70.0))))
        close(0.5, chart.days.single().x)
        close(requireNotNull(chart.days.single().intakeY), chart.days.single().burnedY)
        assertNull(chart.days.single().balanceFill)
        close(chart.periodStartY, chart.days.single().periodCumulativeY)
        close(0.5, chart.days.single().weightY)
        close(0.5, chart.days.single().movingAverageY)
    }

    @Test fun intakeIsAboveConsumptionForSurplusAndBelowForDeficit() {
        val chart = DashboardChartProjector.project(data(listOf(-300.0, 600.0, 0.0)))
        assertTrue(requireNotNull(chart.days[0].intakeY) > requireNotNull(chart.days[0].burnedY))
        assertFalse(requireNotNull(chart.days[0].balanceFill).isPositive)
        assertTrue(requireNotNull(chart.days[1].intakeY) < requireNotNull(chart.days[1].burnedY))
        assertTrue(requireNotNull(chart.days[1].balanceFill).isPositive)
        close(requireNotNull(chart.days[2].intakeY), chart.days[2].burnedY)
        assertNull(chart.days[2].balanceFill)
    }

    @Test fun averageWithoutInRangeWeightCentersAxisAndCumulative() {
        val chart = DashboardChartProjector.project(data(listOf(-300.0), listOf(weight(0, DisplayValue.Missing, 70.0))))
        assertTrue(chart.baseline!!.isMovingAverage)
        close(70.0, chart.baseline?.kilograms)
        close(0.5, chart.periodStartY)
        assertNull(chart.independentPeriodCumulativeScale)
        assertNull(chart.days.single().weightY)
        close(0.5, chart.days.single().movingAverageY)
    }

    @Test fun changingRangeReselectsBaselineAndExcludesEarlierBalances() {
        val input = data(listOf(-7000.0, 0.0), listOf(
            weight(0, DisplayValue.Recorded(70.0)), weight(1, DisplayValue.Recorded(71.0))))
        val full = DashboardChartProjector.project(input)
        val selected = HealthDataRange(start.plusDays(1), start.plusDays(2), range(2).zoneId)
        val calories = DailyCalorieData(range(2), mapOf(start to CalorieTotals(0.0, 7000.0),
            start.plusDays(1) to CalorieTotals(0.0, 0.0)))
        val short = DashboardChartProjector.project(DashboardData(
            CalorieBalanceCalculator.calculate(calories, selected), input.weights.takeLast(1), false))
        close(70.0, full.baseline?.kilograms)
        close(71.0, short.baseline?.kilograms)
        close(0.0, short.days.single().periodCumulative?.kilocalories)
        close(short.periodStartY, short.days.single().periodCumulativeY)
    }

    @Test fun estimatedBalanceAndMissingFirstDayKeepSourceFlags() {
        val calories = DailyCalorieData(range(3), mapOf(start to CalorieTotals(null, 100.0),
            start.plusDays(1) to CalorieTotals(300.0, null), start.plusDays(2) to CalorieTotals(300.0, 300.0)))
        val balances = CalorieBalanceCalculator.calculate(calories)
        val chart = DashboardChartProjector.project(DashboardData(balances, emptyList(), false))
        assertTrue(chart.days[1].daily!!.isEstimated)
        assertTrue(requireNotNull(chart.days[1].balanceFill).isEstimated)
        close(chart.dailyScale.y(200.0), chart.days[1].burnedY)
        assertTrue(chart.days[1].periodCumulative!!.isEstimated)
        close(chart.periodStartY, chart.days.first().periodCumulativeY)
        assertTrue(chart.days.all { it.periodCumulativeY != null })
        assertTrue(chart.periodStartY.isFinite())
    }

    @Test fun calorieTicksStartAtZeroAndContainAllValuesWithNiceIntegers() {
        for (values in listOf(emptyList(), listOf(0.0), listOf(0.1),
            listOf(713.5, 25.0), listOf(30000.0))) {
            val scale = ChartScale.daily(values)
            close(1.0, scale.y(0.0))
            close(0.0, scale.min)
            val step = (scale.max - scale.min) / 4
            assertTrue(step >= 5.0)
            close(0.0, step % 5)
            values.forEach { assertTrue(scale.y(it) in 0.0..1.0) }
        }
    }

    @Test fun fillsJoinTheTwoActualValuesWithoutRoundingOrZeroBaseline() {
        val totals = listOf(CalorieTotals(2400.0, 2000.0), CalorieTotals(1800.0, 2200.0),
            CalorieTotals(0.0, 2000.0), CalorieTotals(2000.001, 2000.0), CalorieTotals(0.0, 0.0))
        val calories = DailyCalorieData(range(totals.size), totals.mapIndexed { i, value ->
            start.plusDays(i.toLong()) to value
        }.toMap())
        val input = DashboardData(CalorieBalanceCalculator.calculate(calories), emptyList(), false)
        val chart = DashboardChartProjector.project(input)
        for (i in 0..3) {
            val day = chart.days[i]
            val fill = requireNotNull(day.balanceFill)
            val intake = requireNotNull(totals[i].intakeKilocalories)
            val burned = requireNotNull(totals[i].burnedKilocalories)
            close(chart.dailyScale.y(maxOf(intake, burned)), fill.topY)
            close(chart.dailyScale.y(minOf(intake, burned)), fill.bottomY)
            assertEquals(intake > burned, fill.isPositive)
            assertFalse(fill.isEstimated)
            assertSame(input.balances.daily[i], day.daily)
            assertSame(input.balances.periodCumulative[i], day.periodCumulative)
        }
        close(1.0, chart.days[4].intakeY)
        close(1.0, chart.days[4].burnedY)
        assertNull(chart.days[4].balanceFill)
    }

    @Test fun intakeOnlyConsumptionOnlyAndLongGapsKeepIndependentCoordinates() {
        val calories = DailyCalorieData(range(10), mapOf(
            start to CalorieTotals(3000.0, null), start.plusDays(1) to CalorieTotals(null, 4000.0),
            start.plusDays(9) to CalorieTotals(2000.0, 2500.0)))
        val chart = DashboardChartProjector.project(DashboardData(
            CalorieBalanceCalculator.calculate(calories), emptyList(), false))
        assertNotNull(chart.days[0].intakeY)
        assertNull(chart.days[0].burnedY)
        assertNull(chart.days[0].balanceFill)
        assertNull(chart.days[1].intakeY)
        close(chart.dailyScale.y(4000.0), chart.days[1].burnedY)
        assertNull(chart.days[1].balanceFill)
        assertTrue(chart.days.subList(2, 9).all { it.intakeY == null && it.burnedY == null && it.balanceFill == null })
        assertTrue(chart.dailyScale.max >= 4000.0)
    }

    @Test fun rejectsInvalidCoefficients() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { coefficient ->
            assertThrows(IllegalArgumentException::class.java) { DashboardChartProjector.project(data(listOf(0.0)), coefficient) }
        }
    }

    @Test fun rejectsDuplicateAndOutOfRangeDates() {
        val input = data(listOf(0.0))
        assertThrows(IllegalArgumentException::class.java) {
            DashboardChartProjector.project(input.copy(weights = listOf(weight(0, DisplayValue.Missing), weight(0, DisplayValue.Missing))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashboardChartProjector.project(input.copy(weights = listOf(weight(1, DisplayValue.Recorded(70.0)))))
        }
    }
}
