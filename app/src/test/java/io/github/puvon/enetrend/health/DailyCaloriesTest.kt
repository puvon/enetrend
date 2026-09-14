package io.github.puvon.enetrend.health

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DailyCaloriesTest {
    private val start = LocalDate.of(2026, 9, 1)
    private fun range(days: Long) = HealthDataRange(start, start.plusDays(days), ZoneId.of("Asia/Tokyo"))

    @Test fun emptyDaysAreRetainedAsMissingAndNotZero() {
        val data = DailyCalorieData(range(3), emptyMap())
        assertFalse(data.hasRecordedData)
        assertEquals(3, data.forDisplay().size)
        assertTrue(data.forDisplay().all { it.intake == DisplayValue.Missing && it.burned == DisplayValue.Missing })
    }

    @Test fun intakeIsNeverInterpolatedAndRecordedZeroIsPreserved() {
        val recorded = mapOf(
            start to CalorieTotals(1000.0, 2000.0),
            start.plusDays(1) to CalorieTotals(null, null),
            start.plusDays(2) to CalorieTotals(0.0, 2200.0),
        )
        val data = DailyCalorieData(range(3), recorded)
        val display = data.forDisplay()
        assertEquals(DisplayValue.Missing, display[1].intake)
        assertEquals(DisplayValue.Recorded(0.0), display[2].intake)
        assertEquals(DisplayValue.Interpolated(2100.0, start, start.plusDays(2)), display[1].burned)
        assertNull(display[1].recorded.burnedKilocalories)
        assertEquals(recorded, data.recorded)
    }

    @Test fun sixMissingDaysAreFilledButSevenAreNot() {
        for (gap in listOf(6L, 7L)) {
            val end = start.plusDays(gap + 1)
            val output = DailyInterpolation.project(range(gap + 2), mapOf(start to 100.0, end to 800.0))
            for (offset in 1..gap) {
                val value = output.getValue(start.plusDays(offset))
                if (gap == 6L) {
                    assertTrue(value is DisplayValue.Interpolated)
                    assertEquals(100.0 + offset * 100.0, (value as DisplayValue.Interpolated).value, 0.000001)
                } else assertEquals(DisplayValue.Missing, value)
            }
        }
    }

    @Test fun missingLeadingAndTrailingDaysAreNotExtrapolated() {
        val output = DailyInterpolation.project(range(5), mapOf(start.plusDays(2) to 70.0))
        assertEquals(4, output.values.count { it == DisplayValue.Missing })
        assertEquals(DisplayValue.Recorded(70.0), output.getValue(start.plusDays(2)))
    }

    @Test fun configurableThresholdAndZeroDisableFilling() {
        val values = mapOf(start to 60.0, start.plusDays(3) to 63.0)
        assertEquals(DisplayValue.Missing, DailyInterpolation.project(range(4), values, InterpolationPolicy(1))[start.plusDays(1)])
        assertTrue(DailyInterpolation.project(range(4), values, InterpolationPolicy(2))[start.plusDays(1)] is DisplayValue.Interpolated)
        assertEquals(DisplayValue.Missing, DailyInterpolation.project(range(4), values, InterpolationPolicy(0))[start.plusDays(1)])
    }

    @Test fun newlyRecordedValueReplacesTheEstimateAndIsTheNewAnchor() {
        val values = mutableMapOf<LocalDate, Double?>(start to 60.0, start.plusDays(4) to 64.0)
        val original = DailyInterpolation.project(range(5), values)
        values[start.plusDays(2)] = 64.0
        val updated = DailyInterpolation.project(range(5), values)
        assertEquals(DisplayValue.Recorded(64.0), updated[start.plusDays(2)])
        assertEquals(62.0, (updated[start.plusDays(1)] as DisplayValue.Interpolated).value, 0.000001)
        assertEquals(61.0, (original[start.plusDays(1)] as DisplayValue.Interpolated).value, 0.000001)
        assertEquals(3, values.size)
    }

    @Test fun calendarDaysRatherThanElapsedHoursDetermineInterpolation() {
        val date = LocalDate.of(2026, 3, 7)
        val range = HealthDataRange(date, date.plusDays(3), ZoneId.of("America/New_York"))
        val output = DailyInterpolation.project(range, mapOf(date to 100.0, date.plusDays(2) to 300.0))
        assertEquals(200.0, (output[date.plusDays(1)] as DisplayValue.Interpolated).value, 0.000001)
    }

    @Test fun suppliedOutsideAnchorsCanFillAWindowButCannotHideALongGap() {
        val oneDay = range(1)
        val short = mapOf(start.minusDays(1) to 60.0, start.plusDays(1) to 62.0)
        assertEquals(DisplayValue.Interpolated(61.0, start.minusDays(1), start.plusDays(1)), DailyInterpolation.project(oneDay, short)[start])
        val long = mapOf(start.minusDays(4) to 60.0, start.plusDays(4) to 68.0)
        assertEquals(DisplayValue.Missing, DailyInterpolation.project(oneDay, long)[start])
    }

    @Test fun inputOrderAndRepeatedProjectionDoNotAffectValues() {
        val values = linkedMapOf(start.plusDays(2) to 80.0, start to 60.0)
        assertEquals(DailyInterpolation.project(range(3), values), DailyInterpolation.project(range(3), values.toSortedMap()))
        assertEquals(2, values.size)
    }

    @Test fun recordedDoesNotClaimTheDayIsComplete() {
        val totals = CalorieTotals(500.0, null, setOf("source"))
        val row = DailyCalorieData(range(1), mapOf(start to totals)).forDisplay().single()
        assertEquals(totals, row.recorded)
        assertEquals(DisplayValue.Recorded(500.0), row.intake)
        assertEquals(DisplayValue.Missing, row.burned)
    }
}
