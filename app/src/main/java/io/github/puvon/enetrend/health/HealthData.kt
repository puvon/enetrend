package io.github.puvon.enetrend.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Local calendar dates [startDate, endDateExclusive), interpreted in the supplied zone. */
data class HealthDataRange(
    val startDate: LocalDate,
    val endDateExclusive: LocalDate,
    val zoneId: ZoneId,
) {
    val startTime: Instant = startDate.atStartOfDay(zoneId).toInstant()
    val endTime: Instant = endDateExclusive.atStartOfDay(zoneId).toInstant()

    init {
        require(startDate < endDateExclusive && startTime < endTime) {
            "The requested date range must contain at least one local day."
        }
    }
}

/** Already aggregated by Health Connect for the entire request; never add raw calories to these. */
data class CalorieTotals(
    val intakeKilocalories: Double?,
    val burnedKilocalories: Double?,
    val dataOrigins: Set<String> = emptySet(),
)

/** A measurement, not a daily representative value. Its recorded offset may be absent. */
data class WeightMeasurement(
    val id: String,
    val time: Instant,
    val zoneOffset: ZoneOffset?,
    val kilograms: Double,
    val dataOrigin: String,
    val lastModifiedTime: Instant,
)

data class WeightPage(val measurements: List<WeightMeasurement>, val nextPageToken: String?)

data class HealthData(
    val range: HealthDataRange,
    val calories: CalorieTotals,
    val weights: List<WeightMeasurement>,
) {
    val isEmpty: Boolean
        get() = calories.intakeKilocalories == null &&
            calories.burnedKilocalories == null && weights.isEmpty()
}

sealed interface HealthDataResult {
    data class Available(val data: HealthData) : HealthDataResult
    /** No readable data returned; not proof of absence outside Health Connect's access limits. */
    data class Empty(val range: HealthDataRange) : HealthDataResult
    data class PermissionsRequired(val missingPermissions: Set<String>) : HealthDataResult
    data object Unavailable : HealthDataResult
    data object UpdateRequired : HealthDataResult
    /** Permission loss during a read, or another access restriction such as historical access. */
    data object AccessDenied : HealthDataResult
    data object Error : HealthDataResult
}

interface HealthDataSource : HealthConnectionGateway {
    suspend fun readCalorieTotals(range: HealthDataRange): CalorieTotals
    suspend fun readWeightPage(range: HealthDataRange, pageToken: String?): WeightPage
}
