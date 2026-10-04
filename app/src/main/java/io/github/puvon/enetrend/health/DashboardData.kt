package io.github.puvon.enetrend.health

import java.time.LocalDate
import java.time.ZoneId
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A local day's aggregate as read, not a claim that the source has synced up to readStartedAt. */
data class TodayStatus(
    val date: LocalDate,
    val readStartedAt: Instant,
    val calories: CalorieTotals,
    val previousSevenDays: DailyCalorieData,
) {
    val includesTodayBalance: Boolean get() = calories.hasPositiveIntake
    val hasData: Boolean get() = calories.intakeKilocalories != null || calories.burnedKilocalories != null
    val calorieSummary: TodayCalorieSummary = TodayCalorieCalculator.calculate(this)
}

data class DashboardData(
    val balances: CalorieBalanceSeries,
    val weights: List<DailyWeightTrend>,
    val historyLimited: Boolean,
    val today: TodayStatus? = null,
) {
    // Chart visibility is independent of the today card.
    val hasData: Boolean get() = balances.daily.any {
        (it.date != today?.date || today?.includesTodayBalance == true) &&
            (it.source.intake != DisplayValue.Missing || it.source.burned != DisplayValue.Missing)
    } || weights.any { it.display != DisplayValue.Missing || it.movingAverage.kilograms != null }
}

sealed interface DashboardState {
    data object Loading : DashboardState
    data class Ready(val data: DashboardData) : DashboardState
    data class Failed(val reason: HealthReadFailure) : DashboardState
}

/** Called on an IO dispatcher while the activity is foregrounded. No health data is persisted. */
class DashboardLoader(
    private val repository: HealthDataRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun loadCurrent(
        displayDays: Int,
        averagePeriod: MovingAveragePeriod,
        timeSource: DashboardTimeSource,
    ): DashboardState {
        while (true) {
            currentCoroutineContext().ensureActive()
            val started = timeSource.now()
            val result = load(started.date, started.zone, displayDays, averagePeriod, started.instant)
            // A read can straddle midnight or a zone change even before its notification arrives.
            if (started.hasSameDayAndZone(timeSource.now())) return result
        }
    }

    suspend fun load(
        today: LocalDate,
        zone: ZoneId,
        displayDays: Int,
        averagePeriod: MovingAveragePeriod,
        readStartedAt: Instant = clock.instant(),
    ): DashboardState {
        require(displayDays in listOf(7, 14, 30))
        // Use the entire local day: clipping at now could truncate a day-long nutrition record.
        val todayRange = HealthDataRange(today, today.plusDays(1), zone)
        val todayCalories = when (val result = repository.readDailyCalories(todayRange)) {
            is DailyCaloriesResult.Available -> result.data
            is HealthReadFailure -> return DashboardState.Failed(result)
        }
        val totals = todayCalories.recorded.getValue(today)
        val selected = HealthDataRange(today.minusDays(displayDays.toLong() - 1), today.plusDays(1), zone)
        val contextDays = maxOf(averagePeriod.days - 1, InterpolationPolicy().maxConsecutiveMissingDays + 1)
        val trendContext = HealthDataRange(selected.startDate.minusDays(contextDays.toLong()), selected.endDateExclusive, zone)
        val previousSeven = HealthDataRange(today.minusDays(7), today, zone)
        val pastRange = HealthDataRange(minOf(trendContext.startDate, previousSeven.startDate), today, zone)
        val past = when (val result = repository.readDailyCalories(pastRange, selected.startDate)) {
            is DailyCaloriesResult.Available -> result.data
            is HealthReadFailure -> return DashboardState.Failed(result)
        }
        val allCalories = DailyCalorieData(
            HealthDataRange(pastRange.startDate, todayRange.endDateExclusive, zone),
            past.recorded + todayCalories.recorded,
            past.accessRestrictedDates,
        )
        // Keep the date axis and weight window through today, but stop balances before an
        // unreported/zero intake day. Its partial consumption must not interpolate earlier days.
        val balanceEnd = if (totals.hasPositiveIntake) selected.endDateExclusive else today
        val trendCalories = allCalories.within(trendContext.copy(endDateExclusive = balanceEnd))
        val calculated = CalorieBalanceCalculator.calculate(trendCalories, selected.copy(endDateExclusive = balanceEnd))
        val balances = calculated.copy(
            range = selected,
            // Keep actual intake/consumption in date details without calculating today's balance.
            daily = calculated.daily + if (totals.hasPositiveIntake) emptyList() else
                listOf(DailyCalorieBalance(todayCalories.forDisplay().single(), null)),
        )
        var weightHistoryLimited = false
        val firstWeights = repository.readWeights(trendContext)
        val weightResult = if (firstWeights == HealthDataResult.AccessDenied) {
            weightHistoryLimited = true
            repository.readWeights(selected)
        } else firstWeights
        val weights = when (weightResult) {
            is WeightDataResult.Available -> weightResult.measurements
            is HealthReadFailure -> return DashboardState.Failed(weightResult)
        }
        val data = DashboardData(
            balances,
            WeightTrendCalculator.calculate(weights, selected, averagePeriod),
            past.accessRestrictedDates.isNotEmpty() || weightHistoryLimited,
            TodayStatus(today, readStartedAt, totals, allCalories.within(previousSeven)),
        )
        currentCoroutineContext().ensureActive()
        return DashboardState.Ready(data)
    }
}
