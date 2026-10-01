package io.github.puvon.enetrend.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TodayCaloriesTest {
    private val today = LocalDate.of(2026, 10, 1)
    private val zone = ZoneId.of("Asia/Tokyo")
    private fun fullWeek(intake: Double = 2100.0, burned: Double = 2000.0) =
        (1L..7L).associate { today.minusDays(it) to CalorieTotals(intake, burned) }

    private fun status(
        records: Map<LocalDate, CalorieTotals> = fullWeek(),
        current: CalorieTotals = CalorieTotals(null, 1600.0),
        restricted: Set<LocalDate> = emptySet(),
    ) = TodayStatus(today, Instant.parse("2026-10-01T03:00:00Z"), current,
        DailyCalorieData(HealthDataRange(today.minusDays(7), today, zone), records, restricted))

    @Test fun fullSurplusIsDeductedFromPrediction() {
        val result = status().calorieSummary
        assertEquals(2000.0, result.predictedBurnedKilocalories!!, 1e-9)
        assertEquals(700.0, result.previousBalanceKilocalories!!, 1e-9)
        assertEquals(1300.0, result.intakeAllowanceKilocalories!!, 1e-9)
        assertEquals(ConsumptionBasis.PREDICTED, result.consumptionBasis)
        assertEquals(7, result.burnedRecordedDays)
        assertEquals(7, result.balanceRecordedDays)
        assertFalse(result.hasMissingRecords)
    }

    @Test fun recordedConsumptionAbovePredictionIsUsedWithoutChangingPrediction() {
        val result = status(current = CalorieTotals(1800.0, 2300.0)).calorieSummary
        assertEquals(2000.0, result.predictedBurnedKilocalories!!, 1e-9)
        assertEquals(2300.0, result.basisBurnedKilocalories!!, 1e-9)
        assertEquals(1600.0, result.intakeAllowanceKilocalories!!, 1e-9)
        assertEquals(ConsumptionBasis.RECORDED, result.consumptionBasis)
        assertEquals(ConsumptionBasis.PREDICTED, status(current = CalorieTotals(null, 2000.0)).calorieSummary.consumptionBasis)
    }

    @Test fun deficitIncreasesAllowanceAndZeroBalanceLeavesBasisUnchanged() {
        assertEquals(2700.0, status(fullWeek(1900.0)).calorieSummary.intakeAllowanceKilocalories!!, 1e-9)
        assertEquals(2000.0, status(fullWeek(2000.0)).calorieSummary.intakeAllowanceKilocalories!!, 1e-9)
    }

    @Test fun negativeAllowanceIsNotClamped() {
        val records = fullWeek(2000.0).toMutableMap().apply { put(today.minusDays(1), CalorieTotals(4500.0, 2000.0)) }
        assertEquals(-500.0, status(records).calorieSummary.intakeAllowanceKilocalories!!, 1e-9)
    }

    @Test fun todaysIntakeIsNeverSubtractedAndRefreshRecalculatesConsumption() {
        val before = status()
        for (intake in listOf(null, 0.0, 1200.0, 5000.0)) {
            assertEquals(before.calorieSummary, before.copy(calories = CalorieTotals(intake, 1600.0)).calorieSummary)
        }
        assertEquals(2300.0, before.copy(calories = CalorieTotals(null, 3000.0)).calorieSummary.intakeAllowanceKilocalories!!, 1e-9)
    }

    @Test fun partialWeekUsesDifferentValidCountsForMeanAndBalance() {
        val records = fullWeek(2000.0).toMutableMap().apply {
            remove(today.minusDays(7))
            put(today.minusDays(1), CalorieTotals(2700.0, 2000.0))
            put(today.minusDays(2), CalorieTotals(null, 2000.0))
            put(today.minusDays(3), CalorieTotals(null, 2000.0))
        }
        val result = status(records).calorieSummary
        assertEquals(6, result.burnedRecordedDays)
        assertEquals(4, result.balanceRecordedDays)
        assertTrue(result.hasMissingRecords)
        assertEquals(2000.0, result.predictedBurnedKilocalories!!, 1e-9)
        assertEquals(700.0, result.previousBalanceKilocalories!!, 1e-9)
        assertEquals(1300.0, result.intakeAllowanceKilocalories!!, 1e-9)
    }

    @Test fun zeroIsRecordedAndAllMissingIsNotZero() {
        val zero = status(fullWeek(0.0, 0.0), CalorieTotals(0.0, 0.0)).calorieSummary
        assertEquals(0.0, zero.intakeAllowanceKilocalories!!, 0.0)
        assertEquals(0.0, zero.previousBalanceKilocalories!!, 0.0)
        assertEquals(7, zero.balanceRecordedDays)
        val missing = status(emptyMap(), CalorieTotals(null, null)).calorieSummary
        assertNull(missing.predictedBurnedKilocalories)
        assertNull(missing.previousBalanceKilocalories)
        assertNull(missing.intakeAllowanceKilocalories)
        assertNull(missing.consumptionBasis)
        assertEquals(0, missing.burnedRecordedDays)
    }

    @Test fun onlyCurrentConsumptionCanProduceAnAllowanceWithoutHistoryCorrection() {
        for (burned in listOf(0.0, 1600.0)) {
            val result = status(emptyMap(), CalorieTotals(null, burned)).calorieSummary
            assertNull(result.predictedBurnedKilocalories)
            assertNull(result.previousBalanceKilocalories)
            assertEquals(burned, result.intakeAllowanceKilocalories!!, 0.0)
            assertEquals(ConsumptionBasis.RECORDED, result.consumptionBasis)
        }
    }

    @Test fun predictionAloneDoesNotNeedTodaysConsumptionOrHistoricalIntake() {
        val result = status(mapOf(today.minusDays(3) to CalorieTotals(null, 2000.0)), CalorieTotals(1200.0, null)).calorieSummary
        assertEquals(2000.0, result.intakeAllowanceKilocalories!!, 0.0)
        assertEquals(1, result.burnedRecordedDays)
        assertEquals(0, result.balanceRecordedDays)
        assertNull(result.previousBalanceKilocalories)
        assertEquals(ConsumptionBasis.PREDICTED, result.consumptionBasis)
    }

    @Test fun missingConsumptionBetweenRecordedDaysIsNotInterpolatedForAllowance() {
        val records = mapOf(
            today.minusDays(3) to CalorieTotals(2100.0, 2000.0),
            today.minusDays(2) to CalorieTotals(2100.0, null),
            today.minusDays(1) to CalorieTotals(2100.0, 2000.0),
        )
        val input = status(records)
        assertTrue(input.previousSevenDays.forDisplay().any { it.burned is DisplayValue.Interpolated })
        val result = input.calorieSummary
        assertEquals(2, result.burnedRecordedDays)
        assertEquals(2, result.balanceRecordedDays)
        assertEquals(200.0, result.previousBalanceKilocalories!!, 0.0)
        assertEquals(1800.0, result.intakeAllowanceKilocalories!!, 1e-9)
    }

    @Test fun restrictedDaysAreDistinctFromMissingRecords() {
        val restrictedDate = today.minusDays(7)
        val result = status(fullWeek().filterKeys { it != restrictedDate }, restricted = setOf(restrictedDate)).calorieSummary
        assertEquals(1, result.accessRestrictedDays)
        assertEquals(6, result.balanceRecordedDays)
        assertFalse(result.hasMissingRecords)
        assertEquals(1400.0, result.intakeAllowanceKilocalories!!, 1e-9)
    }

    @Test fun fixedCalendarWindowExcludesEarlierAndCurrentAndFutureRecords() {
        val records = fullWeek().toMutableMap().apply {
            put(today.minusDays(8), CalorieTotals(100000.0, 30000.0))
            put(today, CalorieTotals(100000.0, 30000.0))
            put(today.plusDays(1), CalorieTotals(100000.0, 30000.0))
        }
        val input = status().copy(previousSevenDays = DailyCalorieData(
            HealthDataRange(today.minusDays(8), today.plusDays(2), zone), records))
        assertEquals(status().calorieSummary, input.calorieSummary)
    }

    @Test fun sevenCalendarDatesWorkAcrossYearLeapDayAndDaylightSaving() {
        for (date in listOf(LocalDate.of(2027, 1, 1), LocalDate.of(2028, 3, 1), LocalDate.of(2026, 3, 10))) {
            val range = HealthDataRange(date.minusDays(7), date, ZoneId.of("America/New_York"))
            val input = TodayStatus(date, Instant.EPOCH, CalorieTotals(null, null), DailyCalorieData(range,
                range.days().associate { it.date to CalorieTotals(2100.0, 2000.0) }))
            assertEquals(7, input.calorieSummary.balanceRecordedDays)
            assertEquals(1300.0, input.calorieSummary.intakeAllowanceKilocalories!!, 1e-9)
        }
    }

    @Test fun fractionalValuesStayUnroundedAndLargeNegativeValuesStayUnclamped() {
        val fraction = status(fullWeek(2000.02, 2000.01)).calorieSummary
        assertEquals(1999.94, fraction.intakeAllowanceKilocalories!!, 1e-8)
        val large = status(fullWeek(1e12, 0.0), CalorieTotals(null, 2000.0)).calorieSummary
        assertEquals(2000.0 - 7e12, large.intakeAllowanceKilocalories!!, 0.0)
    }
}
