package io.github.puvon.enetrend.health

import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import io.github.puvon.enetrend.ui.DashboardChartProjector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DashboardLoaderTest {
    private val today = LocalDate.of(2026, 9, 14)
    private val zone = ZoneId.of("Asia/Tokyo")
    private class Source : HealthDataSource {
        override val requiredPermissions = setOf("read")
        var granted = requiredPermissions
        val ranges = mutableListOf<HealthDataRange>()
        var read: (HealthDataRange) -> CalorieTotals = { CalorieTotals(2000.0, 2200.0) }
        var weights = emptyList<WeightMeasurement>()
        override fun availability() = HealthAvailability.AVAILABLE
        override suspend fun grantedPermissions() = granted
        override suspend fun readCalorieTotals(range: HealthDataRange): CalorieTotals {
            ranges.add(range)
            return read(range)
        }
        override suspend fun readWeightPage(range: HealthDataRange, pageToken: String?) = WeightPage(
            weights.filter { it.time >= range.startTime && it.time < range.endTime }, null)
    }

    private fun populatedSource() = Source().apply {
        weights = (0L..60L).map { offset ->
            WeightMeasurement("$offset", today.minusDays(offset).atTime(8, 0).atZone(zone).toInstant(), null,
                70.0 + offset / 10.0, "test", Instant.EPOCH)
        }
    }

    @Test fun changingDisplayRangeAlignsAllSeriesAndReselectsBaseline() = runBlocking {
        val loader = DashboardLoader(HealthDataRepository(populatedSource()))
        for (length in listOf(30, 7, 14, 30)) {
            val data = (loader.load(today, zone, length, MovingAveragePeriod.SEVEN_DAYS) as DashboardState.Ready).data
            val chart = DashboardChartProjector.project(data)
            val first = today.minusDays(length.toLong() - 1)
            assertEquals(first, chart.range.startDate)
            assertEquals(today.plusDays(1), chart.range.endDateExclusive)
            assertEquals(first, chart.baseline?.date)
            assertEquals(70.0 + (length - 1) / 10.0, chart.baseline!!.kilograms, 1e-10)
            assertEquals(-200.0, chart.days.first().periodCumulative!!.kilocalories!!, 0.0)
            assertEquals(-200.0 * length, chart.days.last().periodCumulative!!.kilocalories!!, 0.0)
            assertEquals(length, chart.days.size)
            chart.days.forEach {
                assertEquals(it.date, it.daily!!.date)
                assertEquals(it.date, it.periodCumulative!!.date)
                assertEquals(it.date, it.weight!!.date)
            }
        }
    }

    @Test fun changingAverageOnlyKeepsBalancesAndBaselineButChangesAverage() = runBlocking {
        val loader = DashboardLoader(HealthDataRepository(populatedSource()))
        val short = (loader.load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS) as DashboardState.Ready).data
        val long = (loader.load(today, zone, 7, MovingAveragePeriod.THIRTY_DAYS) as DashboardState.Ready).data
        assertEquals(short.balances, long.balances)
        assertEquals(DashboardChartProjector.project(short).baseline, DashboardChartProjector.project(long).baseline)
        assertNotEquals(short.weights.last().movingAverage.kilograms, long.weights.last().movingAverage.kilograms)
        assertEquals(7, short.weights.last().movingAverage.recordedDays)
        assertEquals(30, long.weights.last().movingAverage.recordedDays)
    }

    @Test fun selectedRangeAndAverageContextAreIndependent() = runBlocking {
        val source = Source()
        val state = DashboardLoader(HealthDataRepository(source)).load(today, zone, 30, MovingAveragePeriod.THIRTY_DAYS) as DashboardState.Ready
        assertEquals(today.minusDays(58), source.ranges.first().startDate)
        assertEquals(today.minusDays(29), state.data.balances.range.startDate)
        assertEquals(30, state.data.balances.daily.size)
        assertEquals(-6000.0, state.data.balances.periodCumulative.last().kilocalories!!, 0.0)
        assertFalse(state.data.historyLimited)
    }

    @Test fun accessLimitedHistoryRetriesSelectedRangeAndMarksLimitation() = runBlocking {
        val source = Source()
        source.read = { if (it.startDate < today.minusDays(29)) throw SecurityException() else CalorieTotals(1.0, 2.0) }
        val state = DashboardLoader(HealthDataRepository(source)).load(today, zone, 30, MovingAveragePeriod.THIRTY_DAYS) as DashboardState.Ready
        assertTrue(state.data.historyLimited)
        assertEquals(30, state.data.weights.size)
    }

    @Test fun noDataRemainsEmptyInsteadOfBecomingZero() = runBlocking {
        val source = Source().apply { read = { CalorieTotals(null, null) } }
        val state = DashboardLoader(HealthDataRepository(source)).load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS) as DashboardState.Ready
        assertFalse(state.data.hasData)
        assertNull(state.data.balances.periodCumulative.last().kilocalories)
    }

    @Test fun failureIsNotRetriedAsMissingData() = runBlocking {
        val source = Source().apply { read = { error("failure") } }
        assertEquals(DashboardState.Failed(HealthDataResult.Error), DashboardLoader(HealthDataRepository(source)).load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS))
        assertEquals(1, source.ranges.size)
    }

    @Test fun revokedPermissionsPreventDataExposure() = runBlocking {
        val source = Source().apply { read = { granted = emptySet(); CalorieTotals(1.0, 2.0) } }
        val state = DashboardLoader(HealthDataRepository(source)).load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS)
        assertEquals(DashboardState.Failed(HealthDataResult.PermissionsRequired(setOf("read"))), state)
    }

    @Test(expected = CancellationException::class) fun cancellationPropagates() { runBlocking {
        val source = Source().apply { read = { throw CancellationException() } }
        DashboardLoader(HealthDataRepository(source)).load(today, zone, 7, MovingAveragePeriod.SEVEN_DAYS)
    } }
}
