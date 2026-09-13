package io.github.puvon.enetrend.health

import kotlinx.coroutines.CancellationException

enum class HealthAvailability { AVAILABLE, UPDATE_REQUIRED, UNAVAILABLE }

sealed interface HealthConnectionState {
    data object Checking : HealthConnectionState
    data object Unavailable : HealthConnectionState
    data object UpdateRequired : HealthConnectionState
    data class PermissionsRequired(val grantedCount: Int) : HealthConnectionState
    data object Ready : HealthConnectionState
    data object Error : HealthConnectionState
}

interface HealthConnectionGateway {
    val requiredPermissions: Set<String>
    fun availability(): HealthAvailability
    suspend fun grantedPermissions(): Set<String>
}

// Permission grants are queried afresh, because users may revoke them outside the app.
class HealthConnection(private val gateway: HealthConnectionGateway) {
    suspend fun check(): HealthConnectionState = try {
        when (gateway.availability()) {
            HealthAvailability.UNAVAILABLE -> HealthConnectionState.Unavailable
            HealthAvailability.UPDATE_REQUIRED -> HealthConnectionState.UpdateRequired
            HealthAvailability.AVAILABLE -> {
                val granted = gateway.grantedPermissions().intersect(gateway.requiredPermissions)
                if (granted.containsAll(gateway.requiredPermissions)) {
                    HealthConnectionState.Ready
                } else {
                    HealthConnectionState.PermissionsRequired(granted.size)
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        HealthConnectionState.PermissionsRequired(0)
    } catch (_: Exception) {
        HealthConnectionState.Error
    }
}
