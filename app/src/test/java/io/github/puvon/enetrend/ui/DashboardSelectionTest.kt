package io.github.puvon.enetrend.ui

import org.junit.Assert.*
import org.junit.Test

class DashboardSelectionTest {
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
