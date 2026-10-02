package io.github.puvon.enetrend.health

import java.math.BigDecimal
import java.math.MathContext

enum class ConsumptionBasis { PREDICTED, RECORDED }

data class TodayCalorieSummary(
    val predictedBurnedKilocalories: Double?,
    val basisBurnedKilocalories: Double?,
    val consumptionBasis: ConsumptionBasis?,
    val previousBalanceKilocalories: Double?,
    val intakeAllowanceKilocalories: Double?,
    val burnedRecordedDays: Int,
    val balanceRecordedDays: Int,
    val accessRestrictedDays: Int,
    val correctedDays: Int = 0,
) {
    val hasMissingRecords: Boolean get() = balanceRecordedDays + accessRestrictedDays < 7
}

/** Seven calendar days, reconstructed or recorded consumption, never interpolated values. */
object TodayCalorieCalculator {
    fun calculate(status: TodayStatus): TodayCalorieSummary {
        val window = HealthDataRange(status.date.minusDays(7), status.date, status.previousSevenDays.range.zoneId)
        val past = status.previousSevenDays.within(window)
        val consumption = past.recorded.values.mapNotNull { it.effectiveBurnedKilocalories }
        require(consumption.all { it.isFinite() })
        // Avoid overflowing the intermediate sum or comparing a rounded display value with today's value.
        val predicted = if (consumption.isEmpty()) null else consumption
            .fold(BigDecimal.ZERO) { sum, value -> sum + BigDecimal.valueOf(value) }
            .divide(BigDecimal.valueOf(consumption.size.toLong()), MathContext.DECIMAL128).toDouble()
        val recorded = status.calories.burnedKilocalories
        require(recorded == null || recorded.isFinite())
        val basis = when {
            recorded != null && (predicted == null || recorded > predicted) -> ConsumptionBasis.RECORDED
            predicted != null -> ConsumptionBasis.PREDICTED
            else -> null
        }
        val basisValue = when (basis) {
            ConsumptionBasis.PREDICTED -> predicted
            ConsumptionBasis.RECORDED -> recorded
            null -> null
        }
        // Reuse the canonical daily balance and compensated cumulative sum, disabling interpolation.
        val balances = CalorieBalanceCalculator.calculate(past, policy = InterpolationPolicy(0))
        val previousBalance = balances.periodCumulative.last().kilocalories
        val allowance = basisValue?.let { it - (previousBalance ?: 0.0) }
        require(allowance == null || allowance.isFinite())
        return TodayCalorieSummary(
            predicted, basisValue, basis, previousBalance, allowance,
            consumption.size, balances.daily.count { it.kilocalories != null }, past.accessRestrictedDates.size,
            past.recorded.values.count { it.metabolism?.totalKilocalories != null },
        )
    }
}
