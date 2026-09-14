package io.github.puvon.enetrend.health

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class MovingAveragePeriod(val days: Int) {
    SEVEN_DAYS(7), FOURTEEN_DAYS(14), THIRTY_DAYS(30),
}

data class WeightMovingAverage(
    val kilograms: Double?,
    val recordedDays: Int,
    val period: MovingAveragePeriod,
    val hiddenForLongGap: Boolean,
) {
    val hasInsufficientDays: Boolean get() = recordedDays < period.days
}

data class DailyWeightTrend(
    val date: LocalDate,
    val recorded: WeightMeasurement?,
    val display: DisplayValue,
    val movingAverage: WeightMovingAverage,
)

/** Pure calculation over loaded records; never interprets absent records as zero kilograms. */
object WeightTrendCalculator {
    fun calculate(
        measurements: List<WeightMeasurement>,
        range: HealthDataRange,
        period: MovingAveragePeriod = MovingAveragePeriod.SEVEN_DAYS,
        policy: InterpolationPolicy = InterpolationPolicy(),
    ): List<DailyWeightTrend> {
        require(measurements.all { it.kilograms.isFinite() && it.kilograms > 0 })
        // Latest modification wins for repeated IDs. Ties remain deterministic across input order.
        val order = compareBy(WeightMeasurement::lastModifiedTime, WeightMeasurement::time)
            .thenBy { it.kilograms }.thenBy { it.dataOrigin }
        val unique = measurements.groupBy { it.id }.values.map { it.maxWith(order) }
        val daily = unique.groupBy { it.time.atZone(range.zoneId).toLocalDate() }
            .mapValues { (_, records) ->
                records.maxWith(compareBy(WeightMeasurement::time, WeightMeasurement::lastModifiedTime)
                    .thenBy { it.id })
            }.toSortedMap()
        val display = DailyInterpolation.project(range, daily.mapValues { it.value.kilograms }, policy)
        return range.days().map { day ->
            val windowStart = day.date.minusDays(period.days.toLong() - 1)
            val samples = daily.filterKeys { it >= windowStart && it <= day.date }.values
            val previous = daily.keys.lastOrNull { it <= day.date }
            val longGap = previous != null &&
                ChronoUnit.DAYS.between(previous, day.date) > policy.maxConsecutiveMissingDays
            val average = if (day.isEmpty || longGap || samples.isEmpty()) null else
                samples.map { it.kilograms }.average()
            DailyWeightTrend(
                day.date, daily[day.date], display.getValue(day.date),
                WeightMovingAverage(average, samples.size, period, longGap),
            )
        }.toList()
    }
}
