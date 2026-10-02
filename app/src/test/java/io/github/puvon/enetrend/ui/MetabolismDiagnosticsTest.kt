package io.github.puvon.enetrend.ui

import io.github.puvon.enetrend.health.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class MetabolismDiagnosticsTest {
    private val day = LocalDate.of(2026, 10, 1)
    private val range = HealthDataRange(day, day.plusDays(1), ZoneId.of("Asia/Tokyo"))
    private fun estimate(inputs: MetabolismInputs, today: LocalDate = day.plusDays(1)) =
        MetabolismCalculator.calculate(inputs, range, today).getValue(day)

    @Test fun missingPermissionsAreVisibleEvenWhenSampleCountIsInsufficient() {
        val result = estimate(MetabolismInputs(
            active = mapOf(day to OptionalHealthValue(state = OptionalReadState.PERMISSION_REQUIRED)),
            bodyReadStates = mapOf(BodyMeasurementKind.FAT_PERCENT to setOf(OptionalReadState.PERMISSION_REQUIRED)),
        ))
        assertTrue(result.unavailableReason().contains("Fitbit体脂肪率：権限未許可"))
        assertTrue(result.unavailableReason().contains("Fitbit活動消費：権限未許可"))
        assertTrue(result.unavailableReason().contains("0/3日"))
    }

    @Test fun permittedEmptyRecordsAreNotReportedAsMissingPermissionsOrZero() {
        val result = estimate(MetabolismInputs(bodyReadStates = BodyMeasurementKind.entries.associateWith {
            setOf(OptionalReadState.AVAILABLE)
        }))
        assertTrue(result.unavailableReason().contains("有効な記録なし"))
        assertFalse(result.unavailableReason().contains("権限未許可"))
        assertNull(result.totalKilocalories)
        assertTrue(result.readDiagnostics().any { it.contains("読み取り成功、直近14日の有効記録0日") })
    }

    @Test fun diagnosticsCountValidLocalDaysAndExplainUnmatchedDates() {
        fun sample(id: String, offset: Long, kind: BodyMeasurementKind, value: Double): BodyMeasurement {
            val time = day.plusDays(offset).atTime(8, 0).atZone(range.zoneId).toInstant()
            return BodyMeasurement(id, time, time, kind, value)
        }
        val result = estimate(MetabolismInputs(measurements = listOf(
            sample("w1", -1, BodyMeasurementKind.WEIGHT_KG, 70.0),
            sample("w2", -1, BodyMeasurementKind.WEIGHT_KG, 71.0),
            sample("f", 0, BodyMeasurementKind.FAT_PERCENT, 20.0),
            sample("old", -14, BodyMeasurementKind.LEAN_KG, 50.0),
            sample("future", 1, BodyMeasurementKind.LEAN_KG, 50.0),
            sample("invalid", 0, BodyMeasurementKind.LEAN_KG, -1.0),
            sample("other", 0, BodyMeasurementKind.LEAN_KG, 50.0).copy(origin = "other"),
        )))
        assertEquals(1, result.bodyRecordedDays[BodyMeasurementKind.WEIGHT_KG])
        assertEquals(1, result.bodyRecordedDays[BodyMeasurementKind.FAT_PERCENT])
        assertEquals(0, result.bodyRecordedDays[BodyMeasurementKind.LEAN_KG])
        assertEquals(0, result.recordedDays)
        assertTrue(result.readDiagnostics().any { it.contains("記録日は一致していません") })
    }

    @Test fun todayIsExplicitlyExcludedRatherThanDiagnosedAsBroken() {
        assertEquals("当日は補正対象外", estimate(MetabolismInputs(), day).unavailableReason())
    }
}
