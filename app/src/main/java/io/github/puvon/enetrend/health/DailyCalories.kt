package io.github.puvon.enetrend.health

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Recorded means present in the source, not a guarantee of a complete day's logging. */
sealed interface DisplayValue {
    data class Recorded(val value: Double) : DisplayValue
    data class Interpolated(
        val value: Double,
        val previousRecordedDate: LocalDate,
        val nextRecordedDate: LocalDate,
    ) : DisplayValue
    data object Missing : DisplayValue
}

data class InterpolationPolicy(val maxConsecutiveMissingDays: Int = 6) {
    init { require(maxConsecutiveMissingDays >= 0) }
}

/** Pure display projection. Never mutates the records or uses an estimate as an anchor. */
object DailyInterpolation {
    fun project(
        range: HealthDataRange,
        recorded: Map<LocalDate, Double?>,
        policy: InterpolationPolicy = InterpolationPolicy(),
    ): Map<LocalDate, DisplayValue> {
        require(recorded.values.all { it == null || it.isFinite() })
        val days = range.days().toList()
        val output = days.associateTo(linkedMapOf()) { day ->
            day.date to if (day.isEmpty) DisplayValue.Missing else
                recorded[day.date]?.let { DisplayValue.Recorded(it) } ?: DisplayValue.Missing
        }
        val anchors = recorded.entries.filter { (date, value) ->
            value != null && !LocalDayWindow(date, range.zoneId).isEmpty
        }.sortedBy { it.key }
        for ((previous, next) in anchors.zipWithNext()) {
            val distance = ChronoUnit.DAYS.between(previous.key, next.key)
            val missingDays = distance - 1
            if (missingDays !in 1..policy.maxConsecutiveMissingDays.toLong()) continue
            val start = maxOf(previous.key.plusDays(1), range.startDate)
            val end = minOf(next.key, range.endDateExclusive)
            var date = start
            while (date < end) {
                if (!LocalDayWindow(date, range.zoneId).isEmpty) {
                    val fraction = ChronoUnit.DAYS.between(previous.key, date).toDouble() / distance
                    val from = requireNotNull(previous.value)
                    val to = requireNotNull(next.value)
                    output[date] = DisplayValue.Interpolated(
                        from * (1.0 - fraction) + to * fraction, previous.key, next.key,
                    )
                }
                date = date.plusDays(1)
            }
        }
        return output
    }
}

data class DailyCalorieDisplay(
    val date: LocalDate,
    val recorded: CalorieTotals,
    val intake: DisplayValue,
    val burned: DisplayValue,
)

/** Each entry is a single local day's API aggregate, never a share of a multi-day total. */
data class DailyCalorieData(
    val range: HealthDataRange,
    val recorded: Map<LocalDate, CalorieTotals>,
) {
    init { require(recorded.keys.all { it >= range.startDate && it < range.endDateExclusive }) }

    val hasRecordedData: Boolean get() = recorded.values.any {
        it.intakeKilocalories != null || it.burnedKilocalories != null
    }

    /** Fetch surrounding days first and crop after projection if outside anchors are needed. */
    fun forDisplay(policy: InterpolationPolicy = InterpolationPolicy()): List<DailyCalorieDisplay> {
        val burnedValues = DailyInterpolation.project(range, recorded.mapValues { it.value.burnedKilocalories }, policy)
        return range.days().map { day ->
            val original = if (day.isEmpty) null else recorded[day.date]
            val totals = original ?: CalorieTotals(null, null)
            DailyCalorieDisplay(
                day.date, totals,
                totals.intakeKilocalories?.let { DisplayValue.Recorded(it) } ?: DisplayValue.Missing,
                burnedValues.getValue(day.date),
            )
        }.toList()
    }
}

sealed interface DailyCaloriesResult {
    data class Available(val data: DailyCalorieData) : DailyCaloriesResult
}

/** Access failures are shared by period reads and daily reads. */
sealed interface HealthReadFailure : HealthDataResult, DailyCaloriesResult
