package io.github.puvon.enetrend.health

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** One snapshot supplies both the local date and the read timestamp. */
data class DashboardReadTime(val instant: Instant, val zone: ZoneId) {
    val date = instant.atZone(zone).toLocalDate()
    val millisUntilNextDay: Long
        get() = Duration.between(instant, date.plusDays(1).atStartOfDay(zone).toInstant())
            .toMillis().coerceAtLeast(1)

    fun hasSameDayAndZone(other: DashboardReadTime) = date == other.date && zone == other.zone
}

class DashboardTimeSource(
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    fun now(): DashboardReadTime {
        val currentZone = zone()
        return DashboardReadTime(clock.instant(), currentZone)
    }
}
