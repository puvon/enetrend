package io.github.puvon.enetrend.ui

import org.junit.Assert.*
import org.junit.Test
import io.github.puvon.enetrend.health.HealthDataRange
import java.time.LocalDate
import java.time.ZoneId

class DashboardSelectionTest {
    @Test fun selectionKeepsInRangeDateAndResolvesBothOutsideBoundariesToEnd() {
        val end = LocalDate.of(2026, 10, 1)
        val range = HealthDataRange(end.minusDays(7), end, ZoneId.of("Asia/Tokyo"))
        assertEquals(end.minusDays(7), selectedChartDate(range.startDate.toString(), range))
        assertEquals(end.minusDays(3), selectedChartDate(end.minusDays(3).toString(), range))
        for (saved in listOf(null, "invalid", end.toString(), end.minusDays(8).toString())) {
            assertEquals(end.minusDays(1), selectedChartDate(saved, range))
        }
    }

    @Test fun calorieDisplayRoundsOnlyAtPresentationAndKeepsNegativeSign() {
        assertEquals("1999.9 kcal", 1999.94.formatted("kcal"))
        assertEquals("-500.0 kcal", (-500.0).formatted("kcal"))
        assertEquals("0.0 kcal", 0.0.formatted("kcal"))
        assertEquals("算出不可", (null as Double?).formatted("kcal", "算出不可"))
    }
    @Test fun plotMarginsAndStartBoundarySelectFirstAndLastDays() {
        assertEquals(0, chartDayAt(0.0, 310.0, 5.0, 3))
        assertEquals(0, chartDayAt(5.0, 310.0, 5.0, 3))
        assertEquals(2, chartDayAt(305.0, 310.0, 5.0, 3))
        assertEquals(2, chartDayAt(310.0, 310.0, 5.0, 3))
    }
    @Test fun centersAndBoundariesMapToCalendarDaySlots() {
        assertEquals(0, chartDayAt(55.0, 310.0, 5.0, 3))
        assertEquals(1, chartDayAt(155.0, 310.0, 5.0, 3))
        assertEquals(2, chartDayAt(255.0, 310.0, 5.0, 3))
        assertEquals(0, chartDayAt(104.9, 310.0, 5.0, 3))
        assertEquals(1, chartDayAt(105.0, 310.0, 5.0, 3))
    }
    @Test fun singleDayAlwaysSelectsOnlyDay() {
        listOf(0.0, 150.0, 310.0).forEach { assertEquals(0, chartDayAt(it, 310.0, 5.0, 1)) }
    }
    @Test fun invalidGeometryAndEmptyDataDoNotSelect() {
        assertNull(chartDayAt(1.0, 10.0, 5.0, 3))
        assertNull(chartDayAt(1.0, 310.0, 5.0, 0))
        assertNull(chartDayAt(Double.NaN, 310.0, 5.0, 3))
        assertNull(chartDayAt(1.0, Double.POSITIVE_INFINITY, 5.0, 3))
    }
}
