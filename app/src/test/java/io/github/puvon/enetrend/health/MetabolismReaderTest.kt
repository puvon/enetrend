package io.github.puvon.enetrend.health

import java.time.LocalDate
import java.time.ZoneId
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MetabolismReaderTest {
    private val day = LocalDate.of(2026, 3, 8)
    private val range = HealthDataRange(day, day.plusDays(1), ZoneId.of("America/New_York"))
    private class Source : MetabolismSource {
        var granted = MetabolismPermission.entries.toSet()
        val energyRanges = mutableListOf<Pair<HealthDataRange, Boolean>>()
        val bodyRanges = mutableListOf<HealthDataRange>()
        var body: (HealthDataRange, BodyMeasurementKind, String?) -> BodyMeasurementPage = { _, _, _ -> BodyMeasurementPage(emptyList(), null) }
        var energy: (HealthDataRange, Boolean) -> Double? = { _, _ -> 500.0 }
        override suspend fun permissions() = granted
        override suspend fun readEnergy(range: HealthDataRange, active: Boolean): Double? {
            energyRanges.add(range to active)
            return energy(range, active)
        }
        override suspend fun readBodyPage(range: HealthDataRange, kind: BodyMeasurementKind, token: String?): BodyMeasurementPage {
            bodyRanges.add(range)
            return body(range, kind, token)
        }
    }

    @Test fun absentOptionalPermissionsDoNotReadThoseTypes() = runBlocking {
        val source = Source().apply { granted = setOf(MetabolismPermission.TOTAL, MetabolismPermission.WEIGHT) }
        val result = MetabolismReader(source).read(range)
        assertEquals(OptionalReadState.PERMISSION_REQUIRED, result.active.getValue(day).state)
        assertEquals(500.0, result.fitbitTotals.getValue(day).value)
        assertTrue(result.bodyIssues.contains(OptionalReadState.PERMISSION_REQUIRED))
        assertEquals(setOf(OptionalReadState.AVAILABLE), result.bodyReadStates[BodyMeasurementKind.WEIGHT_KG])
        assertEquals(setOf(OptionalReadState.PERMISSION_REQUIRED), result.bodyReadStates[BodyMeasurementKind.FAT_PERCENT])
        assertEquals(setOf(OptionalReadState.PERMISSION_REQUIRED), result.bodyReadStates[BodyMeasurementKind.LEAN_KG])
        assertEquals(1, source.bodyRanges.size)
        assertTrue(source.energyRanges.none { it.second })
    }

    @Test fun readsAllPagesFiltersOriginAndBoundsAndUsesCalendarContext() = runBlocking {
        val source = Source().apply {
            body = { window, kind, token ->
                val t = window.startTime
                val good = BodyMeasurement(token ?: "first", t, t, kind, 50.0)
                BodyMeasurementPage(listOf(good, good.copy(id = "other", origin = "other"),
                    good.copy(id = "outside", time = window.endTime)), if (token == null) "next" else null)
            }
        }
        val result = MetabolismReader(source).read(range)
        assertEquals(6, result.measurements.size)
        assertTrue(result.measurements.all { it.origin == FITBIT_PACKAGE })
        assertTrue(source.bodyRanges.all { it.startDate == day.minusDays(13) })
        assertEquals(2, source.energyRanges.size)
        assertEquals(23L, Duration.between(source.energyRanges.first().first.startTime, range.endTime).toHours())
    }

    @Test fun partialPageFailureAndRepeatedTokensDiscardThatType() = runBlocking {
        for (repeated in listOf(false, true)) {
            val source = Source().apply { body = { window, kind, token ->
                if (kind != BodyMeasurementKind.LEAN_KG) BodyMeasurementPage(emptyList(), null)
                else {
                    if (token != null && !repeated) error("page failed")
                    BodyMeasurementPage(listOf(BodyMeasurement("1", window.startTime, window.startTime, kind, 50.0)), "loop")
                }
            } }
            val result = MetabolismReader(source).read(range)
            assertTrue(result.measurements.isEmpty())
            assertEquals(setOf(OptionalReadState.ERROR), result.bodyIssues)
            assertEquals(500.0, result.active.getValue(day).value)
        }
    }

    @Test fun historyRestrictionRecoversReadableDaysAndRetainsRestrictionState() = runBlocking {
        val source = Source().apply { body = { window, kind, _ ->
            if (window.startDate < day.minusDays(2)) throw SecurityException()
            BodyMeasurementPage(listOf(BodyMeasurement("${window.startDate}-$kind", window.startTime, window.startTime, kind, 50.0)), null)
        } }
        val result = MetabolismReader(source).read(range)
        assertEquals(9, result.measurements.size)
        assertTrue(OptionalReadState.ACCESS_DENIED in result.bodyIssues)
        assertEquals(500.0, result.active.getValue(day).value)
    }

    @Test fun midReadRevocationDiscardsOptionalDataAndNextReadRecovers() = runBlocking {
        val source = Source()
        source.energy = { _, active ->
            if (active) source.granted = setOf(MetabolismPermission.TOTAL)
            500.0
        }
        val reader = MetabolismReader(source)
        val revoked = reader.read(range)
        assertNull(revoked.active.getValue(day).value)
        assertEquals(OptionalReadState.PERMISSION_REQUIRED, revoked.active.getValue(day).state)
        assertTrue(revoked.bodyReadStates.values.all { it == setOf(OptionalReadState.PERMISSION_REQUIRED) })
        source.granted = MetabolismPermission.entries.toSet()
        source.energy = { _, _ -> 0.0 }
        assertEquals(0.0, reader.read(range).active.getValue(day).value)
    }

    @Test fun failedEnergyIsNotAnEmptyOrZeroRead() = runBlocking {
        val source = Source().apply { energy = { _, active -> if (active) error("failed") else null } }
        val result = MetabolismReader(source).read(range)
        assertEquals(OptionalReadState.ERROR, result.active.getValue(day).state)
        assertEquals(OptionalReadState.AVAILABLE, result.fitbitTotals.getValue(day).state)
        assertNull(result.active.getValue(day).value)
    }

    @Test(expected = CancellationException::class) fun cancellationIsNotFallback() { runBlocking {
        val source = Source().apply { body = { _, _, _ -> throw CancellationException() } }
        MetabolismReader(source).read(range)
    } }
}
