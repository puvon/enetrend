package io.github.puvon.enetrend.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class MetabolismTest {
    private val day = LocalDate.of(2026, 10, 1)
    private val zone = ZoneId.of("Asia/Tokyo")
    private val range = HealthDataRange(day, day.plusDays(1), zone)
    private fun measurement(offset: Long, value: Double, kind: BodyMeasurementKind = BodyMeasurementKind.LEAN_KG) =
        BodyMeasurement("$offset-$kind", day.plusDays(offset).atTime(8, 0).atZone(zone).toInstant(), Instant.EPOCH, kind, value)
    private fun samples(value: Double = 50.0) = listOf(-2L, -1L, 0L).map { measurement(it, value) }
    private fun calculate(records: List<BodyMeasurement> = samples(), active: Double? = 500.0,
        today: LocalDate = day.plusDays(1)) = MetabolismCalculator.calculate(
        MetabolismInputs(records, mapOf(day to OptionalHealthValue(active)), mapOf(day to OptionalHealthValue(2200.0))), range, today).getValue(day)

    @Test fun reconstructsWithoutAddingOriginalTotalAndKeepsZeroActivity() {
        val result = calculate()
        assertEquals(1450.0, result.restingKilocaloriesPerDay!!, 1e-9)
        assertEquals(1950.0, result.totalKilocalories!!, 1e-9)
        assertEquals(2200.0, result.fitbitTotalKilocalories!!, 0.0)
        assertEquals(1450.0, calculate(active = 0.0).totalKilocalories!!, 1e-9)
        assertNull(calculate(active = null).totalKilocalories)
        assertEquals(MetabolismFallback.ACTIVE_MISSING, calculate(active = null).fallback)
    }

    @Test fun lowerFatAtSameWeightAndMoreLeanMassIncreaseRmr() {
        fun paired(fat: Double) = (-2L..0L).flatMap { listOf(measurement(it, 70.0, BodyMeasurementKind.WEIGHT_KG),
            measurement(it, fat, BodyMeasurementKind.FAT_PERCENT)) }
        assertTrue(calculate(paired(15.0)).restingKilocaloriesPerDay!! > calculate(paired(20.0)).restingKilocaloriesPerDay!!)
        assertEquals(21.6, calculate(samples(51.0)).restingKilocaloriesPerDay!! - calculate().restingKilocaloriesPerDay!!, 1e-9)
    }

    @Test fun dailyDirectValueWinsAndMeansAreComputedAfterPairing() {
        val pairs = listOf(
            measurement(-2, 100.0, BodyMeasurementKind.WEIGHT_KG), measurement(-2, 50.0, BodyMeasurementKind.FAT_PERCENT),
            measurement(-1, 60.0, BodyMeasurementKind.WEIGHT_KG), measurement(-1, 0.0, BodyMeasurementKind.FAT_PERCENT),
            measurement(0, 100.0, BodyMeasurementKind.WEIGHT_KG), measurement(0, 0.0, BodyMeasurementKind.FAT_PERCENT),
            measurement(0, 40.0),
        )
        assertEquals(1450.0, calculate(pairs).restingKilocaloriesPerDay!!, 1e-9)
        assertEquals(3, calculate(pairs).recordedDays)
    }

    @Test fun calendarWindowIncludesMinus13ExcludesMinus14AndFuture() {
        val records = listOf(measurement(-14, 200.0), measurement(-13, 49.0), measurement(-7, 50.0),
            measurement(-6, 51.0), measurement(1, 200.0))
        assertEquals(1450.0, calculate(records).restingKilocaloriesPerDay!!, 1e-9)
        assertEquals(3, calculate(records).recordedDays)
        assertEquals(MetabolismFallback.STALE_MEASUREMENT,
            calculate(listOf(measurement(-13, 50.0), measurement(-8, 50.0), measurement(-7, 50.0))).fallback)
        assertEquals(MetabolismFallback.INSUFFICIENT_DAYS, calculate(records.filter { it != records[1] }).fallback)
    }

    @Test fun unequalMeasurementDatesAndInvalidValuesDoNotCreateSamples() {
        val records = listOf(measurement(-2, 70.0, BodyMeasurementKind.WEIGHT_KG),
            measurement(-1, 20.0, BodyMeasurementKind.FAT_PERCENT), measurement(0, Double.NaN),
            measurement(-3, -1.0), measurement(-4, 100.0, BodyMeasurementKind.FAT_PERCENT))
        assertEquals(0, calculate(records).recordedDays)
        assertNull(calculate(emptyList()).totalKilocalories)
        assertNull(calculate(active = -1.0).totalKilocalories)
        assertNull(calculate(active = Double.POSITIVE_INFINITY).totalKilocalories)
    }

    @Test fun duplicateIdsAndMultipleDailyMeasurementsAreDeterministicAndOriginFiltered() {
        val first = measurement(0, 20.0)
        val updated = first.copy(value = 50.0, modified = Instant.ofEpochSecond(1))
        val earlier = first.copy(id = "early", time = first.time.minusSeconds(1), value = 100.0)
        val records = samples().take(2) + first + updated + earlier + first.copy(id = "other", origin = "other", value = 300.0)
        assertEquals(1450.0, calculate(records).restingKilocaloriesPerDay!!, 1e-9)
        assertEquals(calculate(records), calculate(records.reversed()))
    }

    @Test fun todayKeepsOriginalButNextDayCanUseEstimate() {
        val current = calculate(today = day)
        assertEquals(MetabolismFallback.TODAY, current.fallback)
        assertNull(current.restingKilocaloriesPerDay)
        assertNull(current.totalKilocalories)
        assertEquals(1200.0, CalorieTotals(null, 1200.0, metabolism = current).effectiveBurnedKilocalories!!, 0.0)
        assertEquals(1950.0, calculate(today = day.plusDays(1)).totalKilocalories!!, 0.0)
    }

    @Test fun localDatesAndDstDoNotUseUtcDateOrFixed24HourWindow() {
        val tokyo = samples().map { it.copy(time = it.time.minusSeconds(8 * 3600 - 60)) }
        assertEquals(3, calculate(tokyo).recordedDays)
        val dstRange = HealthDataRange(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 9), ZoneId.of("America/New_York"))
        val records = (0L..2L).map {
            val time = dstRange.startDate.minusDays(it).atStartOfDay(dstRange.zoneId).toInstant()
            BodyMeasurement("$it", time, time, BodyMeasurementKind.LEAN_KG, 50.0)
        }
        assertEquals(1450.0, MetabolismCalculator.calculate(MetabolismInputs(records), dstRange, dstRange.endDateExclusive)
            .getValue(dstRange.startDate).restingKilocaloriesPerDay!!, 0.0)
    }

    @Test fun reconstructedAndInterpolatedHaveSeparateProvenanceAndOriginalAnchors() {
        val range = HealthDataRange(day, day.plusDays(3), zone)
        val input = DailyCalorieData(range, mapOf(day to CalorieTotals(2000.0, null, metabolism = calculate()),
            day.plusDays(1) to CalorieTotals(2000.0, null), day.plusDays(2) to CalorieTotals(2000.0, 2200.0)))
        val result = CalorieBalanceCalculator.calculate(input)
        assertEquals(DisplayValue.Estimated(1950.0), result.daily[0].source.burned)
        assertEquals(DisplayValue.Missing, result.daily[1].source.burned)
        assertTrue(result.daily[0].isEstimated)
        assertEquals(setOf(day), result.periodCumulative.last().estimatedDates)
        assertEquals(-150.0, result.periodCumulative.last().kilocalories!!, 0.0)
    }
}
