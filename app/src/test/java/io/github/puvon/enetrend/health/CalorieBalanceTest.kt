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
        assertEquals(listOf(-100.0, 0.0, 0.0), result.cumulative.map { it.kilocalories })
        assertTrue(result.cumulative.all { it.isComplete && !it.isEstimated })
    }

    @Test fun missingEitherSideIsNotZero() {
        val result = CalorieBalanceCalculator.calculate(data(null to 100.0, 100.0 to null, null to null), policy = InterpolationPolicy(0))
        assertTrue(result.daily.all { it.kilocalories == null })
        assertTrue(result.cumulative.all { it.kilocalories == null && it.availableDaysSubtotalKilocalories == null })
    }

    @Test fun gapInvalidatesCumulativeButKeepsExplicitSubtotal() {
        val result = CalorieBalanceCalculator.calculate(data(100.0 to 200.0, null to 200.0, 400.0 to 200.0))
        assertEquals(listOf(-100.0, null, null), result.cumulative.map { it.kilocalories })
        assertEquals(listOf(-100.0, -100.0, 100.0), result.cumulative.map { it.availableDaysSubtotalKilocalories })
        assertEquals(emptySet<LocalDate>(), result.cumulative.first().missingDates)
        assertEquals(setOf(start.plusDays(1)), result.cumulative.last().missingDates)
    }

    @Test fun interpolationProvenanceSurvivesDailyAndCumulative() {
        val result = CalorieBalanceCalculator.calculate(data(300.0 to 100.0, 300.0 to null, 300.0 to 300.0))
        assertEquals(100.0, result.daily[1].kilocalories!!, 0.0)
        assertTrue(result.daily[1].isEstimated)
        assertEquals(DisplayValue.Interpolated(200.0, start, start.plusDays(2)), result.daily[1].source.burned)
        assertEquals(setOf(start.plusDays(1)), result.cumulative.last().estimatedDates)
        assertTrue(result.cumulative.last().isComplete)
    }

    @Test fun estimateDoesNotMakeMissingIntakeComputable() {
        val result = CalorieBalanceCalculator.calculate(data(300.0 to 100.0, null to null, 300.0 to 300.0))
        assertNull(result.daily[1].kilocalories)
        assertTrue(result.daily[1].source.burned is DisplayValue.Interpolated)
        assertFalse(result.cumulative.last().isComplete)
    }

    @Test fun selectionResetsCumulativeAndRetainsOutsideAnchors() {
        val input = data(300.0 to 100.0, 300.0 to null, 300.0 to 300.0)
        val selected = HealthDataRange(start.plusDays(1), start.plusDays(2), input.range.zoneId)
        val result = CalorieBalanceCalculator.calculate(input, selected)
        assertEquals(1, result.daily.size)
        assertEquals(100.0, result.cumulative.single().kilocalories!!, 0.0)
        assertEquals(selected.startDate, result.cumulative.single().startDate)
    }

    @Test fun emptyRecordsKeepCalendarDaysMissing() {
        val result = CalorieBalanceCalculator.calculate(DailyCalorieData(range(3), emptyMap()))
        assertEquals(3, result.daily.size)
        assertNull(result.cumulative.last().availableDaysSubtotalKilocalories)
        assertEquals(3, result.cumulative.last().missingDates.size)
    }

    @Test fun decimalAccumulation() {
        val input = DailyCalorieData(range(100), (0L until 100L).associate { start.plusDays(it) to CalorieTotals(0.1, 0.0) })
        assertEquals(10.0, CalorieBalanceCalculator.calculate(input).cumulative.last().kilocalories!!, 1e-12)
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
