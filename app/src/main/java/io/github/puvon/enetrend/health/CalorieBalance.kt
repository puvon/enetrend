package io.github.puvon.enetrend.health

import java.time.LocalDate

data class DailyCalorieBalance(
    val source: DailyCalorieDisplay,
    val kilocalories: Double?,
) {
    val date: LocalDate get() = source.date
    val isEstimated: Boolean get() = kilocalories != null &&
        (source.intake is DisplayValue.Interpolated || source.burned is DisplayValue.Interpolated)
}

/** Completeness refers to available daily balances, not completeness of source logging. */
data class CumulativeCalorieBalance(
    val date: LocalDate,
    val startDate: LocalDate,
    val kilocalories: Double?,
    val availableDaysSubtotalKilocalories: Double?,
    val missingDates: Set<LocalDate>,
    val estimatedDates: Set<LocalDate>,
) {
    val isComplete: Boolean get() = missingDates.isEmpty()
    val isEstimated: Boolean get() = estimatedDates.isNotEmpty()
}

data class CalorieBalanceSeries(
    val range: HealthDataRange,
    val daily: List<DailyCalorieBalance>,
    val cumulative: List<CumulativeCalorieBalance>,
)

object CalorieBalanceCalculator {
    fun daily(source: DailyCalorieDisplay): DailyCalorieBalance {
        val intake = source.intake.number()
        val burned = source.burned.number()
        val balance = if (intake == null || burned == null) null else intake - burned
        require(balance == null || balance.isFinite())
        return DailyCalorieBalance(source, balance)
    }

    /** Project before cropping so interpolation can use records outside the selected range. */
    fun calculate(
        data: DailyCalorieData,
        range: HealthDataRange = data.range,
        policy: InterpolationPolicy = InterpolationPolicy(),
    ): CalorieBalanceSeries {
        require(range.zoneId == data.range.zoneId)
        require(range.startDate >= data.range.startDate && range.endDateExclusive <= data.range.endDateExclusive)
        val daily = data.forDisplay(policy)
            .filter { it.date >= range.startDate && it.date < range.endDateExclusive }
            .map(::daily)
        val missing = linkedSetOf<LocalDate>()
        val estimated = linkedSetOf<LocalDate>()
        // Compensated summation reduces accumulated floating-point rounding error.
        var sum = 0.0
        var correction = 0.0
        var count = 0
        val cumulative = daily.map { day ->
            val value = day.kilocalories
            if (value == null) {
                missing.add(day.date)
            } else {
                val adjusted = value - correction
                val next = sum + adjusted
                require(next.isFinite())
                correction = (next - sum) - adjusted
                sum = next
                count++
                if (day.isEstimated) estimated.add(day.date)
            }
            CumulativeCalorieBalance(
                day.date, range.startDate,
                sum.takeIf { missing.isEmpty() && count > 0 },
                sum.takeIf { count > 0 },
                missing.toSet(), estimated.toSet(),
            )
        }
        return CalorieBalanceSeries(range, daily, cumulative)
    }

    private fun DisplayValue.number(): Double? {
        val value = when (this) {
            is DisplayValue.Recorded -> value
            is DisplayValue.Interpolated -> value
            DisplayValue.Missing -> null
        }
        require(value == null || value.isFinite())
        return value
    }
}
