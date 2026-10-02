package io.github.puvon.enetrend.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class MetabolismPermission { ACTIVE, TOTAL, WEIGHT, FAT, LEAN }
data class BodyMeasurementPage(val records: List<BodyMeasurement>, val nextPageToken: String?)

interface MetabolismSource {
    suspend fun permissions(): Set<MetabolismPermission>
    suspend fun readEnergy(range: HealthDataRange, active: Boolean): Double?
    suspend fun readBodyPage(range: HealthDataRange, kind: BodyMeasurementKind, token: String?): BodyMeasurementPage
}

/** Optional reads fail independently; never return a partial page series as a successful read. */
class MetabolismReader(private val source: MetabolismSource) {
    suspend fun read(range: HealthDataRange): MetabolismInputs {
        val bodyRange = range.copy(startDate = range.startDate.minusDays(13))
        val issues = mutableSetOf<OptionalReadState>()
        val bodyStates = mutableMapOf<BodyMeasurementKind, Set<OptionalReadState>>()
        val body = mutableListOf<BodyMeasurement>()
        for ((kind, permission) in listOf(
            BodyMeasurementKind.WEIGHT_KG to MetabolismPermission.WEIGHT,
            BodyMeasurementKind.FAT_PERCENT to MetabolismPermission.FAT,
            BodyMeasurementKind.LEAN_KG to MetabolismPermission.LEAN,
        )) {
            val states = mutableSetOf<OptionalReadState>()
            val all = optional(permission) { pages(bodyRange, kind) }
            if (all.state == OptionalReadState.ACCESS_DENIED) {
                // A history boundary can reject the whole range. Recover readable local days only.
                for (day in bodyRange.days().filterNot { it.isEmpty }) {
                    val part = optional(permission) { pages(HealthDataRange(day.date, day.date.plusDays(1), range.zoneId), kind) }
                    body.addAll(part.value.orEmpty())
                    states.add(part.state)
                    if (part.state != OptionalReadState.AVAILABLE) issues.add(part.state)
                }
            } else {
                body.addAll(all.value.orEmpty())
                states.add(all.state)
                if (all.state != OptionalReadState.AVAILABLE) issues.add(all.state)
            }
            bodyStates[kind] = states
        }
        val active = linkedMapOf<java.time.LocalDate, OptionalHealthValue<Double>>()
        val totals = linkedMapOf<java.time.LocalDate, OptionalHealthValue<Double>>()
        for (day in range.days().filterNot { it.isEmpty }) {
            val daily = HealthDataRange(day.date, day.date.plusDays(1), range.zoneId)
            active[day.date] = optional(MetabolismPermission.ACTIVE) { source.readEnergy(daily, true) }
            totals[day.date] = optional(MetabolismPermission.TOTAL) { source.readEnergy(daily, false) }
        }
        // Recheck after the last read so revocation cannot leave stale optional data in the result.
        val granted = source.permissions()
        val retained = body.filter { when (it.kind) {
            BodyMeasurementKind.WEIGHT_KG -> MetabolismPermission.WEIGHT
            BodyMeasurementKind.FAT_PERCENT -> MetabolismPermission.FAT
            BodyMeasurementKind.LEAN_KG -> MetabolismPermission.LEAN
        } in granted }
        if (retained.size != body.size) issues.add(OptionalReadState.PERMISSION_REQUIRED)
        for ((kind, permission) in mapOf(BodyMeasurementKind.WEIGHT_KG to MetabolismPermission.WEIGHT,
            BodyMeasurementKind.FAT_PERCENT to MetabolismPermission.FAT, BodyMeasurementKind.LEAN_KG to MetabolismPermission.LEAN)) {
            if (permission !in granted) bodyStates[kind] = setOf(OptionalReadState.PERMISSION_REQUIRED)
        }
        return MetabolismInputs(retained,
            if (MetabolismPermission.ACTIVE in granted) active else active.mapValues { OptionalHealthValue(state = OptionalReadState.PERMISSION_REQUIRED) },
            if (MetabolismPermission.TOTAL in granted) totals else totals.mapValues { OptionalHealthValue(state = OptionalReadState.PERMISSION_REQUIRED) }, issues, bodyStates)
    }

    private suspend fun pages(range: HealthDataRange, kind: BodyMeasurementKind): List<BodyMeasurement> {
        val records = mutableListOf<BodyMeasurement>()
        val tokens = mutableSetOf<String>()
        var token: String? = null
        do {
            currentCoroutineContext().ensureActive()
            val page = source.readBodyPage(range, kind, token)
            records.addAll(page.records.filter {
                it.kind == kind && it.origin == FITBIT_PACKAGE && it.time >= range.startTime && it.time < range.endTime
            })
            token = page.nextPageToken?.takeUnless { it.isEmpty() }
            check(token == null || tokens.add(token)) { "Repeated page token" }
        } while (token != null)
        return records
    }

    private suspend fun <T> optional(permission: MetabolismPermission, read: suspend () -> T?): OptionalHealthValue<T> = try {
        currentCoroutineContext().ensureActive()
        if (permission !in source.permissions()) OptionalHealthValue(state = OptionalReadState.PERMISSION_REQUIRED)
        else {
            val value = read()
            if (permission in source.permissions()) OptionalHealthValue(value)
            else OptionalHealthValue(state = OptionalReadState.PERMISSION_REQUIRED)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        OptionalHealthValue(state = OptionalReadState.ACCESS_DENIED)
    } catch (_: Exception) {
        OptionalHealthValue(state = OptionalReadState.ERROR)
    }
}
