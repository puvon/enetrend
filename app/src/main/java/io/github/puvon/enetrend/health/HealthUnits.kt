package io.github.puvon.enetrend.health

import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass

/** Convert SDK value types at the data boundary; missing energy remains missing. */
internal object HealthUnits {
    fun kilocalories(energy: Energy?): Double? = energy?.inKilocalories
    fun kilograms(mass: Mass): Double = mass.inKilograms
}
