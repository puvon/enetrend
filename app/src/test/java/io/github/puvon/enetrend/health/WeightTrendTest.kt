package io.github.puvon.enetrend.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class WeightTrendTest {
    private val start = LocalDate.of(2026, 9, 1)
    private val zone = ZoneId.of("Asia/Tokyo")
    private fun range(days: Int = 10) = HealthDataRange(start, start.plusDays(days.toLong()), zone)
    private fun record(day: Int, kg: Double, hour: Int = 8, id: String = "$day-$hour") = WeightMeasurement(
        id, start.plusDays(day.toLong()).atTime(hour, 0).atZone(zone).toInstant(), null,
        kg, "test", Instant.EPOCH,
    )

    @Test fun lastMeasurementOfLocalDayWinsRegardlessOfOrder() {
        val records = listOf(record(0, 60.0), record(0, 62.0, 20))
        val result = WeightTrendCalculator.calculate(records.reversed(), range(1)).single()
        assertEquals(62.0, result.recorded!!.kilograms, 0.0)
        assertEquals(62.0, result.movingAverage.kilograms!!, 0.0)
        assertEquals(1, result.movingAverage.recordedDays)
        assertTrue(result.movingAverage.hasInsufficientDays)
    }

    @Test fun interpolationNeverEntersMovingAverage() {
        val result = WeightTrendCalculator.calculate(listOf(record(0, 60.0), record(3, 66.0)), range(4))
        val interpolated = result[1].display as DisplayValue.Interpolated
        assertEquals(62.0, interpolated.value, 1e-12)
        assertEquals(start, interpolated.previousRecordedDate)
        assertEquals(start.plusDays(3), interpolated.nextRecordedDate)
        assertNull(result[1].recorded)
        assertEquals(60.0, result[1].movingAverage.kilograms!!, 0.0)
        assertEquals(63.0, result[3].movingAverage.kilograms!!, 0.0)
        assertEquals(2, result[3].movingAverage.recordedDays)
    }

    @Test fun sixDayGapInterpolatesButSevenDayGapDoesNot() {
        val short = WeightTrendCalculator.calculate(listOf(record(0, 60.0), record(7, 67.0)), range())
        val long = WeightTrendCalculator.calculate(listOf(record(0, 60.0), record(8, 68.0)), range())
        assertTrue(short[6].display is DisplayValue.Interpolated)
        assertEquals(DisplayValue.Missing, long[1].display)
    }

    @Test fun longGapHidesAverageEvenWithSamplesInThirtyDayWindowAndResumes() {
        val result = WeightTrendCalculator.calculate(listOf(record(0, 60.0), record(8, 68.0)), range(), MovingAveragePeriod.THIRTY_DAYS)
        assertNotNull(result[6].movingAverage.kilograms)
        assertNull(result[7].movingAverage.kilograms)
        assertTrue(result[7].movingAverage.hiddenForLongGap)
        assertEquals(64.0, result[8].movingAverage.kilograms!!, 0.0)
    }

    @Test fun emptyDataNeverBecomesZero() {
        val result = WeightTrendCalculator.calculate(emptyList(), range())
        assertEquals(10, result.size)
        assertTrue(result.all { it.recorded == null && it.display == DisplayValue.Missing && it.movingAverage.kilograms == null })
    }

    @Test fun windowIncludesTodayAndExcludesDayBeforeWindow() {
        val result = WeightTrendCalculator.calculate(listOf(record(-7, 100.0), record(-6, 60.0), record(0, 62.0)), range(1)).single()
        assertEquals(61.0, result.movingAverage.kilograms!!, 0.0)
        assertEquals(2, result.movingAverage.recordedDays)
    }

    @Test fun periodSelectionRecalculatesWithoutChangingDisplayRange() {
        val records = listOf(record(-10, 80.0), record(0, 60.0))
        val seven = WeightTrendCalculator.calculate(records, range(1)).single()
        val fourteen = WeightTrendCalculator.calculate(records, range(1), MovingAveragePeriod.FOURTEEN_DAYS).single()
        assertEquals(seven.date, fourteen.date)
        assertEquals(60.0, seven.movingAverage.kilograms!!, 0.0)
        assertEquals(70.0, fourteen.movingAverage.kilograms!!, 0.0)
    }

    @Test fun outsideRecordsSupportInterpolationButNeverExtrapolation() {
        val result = WeightTrendCalculator.calculate(listOf(record(-1, 60.0), record(1, 64.0)), range(4))
        assertEquals(DisplayValue.Interpolated(62.0, start.minusDays(1), start.plusDays(1)), result[0].display)
        assertEquals(DisplayValue.Missing, result[2].display)
    }

    @Test fun repeatedIdsUseLatestVersionAndTiesAreStable() {
        val old = record(0, 60.0)
        val updated = old.copy(kilograms = 62.0, lastModifiedTime = Instant.EPOCH.plusSeconds(1))
        assertEquals(updated, WeightTrendCalculator.calculate(listOf(updated, old), range(1)).single().recorded)
        val other = updated.copy(id = "z", kilograms = 64.0)
        assertEquals(other, WeightTrendCalculator.calculate(listOf(other, updated), range(1)).single().recorded)
    }

    @Test fun fullWindowHasNoInsufficientDaysFlag() {
        val result = WeightTrendCalculator.calculate((0..6).map { record(it, 60.0) }, range(7)).last()
        assertFalse(result.movingAverage.hasInsufficientDays)
        assertEquals(7, result.movingAverage.recordedDays)
    }

    @Test fun daylightSavingWindowUsesCalendarDates() {
        val dstZone = ZoneId.of("America/New_York")
        val date = LocalDate.of(2026, 3, 9)
        val records = listOf(record(0, 60.0).copy(time = date.minusDays(6).atStartOfDay(dstZone).toInstant()),
            record(1, 62.0).copy(time = date.atStartOfDay(dstZone).toInstant()))
        assertEquals(61.0, WeightTrendCalculator.calculate(records, HealthDataRange(date, date.plusDays(1), dstZone)).single().movingAverage.kilograms!!, 0.0)
    }
}
