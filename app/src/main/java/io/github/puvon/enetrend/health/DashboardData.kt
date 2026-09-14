package io.github.puvon.enetrend.health

import java.time.LocalDate
import java.time.ZoneId

data class DashboardData(
    val balances: CalorieBalanceSeries,
    val weights: List<DailyWeightTrend>,
    val historyLimited: Boolean,
) {
    val hasData: Boolean get() = balances.daily.any {
        it.source.intake != DisplayValue.Missing || it.source.burned != DisplayValue.Missing
    } || weights.any { it.display != DisplayValue.Missing }
}

sealed interface DashboardState {
    data object Loading : DashboardState
    data class Ready(val data: DashboardData) : DashboardState
    data class Failed(val reason: HealthReadFailure) : DashboardState
}

/** Called on an IO dispatcher while the activity is foregrounded. No health data is persisted. */
class DashboardLoader(private val repository: HealthDataRepository) {
    suspend fun load(
        today: LocalDate,
        zone: ZoneId,
        displayDays: Int,
        averagePeriod: MovingAveragePeriod,
    ): DashboardState {
        require(displayDays in listOf(7, 14, 30))
        val selected = HealthDataRange(today.minusDays(displayDays.toLong() - 1), today.plusDays(1), zone)
        // Latest-period display ends today: no future records are requested or extrapolated.
        val contextDays = maxOf(averagePeriod.days - 1, InterpolationPolicy().maxConsecutiveMissingDays + 1)
        val extended = HealthDataRange(selected.startDate.minusDays(contextDays.toLong()), selected.endDateExclusive, zone)
        val first = read(extended, selected, averagePeriod, false)
        // Older history may be inaccessible without additional permission. Retry only an access
        // restriction, never an arbitrary failed read; preserve the limitation in the result.
        return if (first == DashboardState.Failed(HealthDataResult.AccessDenied)) {
            read(selected, selected, averagePeriod, true)
        } else first
    }

    private suspend fun read(
        loaded: HealthDataRange,
        selected: HealthDataRange,
        averagePeriod: MovingAveragePeriod,
        historyLimited: Boolean,
    ): DashboardState {
        val calories = when (val result = repository.readDailyCalories(loaded)) {
            is DailyCaloriesResult.Available -> result.data
            is HealthReadFailure -> return DashboardState.Failed(result)
        }
        val weights = when (val result = repository.read(loaded)) {
            is HealthDataResult.Available -> result.data.weights
            is HealthDataResult.Empty -> emptyList()
            is HealthReadFailure -> return DashboardState.Failed(result)
        }
        return DashboardState.Ready(DashboardData(
            CalorieBalanceCalculator.calculate(calories, selected),
            WeightTrendCalculator.calculate(weights, selected, averagePeriod),
            historyLimited,
        ))
    }
}
