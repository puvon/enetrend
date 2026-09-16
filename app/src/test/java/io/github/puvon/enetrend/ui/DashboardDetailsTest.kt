package io.github.puvon.enetrend.ui

import io.github.puvon.enetrend.health.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DashboardDetailsTest {
    private val start = LocalDate.of(2026, 9, 1)
    private val range = HealthDataRange(start, start.plusDays(4), ZoneId.of("Asia/Tokyo"))
    private fun chart(): DashboardChartData {
        val calories = DailyCalorieData(range, mapOf(start to CalorieTotals(200.0, 200.0),
            start.plusDays(1) to CalorieTotals(300.0, null), start.plusDays(2) to CalorieTotals(null, 400.0)))
        val weight = DailyWeightTrend(start.plusDays(2), null,
            DisplayValue.Interpolated(70.0, start, start.plusDays(3)),
            WeightMovingAverage(70.0, 2, MovingAveragePeriod.SEVEN_DAYS, false))
        return DashboardChartProjector.project(DashboardData(CalorieBalanceCalculator.calculate(calories), listOf(weight), false))
    }

    @Test fun recordedZeroAndMissingAreDifferentStates() {
        val chart = chart()
        assertEquals(listOf(DetailStatus.AVAILABLE), chart.days[0].details()[2].statuses)
        assertEquals("日別収支：0.0 kcal", chart.days[0].details()[2].text)
        assertEquals(listOf(DetailStatus.MISSING), chart.days[2].details()[0].statuses)
        assertEquals(listOf(DetailStatus.UNAVAILABLE), chart.days[2].details()[2].statuses)
        assertEquals(listOf(DetailStatus.MISSING), chart.days[3].details()[1].statuses)
    }

    @Test fun referenceAndEstimateRemainSeparateAndAverageShowsSampleShortage() {
        val details = chart().days[2].details()
        assertEquals(listOf(DetailStatus.REFERENCE, DetailStatus.ESTIMATED), details[3].statuses)
        assertTrue(details[3].notes.single().contains("欠測1日を除外"))
        assertEquals(listOf(DetailStatus.INTERPOLATED), details[4].statuses)
        assertTrue(details[4].text.contains("2026-09-01 ～ 2026-09-04"))
        assertEquals(listOf(DetailStatus.AVAILABLE, DetailStatus.INSUFFICIENT), details[5].statuses)
        assertTrue(details[5].text.contains("実測2/7日"))
        val spoken = chart().days[2].detailLines().joinToString("。")
        assertTrue(spoken.contains("参考累積・推定"))
    }

    @Test fun absentAverageAndEmptyCalorieDataDoNotBecomeAvailable() {
        val chart = DashboardChartProjector.project(DashboardData(
            CalorieBalanceCalculator.calculate(DailyCalorieData(range, emptyMap())), emptyList(), false))
        val details = chart.days[0].details()
        assertEquals(listOf(DetailStatus.UNAVAILABLE), details[3].statuses)
        assertTrue(details[3].notes.isEmpty())
        assertEquals(listOf(DetailStatus.UNAVAILABLE), details[5].statuses)
    }
}
