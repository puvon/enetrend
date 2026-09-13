package io.github.puvon.enetrend.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter

/** Use through HealthDataRepository, which checks availability and permissions before every read. */
class AndroidHealthDataSource(context: Context) : HealthDataSource {
    private val appContext = context.applicationContext
    private val connection = AndroidHealthConnection(appContext)
    private val client by lazy { HealthConnectClient.getOrCreate(appContext) }

    override val requiredPermissions: Set<String> get() = connection.requiredPermissions
    override fun availability(): HealthAvailability = connection.availability()
    override suspend fun grantedPermissions(): Set<String> = connection.grantedPermissions()

    override suspend fun readCalorieTotals(range: HealthDataRange): CalorieTotals {
        val result = client.aggregate(
            AggregateRequest(
                metrics = setOf(NutritionRecord.ENERGY_TOTAL, TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(range.startTime, range.endTime),
            ),
        )
        // Activity priorities/deduplication belong to Health Connect. Nutrition from different
        // sources is combined by the API; identical meals across sources are not inferred here.
        return CalorieTotals(
            intakeKilocalories = result[NutritionRecord.ENERGY_TOTAL]?.inKilocalories,
            burnedKilocalories = result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories,
            dataOrigins = result.dataOrigins.map { it.packageName }.toSet(),
        )
    }

    override suspend fun readWeightPage(range: HealthDataRange, pageToken: String?): WeightPage {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(range.startTime, range.endTime),
                ascendingOrder = true,
                pageSize = 1000,
                pageToken = pageToken,
            ),
        )
        return WeightPage(
            response.records.map { record ->
                WeightMeasurement(
                    id = record.metadata.id,
                    time = record.time,
                    zoneOffset = record.zoneOffset,
                    kilograms = record.weight.inKilograms,
                    dataOrigin = record.metadata.dataOrigin.packageName,
                    lastModifiedTime = record.metadata.lastModifiedTime,
                )
            },
            response.pageToken,
        )
    }
}
