package io.github.puvon.enetrend.health

import java.time.Instant
import java.time.LocalDate

const val FITBIT_PACKAGE = "com.fitbit.FitbitMobile"

enum class OptionalReadState { AVAILABLE, PERMISSION_REQUIRED, ACCESS_DENIED, ERROR }

data class OptionalHealthValue<T>(val value: T? = null, val state: OptionalReadState = OptionalReadState.AVAILABLE)

enum class BodyMeasurementKind { WEIGHT_KG, FAT_PERCENT, LEAN_KG }

data class BodyMeasurement(
    val id: String,
    val time: Instant,
    val modified: Instant,
    val kind: BodyMeasurementKind,
    val value: Double,
    val origin: String = FITBIT_PACKAGE,
)

data class MetabolismInputs(
    val measurements: List<BodyMeasurement> = emptyList(),
    val active: Map<LocalDate, OptionalHealthValue<Double>> = emptyMap(),
    val fitbitTotals: Map<LocalDate, OptionalHealthValue<Double>> = emptyMap(),
    val bodyIssues: Set<OptionalReadState> = emptySet(),
    val bodyReadStates: Map<BodyMeasurementKind, Set<OptionalReadState>> = emptyMap(),
)

enum class MetabolismFallback { TODAY, INSUFFICIENT_DAYS, STALE_MEASUREMENT, ACTIVE_MISSING, INVALID_VALUE }

data class MetabolismEstimate(
    val restingKilocaloriesPerDay: Double?,
    val activeKilocalories: Double?,
    val totalKilocalories: Double?,
    val recordedDays: Int,
    val latestMeasurementDate: LocalDate?,
    val fallback: MetabolismFallback?,
    val activeState: OptionalReadState,
    val bodyIssues: Set<OptionalReadState>,
    val fitbitTotalKilocalories: Double?,
    val fitbitTotalState: OptionalReadState = OptionalReadState.AVAILABLE,
    val bodyReadStates: Map<BodyMeasurementKind, Set<OptionalReadState>> = emptyMap(),
    val bodyRecordedDays: Map<BodyMeasurementKind, Int> = emptyMap(),
)

/** Daily representatives first, then a trailing calendar mean; no interpolation or future values. */
object MetabolismCalculator {
    fun calculate(inputs: MetabolismInputs, range: HealthDataRange, today: LocalDate): Map<LocalDate, MetabolismEstimate> {
        val unique = inputs.measurements.filter { it.origin == FITBIT_PACKAGE }
            .groupBy { it.kind to it.id }.values.map { records ->
                records.maxWith(compareBy(BodyMeasurement::modified, BodyMeasurement::time).thenBy { it.value })
            }
        val valid = unique.filter { it.id.isNotEmpty() && it.value.isFinite() && when (it.kind) {
            BodyMeasurementKind.FAT_PERCENT -> it.value >= 0 && it.value < 100
            else -> it.value > 0
        } }
        val daily = valid.groupBy { it.time.atZone(range.zoneId).toLocalDate() }.mapValues { (_, records) ->
            val representatives = records.groupBy { it.kind }.mapValues { (_, samples) ->
                samples.maxWith(compareBy(BodyMeasurement::time, BodyMeasurement::modified).thenBy { it.id }).value
            }
            representatives[BodyMeasurementKind.LEAN_KG] ?: representatives[BodyMeasurementKind.WEIGHT_KG]?.let { weight ->
                representatives[BodyMeasurementKind.FAT_PERCENT]?.let { fat -> weight * (1 - fat / 100) }
            }
        }.filterValues { it != null }
        return range.days().associate { day ->
            val samples = daily.filterKeys { it >= day.date.minusDays(13) && it <= day.date }
            val latest = samples.keys.maxOrNull()
            val active = inputs.active[day.date] ?: OptionalHealthValue()
            val rmr = if (samples.size >= 3 && latest != null && latest >= day.date.minusDays(6) && day.date < today)
                (370 + 21.6 * samples.values.mapNotNull { it }.average()).takeIf { it.isFinite() } else null
            val activity = active.value?.takeIf { active.state == OptionalReadState.AVAILABLE && it.isFinite() && it >= 0 }
            val total = if (rmr != null && activity != null) (rmr + activity).takeIf { it.isFinite() } else null
            val fallback = when {
                day.date >= today -> MetabolismFallback.TODAY
                samples.size < 3 -> MetabolismFallback.INSUFFICIENT_DAYS
                latest == null || latest < day.date.minusDays(6) -> MetabolismFallback.STALE_MEASUREMENT
                rmr == null -> MetabolismFallback.INVALID_VALUE
                activity == null -> if (active.value != null) MetabolismFallback.INVALID_VALUE else MetabolismFallback.ACTIVE_MISSING
                total == null -> MetabolismFallback.INVALID_VALUE
                else -> null
            }
            val fitbitTotal = inputs.fitbitTotals[day.date] ?: OptionalHealthValue()
            val bodyDays = BodyMeasurementKind.entries.associateWith { kind -> valid.asSequence()
                .filter { it.kind == kind }
                .map { it.time.atZone(range.zoneId).toLocalDate() }
                .filter { it >= day.date.minusDays(13) && it <= day.date }
                .distinct().count()
            }
            day.date to MetabolismEstimate(rmr, activity, total, samples.size, latest, fallback,
                active.state, inputs.bodyIssues,
                fitbitTotal.value?.takeIf { fitbitTotal.state == OptionalReadState.AVAILABLE && it.isFinite() && it >= 0 },
                fitbitTotal.state, inputs.bodyReadStates, bodyDays)
        }
    }
}
