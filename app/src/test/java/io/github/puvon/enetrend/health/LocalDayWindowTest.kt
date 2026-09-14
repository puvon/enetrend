package io.github.puvon.enetrend.health

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LocalDayWindowTest {
    @Test fun localMidnightIsInclusiveAndTheNextMidnightIsExclusive() {
        val day = LocalDayWindow(LocalDate.of(2026, 9, 14), ZoneId.of("Asia/Tokyo"))
        assertEquals(Instant.parse("2026-09-13T15:00:00Z"), day.startTime)
        assertTrue(day.contains(day.startTime))
        assertTrue(day.contains(day.endTime.minusNanos(1)))
        assertFalse(day.contains(day.startTime.minusNanos(1)))
        assertFalse(day.contains(day.endTime))
    }

    @Test fun daylightSavingWindowsJoinWithoutGapsOrOverlap() {
        val zone = ZoneId.of("America/New_York")
        for ((date, hours) in listOf(LocalDate.of(2026, 3, 8) to 23L, LocalDate.of(2026, 11, 1) to 25L)) {
            val range = HealthDataRange(date.minusDays(1), date.plusDays(2), zone)
            val days = range.days().toList()
            assertEquals(3, days.size)
            assertEquals(hours, Duration.between(days[1].startTime, days[1].endTime).toHours())
            assertEquals(range.startTime, days.first().startTime)
            assertEquals(range.endTime, days.last().endTime)
            days.zipWithNext().forEach { (previous, next) -> assertEquals(previous.endTime, next.startTime) }
        }
    }

    @Test fun zoneIsExplicitAndDoesNotDependOnTheRecordedOffset() {
        val date = LocalDate.of(2026, 9, 14)
        val instant = Instant.parse("2026-09-13T16:00:00Z")
        assertTrue(LocalDayWindow(date, ZoneId.of("Asia/Tokyo")).contains(instant))
        assertFalse(LocalDayWindow(date, ZoneId.of("UTC")).contains(instant))
    }

    @Test fun leapDayAndExclusiveEndArePreserved() {
        val range = HealthDataRange(LocalDate.of(2024, 2, 28), LocalDate.of(2024, 3, 1), ZoneId.of("UTC"))
        assertEquals(listOf(LocalDate.of(2024, 2, 28), LocalDate.of(2024, 2, 29)), range.days().map { it.date }.toList())
    }

    @Test fun skippedCalendarDayHasNoInstantsRatherThanAnInvented24Hours() {
        val range = HealthDataRange(LocalDate.of(2011, 12, 29), LocalDate.of(2012, 1, 1), ZoneId.of("Pacific/Apia"))
        val days = range.days().toList()
        assertTrue(days[1].isEmpty)
        assertFalse(days[1].contains(days[1].startTime))
        assertEquals(days[0].endTime, days[2].startTime)
    }
}
