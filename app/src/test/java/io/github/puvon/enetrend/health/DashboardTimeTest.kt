package io.github.puvon.enetrend.health

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DashboardTimeTest {
    @Test fun midnightWaitUsesCalendarBoundaryIncludingShortAndLongDstDays() {
        val zone = ZoneId.of("America/New_York")
        for ((instant, hours) in listOf(
            "2026-03-08T05:00:00Z" to 23,
            "2026-11-01T04:00:00Z" to 25,
            "2026-11-02T05:00:00Z" to 24,
        )) {
            assertEquals(hours * 3_600_000L, DashboardReadTime(Instant.parse(instant), zone).millisUntilNextDay)
        }
    }

    @Test fun fractionalLastMillisecondDoesNotBusyLoopAndYearRolloverUsesLocalDate() {
        val time = DashboardReadTime(Instant.parse("2026-12-31T14:59:59.999999999Z"), ZoneId.of("Asia/Tokyo"))
        assertEquals("2026-12-31", time.date.toString())
        assertEquals(1L, time.millisUntilNextDay)
        assertFalse(time.hasSameDayAndZone(time.copy(instant = Instant.parse("2026-12-31T15:00:00Z"))))
    }

    @Test fun zoneChangeInvalidatesEvenIfDateAndOffsetAreUnchanged() {
        val time = DashboardReadTime(Instant.parse("2026-10-01T03:00:00Z"), ZoneId.of("Asia/Tokyo"))
        assertFalse(time.hasSameDayAndZone(time.copy(zone = ZoneId.of("Asia/Seoul"))))
        assertTrue(time.hasSameDayAndZone(time.copy(instant = time.instant.plusSeconds(60))))
    }
}
