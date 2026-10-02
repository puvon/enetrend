package io.github.puvon.enetrend.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter

class AndroidMetabolismSource(private val client: HealthConnectClient) : MetabolismSource {
    private val origins = setOf(DataOrigin(FITBIT_PACKAGE))
    override suspend fun permissions(): Set<MetabolismPermission> {
        val granted = client.permissionController.getGrantedPermissions()
        return listOf(
            MetabolismPermission.ACTIVE to ActiveCaloriesBurnedRecord::class,
            MetabolismPermission.TOTAL to TotalCaloriesBurnedRecord::class,
            MetabolismPermission.WEIGHT to WeightRecord::class,
            MetabolismPermission.FAT to BodyFatRecord::class,
            MetabolismPermission.LEAN to LeanBodyMassRecord::class,
        ).filter { HealthPermission.getReadPermission(it.second) in granted }.map { it.first }.toSet()
    }

    override suspend fun readEnergy(range: HealthDataRange, active: Boolean): Double? {
        val metric = if (active) ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL else TotalCaloriesBurnedRecord.ENERGY_TOTAL
        val result = client.aggregate(AggregateRequest(setOf(metric),
            TimeRangeFilter.between(range.startTime, range.endTime), origins))
        return HealthUnits.kilocalories(result[metric])
    }

    override suspend fun readBodyPage(range: HealthDataRange, kind: BodyMeasurementKind, token: String?): BodyMeasurementPage = when (kind) {
        BodyMeasurementKind.WEIGHT_KG -> page<WeightRecord>(range, token) {
            BodyMeasurement(it.metadata.id, it.time, it.metadata.lastModifiedTime, kind, HealthUnits.kilograms(it.weight), it.metadata.dataOrigin.packageName)
        }
        BodyMeasurementKind.FAT_PERCENT -> page<BodyFatRecord>(range, token) {
            BodyMeasurement(it.metadata.id, it.time, it.metadata.lastModifiedTime, kind, HealthUnits.percent(it.percentage), it.metadata.dataOrigin.packageName)
        }
        BodyMeasurementKind.LEAN_KG -> page<LeanBodyMassRecord>(range, token) {
            BodyMeasurement(it.metadata.id, it.time, it.metadata.lastModifiedTime, kind, HealthUnits.kilograms(it.mass), it.metadata.dataOrigin.packageName)
        }
    }

    private suspend inline fun <reified T : Record> page(
        range: HealthDataRange, token: String?, convert: (T) -> BodyMeasurement,
    ): BodyMeasurementPage {
        val response = client.readRecords(ReadRecordsRequest<T>(
            timeRangeFilter = TimeRangeFilter.between(range.startTime, range.endTime),
            dataOriginFilter = origins, ascendingOrder = true, pageSize = 1000, pageToken = token,
        ))
        return BodyMeasurementPage(response.records.map(convert), response.pageToken)
    }
}
