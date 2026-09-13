package io.github.puvon.enetrend.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthConnectionTest {
    private class Gateway : HealthConnectionGateway {
        override val requiredPermissions = setOf("nutrition", "calories", "weight")
        var status = HealthAvailability.AVAILABLE
        var grants = emptySet<String>()
        var failure: Exception? = null
        var queries = 0
        override fun availability() = status
        override suspend fun grantedPermissions(): Set<String> {
            queries++
            failure?.let { throw it }
            return grants
        }
    }

    @Test fun unavailableDoesNotCreateClientOrQueryPermissions() = runBlocking {
        val gateway = Gateway()
        val connection = HealthConnection(gateway)
        gateway.status = HealthAvailability.UNAVAILABLE
        assertEquals(HealthConnectionState.Unavailable, connection.check())
        gateway.status = HealthAvailability.UPDATE_REQUIRED
        assertEquals(HealthConnectionState.UpdateRequired, connection.check())
        assertEquals(0, gateway.queries)
    }

    @Test fun partialAndDeniedPermissionsAreNotReady() = runBlocking {
        val gateway = Gateway()
        val connection = HealthConnection(gateway)
        assertEquals(HealthConnectionState.PermissionsRequired(0), connection.check())
        gateway.grants = setOf("nutrition", "unrelated")
        assertEquals(HealthConnectionState.PermissionsRequired(1), connection.check())
    }

    @Test fun revocationAndRegrantAreObservedWithoutCachedGrants() = runBlocking {
        val gateway = Gateway()
        val connection = HealthConnection(gateway)
        gateway.grants = gateway.requiredPermissions
        assertEquals(HealthConnectionState.Ready, connection.check())
        gateway.grants = emptySet()
        assertEquals(HealthConnectionState.PermissionsRequired(0), connection.check())
        gateway.grants = gateway.requiredPermissions
        assertEquals(HealthConnectionState.Ready, connection.check())
        assertEquals(3, gateway.queries)
    }

    @Test fun permissionLossDuringQueryAndServiceFailureCanRecover() = runBlocking {
        val gateway = Gateway()
        val connection = HealthConnection(gateway)
        gateway.failure = SecurityException()
        assertEquals(HealthConnectionState.PermissionsRequired(0), connection.check())
        gateway.failure = IllegalStateException()
        assertEquals(HealthConnectionState.Error, connection.check())
        gateway.failure = null
        gateway.grants = gateway.requiredPermissions
        assertEquals(HealthConnectionState.Ready, connection.check())
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotConvertedIntoAnError(): Unit = runBlocking {
        val gateway = Gateway()
        gateway.failure = CancellationException()
        HealthConnection(gateway).check()
        Unit
    }
}
