package io.github.puvon.enetrend.health

import java.time.LocalDate
import java.time.ZoneId
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
        override fun availability() = HealthAvailability.AVAILABLE
        override suspend fun grantedPermissions() = granted
        override suspend fun readCalorieTotals(range: HealthDataRange): CalorieTotals {
            ranges.add(range)
            return read(range)
        }
        override suspend fun readWeightPage(range: HealthDataRange, pageToken: String?) = WeightPage(emptyList(), null)
    }

    @Test fun selectedRangeAndAverageContextAreIndependent() = runBlocking {
        val source = Source()
        val state = DashboardLoader(HealthDataRepository(source)).load(today, zone, 30, MovingAveragePeriod.THIRTY_DAYS) as DashboardState.Ready
        assertEquals(today.minusDays(58), source.ranges.first().startDate)
        assertEquals(today.minusDays(29), state.data.balances.range.startDate)
        assertEquals(30, state.data.balances.daily.size)
        assertEquals(-6000.0, state.data.balances.cumulative.last().kilocalories!!, 0.0)
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
        assertNull(state.data.balances.cumulative.last().kilocalories)
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
