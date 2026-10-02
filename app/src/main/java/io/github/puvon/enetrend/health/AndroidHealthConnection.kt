package io.github.puvon.enetrend.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.LeanBodyMassRecord

class AndroidHealthConnection(context: Context) : HealthConnectionGateway {
    private val appContext = context.applicationContext
    override val requiredPermissions: Set<String> = READ_PERMISSIONS

    override fun availability(): HealthAvailability =
        when (HealthConnectClient.getSdkStatus(appContext)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.UPDATE_REQUIRED
            else -> HealthAvailability.UNAVAILABLE
        }

    override suspend fun grantedPermissions(): Set<String> =
        HealthConnectClient.getOrCreate(appContext).permissionController.getGrantedPermissions()

    companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        val METABOLISM_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        )
        val READ_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(NutritionRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
        )
    }
}
