package io.github.puvon.enetrend.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Foreground reads only. A failed page never produces a seemingly complete partial result. */
class HealthDataRepository(private val source: HealthDataSource) {
    suspend fun readDailyCalories(range: HealthDataRange): DailyCaloriesResult {
        return try {
            checkAccess()?.let { return it }
            val daily = linkedMapOf<java.time.LocalDate, CalorieTotals>()
            for (day in range.days()) {
                currentCoroutineContext().ensureActive()
                if (day.isEmpty) continue
                checkAccess()?.let { return it }
                daily[day.date] = source.readCalorieTotals(
                    HealthDataRange(day.date, day.date.plusDays(1), range.zoneId),
                )
            }
            checkAccess()?.let { return it }
            DailyCaloriesResult.Available(DailyCalorieData(range, daily))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            HealthDataResult.AccessDenied
        } catch (_: Exception) {
            // A failed day is not missing data and must not be interpolated.
            HealthDataResult.Error
        }
    }

    suspend fun read(range: HealthDataRange): HealthDataResult {
        return try {
            checkAccess()?.let { return it }
            val calories = source.readCalorieTotals(range)
            val weights = linkedMapOf<String, WeightMeasurement>()
            val seenTokens = mutableSetOf<String>()
            var token: String? = null
            do {
                currentCoroutineContext().ensureActive()
                checkAccess()?.let { return it }
                val page = source.readWeightPage(range, token)
                for (measurement in page.measurements) {
                    // Only identical Health Connect IDs are duplicates; equal times/values are not.
                    require(measurement.id.isNotEmpty()) { "Missing Health Connect record ID" }
                    if (measurement.time < range.startTime || measurement.time >= range.endTime) continue
                    val previous = weights[measurement.id]
                    if (previous == null || measurement.lastModifiedTime > previous.lastModifiedTime) {
                        weights[measurement.id] = measurement
                    }
                }
                token = page.nextPageToken?.takeUnless { it.isEmpty() }
                check(token == null || seenTokens.add(token)) { "Repeated Health Connect page token" }
            } while (token != null)
            // Do not expose collected data if access was revoked while the last request was running.
            checkAccess()?.let { return it }
            val data = HealthData(
                range, calories,
                weights.values.sortedWith(compareBy(WeightMeasurement::time, WeightMeasurement::id)),
            )
            if (data.isEmpty) HealthDataResult.Empty(range) else HealthDataResult.Available(data)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            // History restrictions can also throw SecurityException; do not claim data is empty.
            HealthDataResult.AccessDenied
        } catch (_: Exception) {
            HealthDataResult.Error
        }
    }

    private suspend fun checkAccess(): HealthReadFailure? {
        currentCoroutineContext().ensureActive()
        return when (source.availability()) {
            HealthAvailability.UNAVAILABLE -> HealthDataResult.Unavailable
            HealthAvailability.UPDATE_REQUIRED -> HealthDataResult.UpdateRequired
            HealthAvailability.AVAILABLE -> {
                val missing = source.requiredPermissions - source.grantedPermissions()
                if (missing.isEmpty()) null else HealthDataResult.PermissionsRequired(missing)
            }
        }
    }
}
