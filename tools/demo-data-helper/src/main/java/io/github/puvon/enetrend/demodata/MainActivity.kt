package io.github.puvon.enetrend.demodata

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Power
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.math.sin
import kotlin.reflect.KClass

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            textSize = 19f
            setPadding(32, 64, 32, 32)
            text = "EneTrend Demo Data\nEmulator only. Fictional data.\nChecking device..."
        }
        setContentView(status)
        val mode = intent.getStringExtra("mode") ?: "inspect"
        val requestId = intent.getStringExtra("requestId")?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }
        filesDir.mkdirs()
        Thread {
            val summary = try {
                checkEmulator() // Before constructing the client or accessing Health Connect.
                require(mode in setOf("inspect", "seed")) { "Unsupported mode" }
                runBlocking {
                    val client = HealthConnectClient.getOrCreate(applicationContext)
                    val zone = ZoneId.systemDefault()
                    val today = LocalDate.now(zone)
                    val endDate = intent.getStringExtra("endDate")?.let(LocalDate::parse) ?: today
                    require(endDate <= today) { "End date must not be in the future" }
                    val start = endDate.minusDays(29)
                    val filter = TimeRangeFilter.between(start.atStartOfDay(zone).toInstant(), endDate.plusDays(1).atStartOfDay(zone).toInstant())
                    if (mode == "seed") {
                        val records = demoRecords(start, endDate, zone, Instant.now())
                        checkEmulator() // Direct APK launches must also pass this check for every write.
                        client.insertRecords(records)
                        "OK SEEDED ${records.size} fictional records requested; $start through $endDate\n" +
                            "Source: $packageName; stable IDs prevent duplicate records.\n" + counts(client, filter)
                    } else "OK INSPECT $start through $endDate\n" + counts(client, filter)
                }
            } catch (error: Exception) {
                "FAILED: ${error.javaClass.simpleName}. Emulator checks, permissions, dates, or Health Connect failed."
            }
            requestId?.let {
                val temporary = File(filesDir, "result-$it.tmp")
                temporary.writeText(summary)
                check(temporary.renameTo(File(filesDir, "result-$it.txt")))
            }
            runOnUiThread { status.text = "EneTrend Demo Data\nEmulator only. Fictional data.\n\n$summary" }
        }.start()
    }

    private fun checkEmulator() {
        check(EmulatorPolicy.allows(
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            Build.HARDWARE, Build.MODEL, Build.FINGERPRINT,
            property("ro.kernel.qemu"), property("ro.boot.qemu"),
        )) { "Only standard Android SDK emulators are allowed" }
    }

    private fun property(name: String): String = try {
        val process = ProcessBuilder("/system/bin/getprop", name).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroy()
            ""
        } else if (process.exitValue() != 0) "" else process.inputStream.bufferedReader().use { it.readText().trim() }
    } catch (_: Exception) { "" }

    private fun demoRecords(start: LocalDate, endDate: LocalDate, zone: ZoneId, now: Instant): List<Record> {
        val records = mutableListOf<Record>()
        val baseline = start.atStartOfDay(zone)
        records.add(BasalMetabolicRateRecord(baseline.toInstant(), baseline.offset,
            Power.kilocaloriesPerDay(1600.0), Metadata.manualEntry(clientRecordId = "enetrend-demo-bmr-$start", clientRecordVersion = 1)))
        for (index in 0..29) {
            val date = start.plusDays(index.toLong())
            val morning = date.atTime(7, 0).atZone(zone)
            val from = date.atStartOfDay(zone)
            val to = minOf(date.plusDays(1).atStartOfDay(zone).toInstant(), now.minusSeconds(1))
            fun metadata(kind: String) = Metadata.manualEntry(clientRecordId = "enetrend-demo-v1-$date-$kind", clientRecordVersion = 1)
            if (to > from.toInstant()) {
                if (index != 26) {
                    val intake = if (date == endDate) 1450.0 else 2150.0 + 220.0 * sin(index * 1.3) + if (index % 7 in 4..5) 500.0 else 0.0
                    records.add(NutritionRecord(from.toInstant(), from.offset, to, zone.rules.getOffset(to),
                        metadata("nutrition"), energy = Energy.kilocalories(intake), name = "EneTrend DEMO (fictional daily total)"))
                }
                if (index !in 15..16) {
                    val burned = if (date == endDate) 1700.0 else 2510.0 + 180.0 * sin(index * 0.75)
                    records.add(TotalCaloriesBurnedRecord(from.toInstant(), from.offset, to, zone.rules.getOffset(to),
                        Energy.kilocalories(burned), metadata("burned")))
                }
            }
            if (index !in setOf(9, 10, 19) && morning.toInstant() < now) {
                records.add(WeightRecord(morning.toInstant(), morning.offset,
                    Mass.kilograms(74.2 - index * 0.052 + 0.24 * sin(index * 1.1)), metadata("weight")))
            }
        }
        return records
    }

    private suspend fun counts(client: HealthConnectClient, filter: TimeRangeFilter): String {
        val summaries = mutableListOf<String>()
        for (type in listOf(NutritionRecord::class, TotalCaloriesBurnedRecord::class, WeightRecord::class)) {
            summaries.add(countType(client, filter, type))
        }
        return summaries.joinToString("\n")
    }

    private suspend fun <T : Record> countType(client: HealthConnectClient, filter: TimeRangeFilter, type: KClass<T>): String {
        var token: String? = null
        val counts = sortedMapOf<String, Int>()
        do {
            val page = client.readRecords(ReadRecordsRequest(type, filter, pageSize = 1000, pageToken = token))
            page.records.forEach { record ->
                val origin = record.metadata.dataOrigin.packageName
                counts[origin] = (counts[origin] ?: 0) + 1
            }
            token = page.pageToken
        } while (!token.isNullOrEmpty())
        return "${type.simpleName}: $counts"
    }
}
