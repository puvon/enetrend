package io.github.puvon.enetrend.health

import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import org.junit.Assert.*
import org.junit.Test

class HealthUnitsTest {
    @Test fun energyIsNormalizedToKilocalories() {
        for (energy in listOf(Energy.calories(1000.0), Energy.kilocalories(1.0), Energy.joules(4184.0))) {
            val actual = requireNotNull(HealthUnits.kilocalories(energy))
            assertEquals(1.0, actual, 0.000001)
        }
    }

    @Test fun missingEnergyIsNotMeasuredZero() {
        assertNull(HealthUnits.kilocalories(null))
        assertEquals(0.0, HealthUnits.kilocalories(Energy.joules(0.0)))
    }

    @Test fun massIsNormalizedToKilogramsWithoutRounding() {
        assertEquals(65.25, HealthUnits.kilograms(Mass.grams(65250.0)), 0.000001)
        assertEquals(65.25, HealthUnits.kilograms(Mass.kilograms(65.25)), 0.000001)
    }
}
