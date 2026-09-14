package io.github.puvon.enetrend.health

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CalorieBalanceTest {
    private val start = LocalDate.of(2026, 9, 1)
    private fun range(days: Int) = HealthDataRange(start, start.plusDays(days.toLong()), ZoneId.of("Asia/Tokyo"))
    private fun data(vararg values: Pair<Double?, Double?>) = DailyCalorieData(
        range(values.size), values.mapIndexed { index, pair ->
            start.plusDays(index.toLong()) to CalorieTotals(pair.first, pair.second)
        }.toMap(),
    )

    @Test fun signsAndRecordedZero() {
        val result = CalorieBalanceCalculator.calculate(data(100.0 to 200.0, 300.0 to 200.0, 0.0 to 0.0))
        assertEquals(listOf(-100.0, 100.0, 0.0), result.daily.map { it.kilocalories })
        assertEquals(listOf(-100.0, 0.0, 0.0), result.periodCumulative.map { it.kilocalories })
        assertTrue(result.periodCumulative.all { it.isComplete && !it.isEstimated })
    }

    @Test fun missingEitherSideIsNotZero() {
        val result = CalorieBalanceCalculator.calculate(data(null to 100.0, 100.0 to null, null to null), policy = InterpolationPolicy(0))
        assertTrue(result.daily.all { it.kilocalories == null })
        assertTrue(result.periodCumulative.all { it.kilocalories == null && it.availableDaysSubtotalKilocalories == null })
    }

    @Test fun gapInvalidatesCumulativeButKeepsExplicitSubtotal() {
        val result = CalorieBalanceCalculator.calculate(data(100.0 to 200.0, null to 200.0, 400.0 to 200.0))
        assertEquals(listOf(-100.0, null, null), result.periodCumulative.map { it.kilocalories })
        assertEquals(listOf(-100.0, -100.0, 100.0), result.periodCumulative.map { it.availableDaysSubtotalKilocalories })
        assertEquals(emptySet<LocalDate>(), result.periodCumulative.first().missingDates)
        assertEquals(setOf(start.plusDays(1)), result.periodCumulative.last().missingDates)
    }

    @Test fun interpolationProvenanceSurvivesDailyAndCumulative() {
        val result = CalorieBalanceCalculator.calculate(data(300.0 to 100.0, 300.0 to null, 300.0 to 300.0))
        assertEquals(100.0, result.daily[1].kilocalories!!, 0.0)
        assertTrue(result.daily[1].isEstimated)
        assertEquals(DisplayValue.Interpolated(200.0, start, start.plusDays(2)), result.daily[1].source.burned)
        assertEquals(setOf(start.plusDays(1)), result.periodCumulative.last().estimatedDates)
        assertTrue(result.periodCumulative.last().isComplete)
    }

    @Test fun estimateDoesNotMakeMissingIntakeComputable() {
        val result = CalorieBalanceCalculator.calculate(data(300.0 to 100.0, null to null, 300.0 to 300.0))
        assertNull(result.daily[1].kilocalories)
        assertTrue(result.daily[1].source.burned is DisplayValue.Interpolated)
        assertFalse(result.periodCumulative.last().isComplete)
    }

    @Test fun selectionResetsCumulativeAndRetainsOutsideAnchors() {
        val input = data(300.0 to 100.0, 300.0 to null, 300.0 to 300.0)
        val selected = HealthDataRange(start.plusDays(1), start.plusDays(2), input.range.zoneId)
        val result = CalorieBalanceCalculator.calculate(input, selected)
        assertEquals(1, result.daily.size)
        assertEquals(100.0, result.periodCumulative.single().kilocalories!!, 0.0)
        assertEquals(selected.startDate, result.periodCumulative.single().startDate)
    }

    @Test fun emptyRecordsKeepCalendarDaysMissing() {
        val result = CalorieBalanceCalculator.calculate(DailyCalorieData(range(3), emptyMap()))
        assertEquals(3, result.daily.size)
        assertNull(result.periodCumulative.last().availableDaysSubtotalKilocalories)
        assertEquals(3, result.periodCumulative.last().missingDates.size)
        assertEquals(0.0, result.periodStartKilocalories, 0.0)
        assertTrue(result.periodCumulative.all { it.kilocalories == null })
    }

    @Test fun allNegativeBalancesStartFromZero() {
        val result = CalorieBalanceCalculator.calculate(data(0.0 to 300.0, 0.0 to 500.0, 0.0 to 200.0))
        assertEquals(0.0, result.periodStartKilocalories, 0.0)
        assertEquals(listOf(-300.0, -800.0, -1000.0), result.periodCumulative.map { it.kilocalories })
    }

    @Test fun allPositiveBalances() {
        val result = CalorieBalanceCalculator.calculate(data(300.0 to 0.0, 500.0 to 0.0, 200.0 to 0.0))
        assertEquals(listOf(300.0, 800.0, 1000.0), result.periodCumulative.map { it.kilocalories })
    }

    @Test fun documentedMixedExample() {
        val result = CalorieBalanceCalculator.calculate(data(0.0 to 300.0, 0.0 to 500.0, 200.0 to 0.0, 0.0 to 400.0))
        assertEquals(listOf(-300.0, -800.0, -600.0, -1000.0), result.periodCumulative.map { it.kilocalories })
    }

    @Test fun zeroDayDoesNotResetTheRunningSum() {
        val result = CalorieBalanceCalculator.calculate(data(0.0 to 300.0, 500.0 to 500.0, 0.0 to 200.0))
        assertEquals(listOf(-300.0, -300.0, -500.0), result.periodCumulative.map { it.kilocalories })
    }

    @Test fun oneDayIncludesItsBalanceAfterTheZeroOrigin() {
        val result = CalorieBalanceCalculator.calculate(data(100.0 to 400.0))
        assertEquals(0.0, result.periodStartKilocalories, 0.0)
        assertEquals(-300.0, result.periodCumulative.single().kilocalories!!, 0.0)
        assertEquals(start, result.periodCumulative.single().startDate)
    }

    @Test fun recordsAreAccumulatedByDateNotMapInsertionOrder() {
        val records = linkedMapOf(start.plusDays(2) to CalorieTotals(200.0, 0.0),
            start to CalorieTotals(0.0, 300.0), start.plusDays(1) to CalorieTotals(0.0, 500.0))
        val result = CalorieBalanceCalculator.calculate(DailyCalorieData(range(3), records))
        assertEquals(listOf(start, start.plusDays(1), start.plusDays(2)), result.periodCumulative.map { it.date })
        assertEquals(listOf(-300.0, -800.0, -600.0), result.periodCumulative.map { it.kilocalories })
    }

    @Test fun thirtyAndNinetyDayRangesHaveDifferentTotalsOnTheSameDate() {
        val input = DailyCalorieData(range(90), (0L until 90L).associate { start.plusDays(it) to CalorieTotals(2000.0, 2100.0) })
        val full = CalorieBalanceCalculator.calculate(input)
        val selected = HealthDataRange(start.plusDays(60), start.plusDays(90), input.range.zoneId)
        val short = CalorieBalanceCalculator.calculate(input, selected)
        assertEquals(full.periodCumulative.last().date, short.periodCumulative.last().date)
        assertEquals(-9000.0, full.periodCumulative.last().kilocalories!!, 0.0)
        assertEquals(-3000.0, short.periodCumulative.last().kilocalories!!, 0.0)
        assertEquals(-100.0, short.periodCumulative.first().kilocalories!!, 0.0)
        assertEquals(0.0, short.periodStartKilocalories, 0.0)
    }

    @Test fun missingDaysOutsideSelectionDoNotInvalidateTheSelectedPeriod() {
        val input = data(null to null, 100.0 to 400.0)
        val result = CalorieBalanceCalculator.calculate(input, HealthDataRange(start.plusDays(1), start.plusDays(2), input.range.zoneId))
        assertTrue(result.periodCumulative.single().isComplete)
        assertEquals(-300.0, result.periodCumulative.single().kilocalories!!, 0.0)
    }

    @Test fun decimalAccumulation() {
        val input = DailyCalorieData(range(100), (0L until 100L).associate { start.plusDays(it) to CalorieTotals(0.1, 0.0) })
        assertEquals(10.0, CalorieBalanceCalculator.calculate(input).periodCumulative.last().kilocalories!!, 1e-12)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsDifferentTimeZone() {
        CalorieBalanceCalculator.calculate(data(1.0 to 0.0), HealthDataRange(start, start.plusDays(1), ZoneId.of("UTC")))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsRangeOutsideLoadedData() {
        CalorieBalanceCalculator.calculate(data(1.0 to 0.0), range(2))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsNonFiniteEnergy() {
        CalorieBalanceCalculator.calculate(data(Double.NaN to 0.0))
    }
}
