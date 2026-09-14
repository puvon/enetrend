package io.github.puvon.enetrend.ui

import io.github.puvon.enetrend.health.*
import java.time.LocalDate

/** Coordinates are fractions of the plot: x increases rightwards, y downwards. */
data class DashboardChartDay(
    val date: LocalDate,
    val x: Double,
    val daily: DailyCalorieBalance?,
    val periodCumulative: PeriodCumulativeCalorieBalance?,
    val weight: DailyWeightTrend?,
    val dailyY: Double?,
    val periodCumulativeY: Double?,
    val weightY: Double?,
    val movingAverageY: Double?,
)

/** The source remains available so a baseline taken from interpolation can be labelled. */
data class ChartWeightBaseline(val date: LocalDate, val source: DisplayValue, val kilograms: Double)

data class ChartScale(val min: Double, val max: Double) {
    init { require(min.isFinite() && max.isFinite() && max > min && (max - min).isFinite()) }

    fun y(value: Double): Double {
        require(value.isFinite())
        return 1.0 - (value - min) / (max - min)
    }

    companion object {
        internal fun covering(values: List<Double>, includeZero: Boolean): ChartScale {
            require(values.all { it.isFinite() })
            val bounds = if (includeZero) values + 0.0 else values
            val min = bounds.minOrNull() ?: 0.0
            val max = bounds.maxOrNull() ?: 0.0
            // A constant/empty series still needs a non-zero drawing range.
            return if (min == max) ChartScale(min - 0.5, max + 0.5) else ChartScale(min, max)
        }
    }
}

data class DashboardChartData(
    val range: HealthDataRange,
    val days: List<DashboardChartDay>,
    val dailyScale: ChartScale,
    val weightScale: ChartScale?,
    val independentPeriodCumulativeScale: ChartScale?,
    val baseline: ChartWeightBaseline?,
    val kilocaloriesPerKilogram: Double,
    val dailyZeroY: Double,
    val periodStartY: Double,
) {
    val periodStartX: Double get() = 0.0
    val isPeriodCumulativeWeightLinked: Boolean get() = baseline != null
}

/** Presentation-only projection; never recalculates balances or generates weight records. */
object DashboardChartProjector {
    const val DEFAULT_KILOCALORIES_PER_KILOGRAM = 7000.0

    fun project(
        data: DashboardData,
        kilocaloriesPerKilogram: Double = DEFAULT_KILOCALORIES_PER_KILOGRAM,
    ): DashboardChartData {
        require(kilocaloriesPerKilogram.isFinite() && kilocaloriesPerKilogram > 0.0)
        val range = data.balances.range
        val dates = range.days().map { it.date }.toList()
        val daily = index(data.balances.daily, range) { it.date }
        val cumulative = index(data.balances.periodCumulative, range) { it.date }
        val weights = index(data.weights, range) { it.date }
        require(cumulative.values.all { it.startDate == range.startDate })
        val orderedWeights = dates.mapNotNull { weights[it] }
        val baselineDay = orderedWeights.firstOrNull { it.display is DisplayValue.Recorded }
            ?: orderedWeights.firstOrNull { it.display is DisplayValue.Interpolated }
        val baseline = baselineDay?.let {
            ChartWeightBaseline(it.date, it.display, requireNotNull(it.display.number()))
        }
        val dailyScale = ChartScale.covering(daily.values.mapNotNull { it.kilocalories }, true)
        fun cumulativePosition(value: Double): Double =
            baseline?.let { it.kilograms + value / kilocaloriesPerKilogram } ?: value
        val cumulativeValues = cumulative.values.mapNotNull { it.kilocalories }.map(::cumulativePosition)
        val weightValues = orderedWeights.flatMap { listOfNotNull(it.display.number(), it.movingAverage.kilograms) }
        val weightScale = if (baseline != null) {
            ChartScale.covering(weightValues + cumulativeValues + baseline.kilograms, false)
        } else if (weightValues.isNotEmpty()) {
            // An average from earlier records can exist without an in-range baseline.
            ChartScale.covering(weightValues, false)
        } else null
        val independent = if (baseline == null) ChartScale.covering(cumulativeValues, true) else null
        val cumulativeScale = requireNotNull(if (baseline != null) weightScale else independent)
        val days = dates.mapIndexed { i, date ->
            val day = daily[date]
            val period = cumulative[date]
            val weight = weights[date]
            DashboardChartDay(
                date, (i + 0.5) / dates.size, day, period, weight,
                day?.kilocalories?.let(dailyScale::y),
                period?.kilocalories?.let { cumulativeScale.y(cumulativePosition(it)) },
                weight?.display?.number()?.let { requireNotNull(weightScale).y(it) },
                weight?.movingAverage?.kilograms?.let { requireNotNull(weightScale).y(it) },
            )
        }
        return DashboardChartData(range, days, dailyScale, weightScale, independent, baseline,
            kilocaloriesPerKilogram, dailyScale.y(0.0), cumulativeScale.y(cumulativePosition(data.balances.periodStartKilocalories)))
    }

    private fun <T> index(values: List<T>, range: HealthDataRange, date: (T) -> LocalDate): Map<LocalDate, T> {
        val indexed = values.associateBy(date)
        require(indexed.size == values.size) { "Duplicate chart date" }
        require(indexed.keys.all { it >= range.startDate && it < range.endDateExclusive }) { "Chart date outside display range" }
        return indexed
    }

    private fun DisplayValue.number(): Double? = when (this) {
        is DisplayValue.Recorded -> value
        is DisplayValue.Interpolated -> value
        DisplayValue.Missing -> null
    }
}
