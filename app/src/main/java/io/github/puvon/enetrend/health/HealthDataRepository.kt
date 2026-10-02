package io.github.puvon.enetrend.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.LocalDate

/** Foreground reads only. A failed page never produces a seemingly complete partial result. */
class HealthDataRepository(private val source: HealthDataSource) {
    suspend fun readMetabolism(range: HealthDataRange): MetabolismInputs = try {
        currentCoroutineContext().ensureActive()
        source.readMetabolism(range)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        MetabolismInputs(bodyIssues = setOf(OptionalReadState.ACCESS_DENIED))
    } catch (_: Exception) {
        MetabolismInputs(bodyIssues = setOf(OptionalReadState.ERROR))
    }

    /** Older optional context may be inaccessible, but is never reported as an empty read. */
    suspend fun readDailyCalories(
        range: HealthDataRange,
        requiredStartDate: LocalDate = range.startDate,
    ): DailyCaloriesResult {
        require(requiredStartDate >= range.startDate && requiredStartDate <= range.endDateExclusive)
        return try {
            checkAccess()?.let { return it }
            val daily = linkedMapOf<LocalDate, CalorieTotals>()
            val restricted = linkedSetOf<LocalDate>()
            for (day in range.days()) {
                currentCoroutineContext().ensureActive()
                if (day.isEmpty) continue
                checkAccess()?.let { return it }
                try {
                    daily[day.date] = source.readCalorieTotals(
                        HealthDataRange(day.date, day.date.plusDays(1), range.zoneId),
                    )
                } catch (_: SecurityException) {
                    checkAccess()?.let { return it }
                    if (day.date >= requiredStartDate) return HealthDataResult.AccessDenied
                    restricted.add(day.date)
                }
            }
            checkAccess()?.let { return it }
            DailyCaloriesResult.Available(DailyCalorieData(range, daily, restricted))
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
            val weights = when (val result = readWeights(range)) {
                is WeightDataResult.Available -> result.measurements
                is HealthReadFailure -> return result
            }
            val data = HealthData(range, calories, weights)
            if (data.isEmpty) HealthDataResult.Empty(range) else HealthDataResult.Available(data)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            HealthDataResult.AccessDenied
        } catch (_: Exception) {
            HealthDataResult.Error
        }
    }

    /** Does not request calories again when the caller already has daily aggregates. */
    suspend fun readWeights(range: HealthDataRange): WeightDataResult {
        return try {
            checkAccess()?.let { return it }
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
            WeightDataResult.Available(weights.values.sortedWith(compareBy(WeightMeasurement::time, WeightMeasurement::id)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            // History restrictions can also throw SecurityException; do not claim data is empty.
            checkAccess() ?: HealthDataResult.AccessDenied
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
