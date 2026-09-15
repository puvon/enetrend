package io.github.puvon.enetrend.health

import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DailyCalorieReadTest {
    private val date = LocalDate.of(2026, 3, 7)
    private val range = HealthDataRange(date, date.plusDays(3), ZoneId.of("America/New_York"))

    private class Source : HealthDataSource {
        override val requiredPermissions = setOf("nutrition", "calories", "weight")
        var grants = requiredPermissions
        var status = HealthAvailability.AVAILABLE
        val requests = mutableListOf<HealthDataRange>()
        var read: (HealthDataRange) -> CalorieTotals = { CalorieTotals(null, null) }
        override fun availability() = status
        override suspend fun grantedPermissions() = grants
        override suspend fun readCalorieTotals(range: HealthDataRange): CalorieTotals {
            requests += range
            return read(range)
        }
        override suspend fun readWeightPage(range: HealthDataRange, pageToken: String?): WeightPage =
            error("Daily calories must not re-read weight records")
    }

    @Test fun eachLocalDayUsesItsOwnNonOverlappingAggregate() = runBlocking {
        val source = Source()
        source.read = { day -> CalorieTotals((day.startDate.dayOfMonth * 100).toDouble(), 2000.0) }
        val data = (HealthDataRepository(source).readDailyCalories(range) as DailyCaloriesResult.Available).data
        assertEquals(listOf(700.0, 800.0, 900.0), data.forDisplay().map { it.recorded.intakeKilocalories })
        assertEquals(3, source.requests.size)
        assertEquals(23L, Duration.between(source.requests[1].startTime, source.requests[1].endTime).toHours())
        source.requests.zipWithNext().forEach { (a, b) -> assertEquals(a.endTime, b.startTime) }
        assertEquals(range.startTime, source.requests.first().startTime)
        assertEquals(range.endTime, source.requests.last().endTime)
    }

    @Test fun missingDaysAndMeasuredZeroSurviveTheRead() = runBlocking {
        val source = Source()
        source.read = { day -> if (day.startDate == date) CalorieTotals(0.0, null) else CalorieTotals(null, null) }
        val data = (HealthDataRepository(source).readDailyCalories(range) as DailyCaloriesResult.Available).data
        assertTrue(data.hasRecordedData)
        assertEquals(3, data.forDisplay().size)
        assertEquals(DisplayValue.Recorded(0.0), data.forDisplay().first().intake)
        assertEquals(DisplayValue.Missing, data.forDisplay().last().intake)
    }

    @Test fun unavailableAndMissingPermissionsDoNotRead() = runBlocking {
        val source = Source()
        val repository = HealthDataRepository(source)
        source.status = HealthAvailability.UNAVAILABLE
        assertEquals(HealthDataResult.Unavailable, repository.readDailyCalories(range))
        source.status = HealthAvailability.AVAILABLE
        source.grants = emptySet()
        assertTrue(repository.readDailyCalories(range) is HealthDataResult.PermissionsRequired)
        assertTrue(source.requests.isEmpty())
    }

    @Test fun failedDayDiscardsTheSeriesRatherThanBeingInterpolated() = runBlocking {
        val source = Source()
        source.read = { day -> if (day.startDate == date.plusDays(1)) throw IOException() else CalorieTotals(100.0, 200.0) }
        assertEquals(HealthDataResult.Error, HealthDataRepository(source).readDailyCalories(range))
        assertEquals(2, source.requests.size)
    }

    @Test fun accessLossOnFinalDayDiscardsTheSeries() = runBlocking {
        val source = Source()
        source.read = { day ->
            if (day.endDateExclusive == range.endDateExclusive) source.grants = emptySet()
            CalorieTotals(100.0, 200.0)
        }
        assertTrue(HealthDataRepository(source).readDailyCalories(range) is HealthDataResult.PermissionsRequired)
    }

    @Test fun historyRestrictionIsNotMissingData() = runBlocking {
        val source = Source()
        source.read = { throw SecurityException() }
        assertEquals(HealthDataResult.AccessDenied, HealthDataRepository(source).readDailyCalories(range))
    }

    @Test fun providerLostAfterFirstDayDiscardsSeriesAndStopsReading() = runBlocking {
        val source = Source()
        source.read = { source.status = HealthAvailability.UNAVAILABLE; CalorieTotals(100.0, 200.0) }
        assertEquals(HealthDataResult.Unavailable, HealthDataRepository(source).readDailyCalories(range))
        assertEquals(1, source.requests.size)
    }

    @Test fun nonexistentLocalDayDoesNotIssueAnInvalidApiRange() = runBlocking {
        val source = Source()
        val range = HealthDataRange(LocalDate.of(2011, 12, 29), LocalDate.of(2012, 1, 1), ZoneId.of("Pacific/Apia"))
        val data = (HealthDataRepository(source).readDailyCalories(range) as DailyCaloriesResult.Available).data
        assertEquals(2, source.requests.size)
        assertEquals(3, data.forDisplay().size)
        assertFalse(data.hasRecordedData)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsPropagated(): Unit = runBlocking {
        val source = Source()
        source.read = { throw CancellationException() }
        HealthDataRepository(source).readDailyCalories(range)
        Unit
    }
}
