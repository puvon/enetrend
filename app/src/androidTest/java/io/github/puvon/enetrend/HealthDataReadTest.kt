package io.github.puvon.enetrend

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.rules.ActivityScenarioRule
import io.github.puvon.enetrend.health.AndroidHealthDataSource
import io.github.puvon.enetrend.health.HealthAvailability
import io.github.puvon.enetrend.health.HealthDataRange
import io.github.puvon.enetrend.health.HealthDataRepository
import io.github.puvon.enetrend.health.HealthDataResult
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule

class HealthDataReadTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun readsRecentDataThroughRealHealthConnectWithoutWriting(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = AndroidHealthDataSource(context)
        assumeTrue("Health Connect must be available", source.availability() == HealthAvailability.AVAILABLE)
        assumeTrue("Grant the three read permissions before this integration test",
            source.grantedPermissions().containsAll(source.requiredPermissions))
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val range = HealthDataRange(today.minusDays(1), today.plusDays(1), zone)
        val result = HealthDataRepository(source).read(range)
        // No record values are logged or attached to assertions.
        assertTrue("Real aggregate/weight reads must succeed, including an empty store",
            result is HealthDataResult.Available || result is HealthDataResult.Empty)
        if (result is HealthDataResult.Available) {
            assertEquals(range, result.data.range)
            assertTrue(result.data.weights.all { it.time >= range.startTime && it.time < range.endTime })
            assertEquals(result.data.weights.size, result.data.weights.map { it.id }.toSet().size)
        }
    }
}
