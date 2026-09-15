package io.github.puvon.enetrend.health

import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HealthDataRepositoryTest {
    private val range = HealthDataRange(
        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3), ZoneId.of("Asia/Tokyo"),
    )

    private class Source : HealthDataSource {
        override val requiredPermissions = setOf("nutrition", "calories", "weight")
        var permissions = requiredPermissions
        var status = HealthAvailability.AVAILABLE
        var calories = CalorieTotals(null, null)
        var pages = mapOf<String?, WeightPage>(null to WeightPage(emptyList(), null))
        val tokens = mutableListOf<String?>()
        val ranges = mutableListOf<HealthDataRange>()
        var calorieCalls = 0
        var permissionCalls = 0
        var failure: Exception? = null
        var onPage: () -> Unit = {}
        override fun availability() = status
        override suspend fun grantedPermissions(): Set<String> {
            permissionCalls++
            return permissions
        }
        override suspend fun readCalorieTotals(range: HealthDataRange): CalorieTotals {
            calorieCalls++
            ranges += range
            return calories
        }
        override suspend fun readWeightPage(range: HealthDataRange, pageToken: String?): WeightPage {
            tokens += pageToken
            ranges += range
            onPage()
            failure?.let { throw it }
            return pages.getValue(pageToken)
        }
    }

    private fun weight(id: String, time: Instant = range.startTime, kg: Double = 65.0) =
        WeightMeasurement(id, time, ZoneOffset.ofHours(9), kg, "test.source", Instant.EPOCH)

    @Test fun localDateRangeUsesExclusiveEndAndRespectsDaylightSaving() {
        assertEquals(Instant.parse("2026-08-31T15:00:00Z"), range.startTime)
        assertEquals(Instant.parse("2026-09-02T15:00:00Z"), range.endTime)
        val spring = HealthDataRange(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 9), ZoneId.of("America/New_York"))
        val autumn = HealthDataRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2), spring.zoneId)
        assertEquals(23, Duration.between(spring.startTime, spring.endTime).toHours().toInt())
        assertEquals(25, Duration.between(autumn.startTime, autumn.endTime).toHours().toInt())
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRangeIsRejected() {
        HealthDataRange(range.startDate, range.startDate, range.zoneId)
    }

    @Test fun unavailableOrUpdateRequiredDoesNotRead() = runBlocking {
        val source = Source()
        val repository = HealthDataRepository(source)
        source.status = HealthAvailability.UNAVAILABLE
        assertEquals(HealthDataResult.Unavailable, repository.read(range))
        source.status = HealthAvailability.UPDATE_REQUIRED
        assertEquals(HealthDataResult.UpdateRequired, repository.read(range))
        assertEquals(0, source.permissionCalls)
        assertEquals(0, source.calorieCalls)
        assertTrue(source.tokens.isEmpty())
    }

    @Test fun partialPermissionDoesNotStartDataReads() = runBlocking {
        val source = Source()
        source.permissions = setOf("weight")
        assertEquals(HealthDataResult.PermissionsRequired(setOf("nutrition", "calories")), HealthDataRepository(source).read(range))
        assertEquals(0, source.calorieCalls)
        assertTrue(source.tokens.isEmpty())
    }

    @Test fun emptyAndMeasuredZeroAreDifferent() = runBlocking {
        val source = Source()
        val repository = HealthDataRepository(source)
        assertEquals(HealthDataResult.Empty(range), repository.read(range))
        source.calories = CalorieTotals(0.0, null)
        val result = repository.read(range) as HealthDataResult.Available
        assertEquals(0.0, result.data.calories.intakeKilocalories)
        assertNull(result.data.calories.burnedKilocalories)
    }

    @Test fun caloriesAreNotAddedAgainAndEitherMetricCanBeAbsent() = runBlocking {
        for (calories in listOf(CalorieTotals(1200.0, null), CalorieTotals(null, 1800.0), CalorieTotals(1200.0, 1800.0))) {
            val source = Source()
            source.calories = calories
            val result = HealthDataRepository(source).read(range) as HealthDataResult.Available
            assertEquals(calories, result.data.calories)
            assertEquals(1, source.calorieCalls)
        }
    }

    @Test fun allPagesAreReadAndDuplicateIdsDoNotRemoveDistinctMeasurements() = runBlocking {
        val source = Source()
        val first = weight("one")
        val revised = first.copy(kilograms = 66.0, lastModifiedTime = Instant.EPOCH.plusSeconds(1))
        val second = weight("two") // Same time/value but a different ID must survive.
        source.pages = mapOf(
            null to WeightPage(listOf(first, weight("end", range.endTime)), "next"),
            "next" to WeightPage(listOf(revised, second, weight("before", range.startTime.minusSeconds(1))), ""),
        )
        val data = (HealthDataRepository(source).read(range) as HealthDataResult.Available).data
        assertEquals(listOf(revised, second), data.weights)
        assertEquals(listOf(null, "next"), source.tokens)
        assertTrue(source.ranges.all { it == range })
        assertNull(data.calories.intakeKilocalories)
    }

    @Test fun emptyPageWithContinuationDoesNotStopPagination() = runBlocking {
        val source = Source()
        source.pages = mapOf(null to WeightPage(emptyList(), "next"), "next" to WeightPage(listOf(weight("one")), null))
        assertTrue(HealthDataRepository(source).read(range) is HealthDataResult.Available)
        assertEquals(listOf(null, "next"), source.tokens)
    }

    @Test fun repeatedPageTokenReturnsErrorInsteadOfLoopingOrReturningPartialData() = runBlocking {
        val source = Source()
        source.pages = mapOf(null to WeightPage(listOf(weight("one")), "next"), "next" to WeightPage(emptyList(), "next"))
        assertEquals(HealthDataResult.Error, HealthDataRepository(source).read(range))
        assertEquals(2, source.tokens.size)
    }

    @Test fun revocationAfterPageDiscardsAlreadyReadDataAndStopsPagination() = runBlocking {
        val source = Source()
        source.calories = CalorieTotals(100.0, 200.0)
        source.pages = mapOf(null to WeightPage(listOf(weight("one")), "next"))
        source.onPage = { source.permissions = emptySet() }
        assertEquals(HealthDataResult.PermissionsRequired(source.requiredPermissions), HealthDataRepository(source).read(range))
        assertEquals(listOf<String?>(null), source.tokens)
    }

    @Test fun revocationOnFinalPageAlsoDiscardsResult() = runBlocking {
        val source = Source()
        source.onPage = { source.permissions = emptySet() }
        assertTrue(HealthDataRepository(source).read(range) is HealthDataResult.PermissionsRequired)
    }

    @Test fun accessRestrictionAndIoFailureAreNotEmptyDataAndRetryStartsFresh() = runBlocking {
        val source = Source()
        source.calories = CalorieTotals(100.0, 200.0)
        val repository = HealthDataRepository(source)
        source.failure = SecurityException()
        assertEquals(HealthDataResult.AccessDenied, repository.read(range))
        source.failure = IOException()
        assertEquals(HealthDataResult.Error, repository.read(range))
        source.failure = null
        assertTrue(repository.read(range) is HealthDataResult.Available)
        assertEquals(listOf<String?>(null, null, null), source.tokens)
    }

    @Test(expected = CancellationException::class)
    fun cancellationPropagates(): Unit = runBlocking {
        val source = Source()
        source.failure = CancellationException()
        HealthDataRepository(source).read(range)
        Unit
    }

    @Test fun laterPageFailureDiscardsEarlierRecordsAndRetryRestartsAtFirstPage() = runBlocking {
        val source = Source()
        source.pages = mapOf(null to WeightPage(listOf(weight("first")), "next"),
            "next" to WeightPage(listOf(weight("second")), null))
        source.onPage = { if (source.tokens.last() == "next") throw IOException() }
        val repository = HealthDataRepository(source)
        assertEquals(HealthDataResult.Error, repository.read(range))
        source.onPage = {}
        val result = repository.read(range) as HealthDataResult.Available
        assertEquals(listOf("first", "second"), result.data.weights.map { it.id })
        assertEquals(listOf(null, "next", null, "next"), source.tokens)
    }
}
