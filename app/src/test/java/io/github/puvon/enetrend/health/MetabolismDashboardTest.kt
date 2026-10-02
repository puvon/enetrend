package io.github.puvon.enetrend.health

import io.github.puvon.enetrend.ui.DashboardChartProjector
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MetabolismDashboardTest {
    private val today = LocalDate.of(2026, 10, 1)
    private val zone = ZoneId.of("Asia/Tokyo")
    private inner class Source : HealthDataSource {
        override val requiredPermissions = setOf("read")
        var optionalFailure = false
        var active: Double? = 500.0
        var original: Double? = 2200.0
        override fun availability() = HealthAvailability.AVAILABLE
        override suspend fun grantedPermissions() = requiredPermissions
        override suspend fun readCalorieTotals(range: HealthDataRange) =
            if (range.startDate == today) CalorieTotals(null, 1200.0) else CalorieTotals(2000.0, original)
        override suspend fun readWeightPage(range: HealthDataRange, pageToken: String?) = WeightPage(emptyList(), null)
        override suspend fun readMetabolism(range: HealthDataRange): MetabolismInputs {
            if (optionalFailure) throw SecurityException()
            val measurements = (0L..60L).map {
                val t = today.minusDays(it).atTime(8, 0).atZone(zone).toInstant()
                BodyMeasurement("$it", t, Instant.EPOCH, BodyMeasurementKind.LEAN_KG, 50.0)
            }
            return MetabolismInputs(measurements, range.days().associate { it.date to OptionalHealthValue(active) })
        }
    }
    private suspend fun load(source: Source, length: Int = 7, average: MovingAveragePeriod = MovingAveragePeriod.SEVEN_DAYS) =
        (DashboardLoader(HealthDataRepository(source)).load(today, zone, length, average) as DashboardState.Ready).data

    @Test fun sameAdoptedConsumptionFeedsBarsBalancesAndSevenDayAllowance() = runBlocking {
        val data = load(Source())
        val day = data.balances.daily.first()
        assertEquals(2200.0, day.source.recorded.burnedKilocalories!!, 0.0)
        assertEquals(DisplayValue.Estimated(1950.0), day.source.burned)
        assertEquals(50.0, day.kilocalories!!, 0.0)
        assertEquals(300.0, data.balances.periodCumulative.last().kilocalories!!, 0.0)
        val summary = data.today!!.calorieSummary
        assertEquals(1950.0, summary.predictedBurnedKilocalories!!, 0.0)
        assertEquals(350.0, summary.previousBalanceKilocalories!!, 0.0)
        assertEquals(1600.0, summary.intakeAllowanceKilocalories!!, 0.0)
        assertEquals(7, summary.correctedDays)
        assertNull(data.balances.daily.last().kilocalories)
        assertEquals(DisplayValue.Recorded(1200.0), data.balances.daily.last().source.burned)
    }

    @Test fun optionalFailureAndMissingActivityFallbackAndRecoveryDoNotBreakOriginals() = runBlocking {
        val source = Source()
        source.optionalFailure = true
        val failed = load(source)
        assertEquals(DisplayValue.Recorded(2200.0), failed.balances.daily.first().source.burned)
        assertTrue(OptionalReadState.ACCESS_DENIED in failed.balances.daily.first().source.recorded.metabolism!!.bodyIssues)
        source.optionalFailure = false
        source.active = null
        assertEquals(DisplayValue.Recorded(2200.0), load(source).balances.daily.first().source.burned)
        source.active = 0.0
        assertEquals(DisplayValue.Estimated(1450.0), load(source).balances.daily.first().source.burned)
        source.original = null
        assertEquals(DisplayValue.Estimated(1450.0), load(source).balances.daily.first().source.burned)
        source.active = null
        assertEquals(DisplayValue.Missing, load(source).balances.daily.first().source.burned)
    }

    @Test fun periodAndWeightAverageDoNotChangeSevenDayCorrectionAndChartHasSharedCenters() = runBlocking {
        val source = Source()
        val short = load(source)
        val long = load(source, 30, MovingAveragePeriod.THIRTY_DAYS)
        assertEquals(short.today!!.calorieSummary, long.today!!.calorieSummary)
        val chart = DashboardChartProjector.project(short)
        val first = chart.days.first()
        assertEquals(chart.dailyScale.y(1950.0), first.burnedY!!, 0.0)
        assertEquals(chart.dailyScale.y(1450.0), first.restingY!!, 0.0)
        assertTrue(first.burnedY!! < first.restingY!! && first.restingY!! < chart.dailyZeroY)
        assertEquals(chart.range.startDate, first.restingBaselineDate)
        assertEquals(0.0, first.restingChangeKilocaloriesPerDay!!, 0.0)
        assertNull(chart.days.last().restingY)
        assertNotNull(chart.days.last().burnedY)
        assertEquals(-chart.dailyScale.min, chart.dailyScale.max, 0.0)
        assertTrue(DashboardChartProjector.CONSUMPTION_BAR_FRACTION < DashboardChartProjector.BALANCE_BAR_FRACTION)
    }
}
