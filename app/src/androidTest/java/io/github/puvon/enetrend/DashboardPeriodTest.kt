package io.github.puvon.enetrend

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.puvon.enetrend.health.*
import io.github.puvon.enetrend.ui.DashboardRoute
import io.github.puvon.enetrend.ui.theme.EnetrendTheme
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DashboardPeriodTest {
    @get:Rule val compose = createComposeRule()
    private class Source : HealthDataSource {
        override val requiredPermissions = setOf("read")
        val nextGate = AtomicReference<CompletableDeferred<Unit>?>(null)
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        @Volatile var fail = false
        override fun availability() = HealthAvailability.AVAILABLE
        override suspend fun grantedPermissions() = requiredPermissions
        override suspend fun readCalorieTotals(range: HealthDataRange): CalorieTotals {
            if (fail) error("test read failure")
            nextGate.getAndSet(null)?.let { gate ->
                started.incrementAndGet()
                try { gate.await() } catch (e: CancellationException) { cancelled.incrementAndGet(); throw e }
            }
            return CalorieTotals(2000.0, 2200.0)
        }
        override suspend fun readWeightPage(range: HealthDataRange, pageToken: String?) = WeightPage(
            range.days().map { day ->
                WeightMeasurement(day.date.toString(), day.date.atTime(8, 0).atZone(range.zoneId).toInstant(), null,
                    70.0 + day.date.dayOfYear / 100.0, "test", Instant.EPOCH)
            }.toList(), null)
    }
    private fun plot() = compose.onNodeWithContentDescription("統合グラフ。", substring = true)
    private fun waitForPlot() = compose.waitUntil(10000) {
        compose.onAllNodesWithContentDescription("統合グラフ。", substring = true).fetchSemanticsNodes().size == 1
    }

    @Test fun retryReloadsAfterReadFailureWithoutChangingPeriodOrLoader() {
        val source = Source().apply { fail = true }
        val loader = DashboardLoader(HealthDataRepository(source))
        var version by mutableStateOf(0)
        compose.setContent { EnetrendTheme {
            DashboardRoute(loader, 30, MovingAveragePeriod.SEVEN_DAYS, {}, {}, { version++ }, {}, {}, false,
                remember { SnackbarHostState() }, readVersion = version)
        } }
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("データを取得できませんでした。", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        plot().assertDoesNotExist()
        source.fail = false
        compose.onNodeWithText("再確認").performClick()
        waitForPlot()
        compose.onAllNodesWithText("データを取得できませんでした。", substring = true).assertCountEquals(0)
    }

    @Test fun rapidRequestsHideOldGraphAndCancelSupersededReads() {
        val source = Source()
        val loader = DashboardLoader(HealthDataRepository(source))
        var days by mutableStateOf(30)
        compose.setContent { EnetrendTheme {
            DashboardRoute(loader, days, MovingAveragePeriod.SEVEN_DAYS, { days = it }, {}, {}, {}, {}, false,
                remember { SnackbarHostState() })
        } }
        waitForPlot()
        val seven = CompletableDeferred<Unit>()
        source.nextGate.set(seven)
        compose.onNodeWithText("7日間").performScrollTo().performClick()
        compose.waitUntil(10000) { source.started.get() == 1 }
        plot().assertDoesNotExist()
        compose.onNodeWithText("データを読み込んでいます。").performScrollTo().assertIsDisplayed()
        val fourteen = CompletableDeferred<Unit>()
        source.nextGate.set(fourteen)
        compose.onNodeWithText("14日間").performScrollTo().performClick()
        compose.waitUntil(10000) { source.started.get() == 2 && source.cancelled.get() == 1 }
        val thirty = CompletableDeferred<Unit>()
        source.nextGate.set(thirty)
        compose.onNodeWithText("30日間").performScrollTo().performClick()
        compose.waitUntil(10000) { source.started.get() == 3 && source.cancelled.get() == 2 }
        thirty.complete(Unit)
        waitForPlot()
        fourteen.complete(Unit)
        seven.complete(Unit)
        compose.waitForIdle()
        val today = LocalDate.now()
        compose.onNodeWithText("期間開始（${today.minusDays(29)}）：0.0 kcal").performScrollTo().assertIsDisplayed()
        assertTrue(plot().fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("期間累積収支：-6000.0 kcal"))
        compose.onNodeWithText("30日間").assertIsSelected()
    }

    @Test fun averageReloadAndRestorationKeepDateButDisplayChangeSelectsLastDay() {
        val loader = DashboardLoader(HealthDataRepository(Source()))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { EnetrendTheme {
            var days by rememberSaveable { mutableStateOf(30) }
            var average by rememberSaveable { mutableStateOf(7) }
            DashboardRoute(loader, days, MovingAveragePeriod.entries.first { it.days == average }, { days = it },
                { average = it.days }, {}, {}, {}, false, remember { SnackbarHostState() })
        } }
        waitForPlot()
        compose.onNodeWithText("7日間").performScrollTo().performClick()
        waitForPlot()
        plot().performScrollTo().performTouchInput { click(Offset(1f, height / 2f)) }
        val first = LocalDate.now().minusDays(6).toString()
        assertTrue(plot().fetchSemanticsNode().config[SemanticsProperties.StateDescription].startsWith(first))
        val baseline = compose.onNodeWithText("累積0の基準：", substring = true).fetchSemanticsNode().config[SemanticsProperties.Text]
        compose.onNodeWithText("30日平均").performScrollTo().performClick()
        waitForPlot()
        assertTrue(plot().fetchSemanticsNode().config[SemanticsProperties.StateDescription].startsWith(first))
        assertEquals(baseline, compose.onNodeWithText("累積0の基準：", substring = true).fetchSemanticsNode().config[SemanticsProperties.Text])
        restoration.emulateSavedInstanceStateRestore()
        waitForPlot()
        compose.onNodeWithText("7日間").assertIsSelected()
        compose.onNodeWithText("30日平均").assertIsSelected()
        assertTrue(plot().fetchSemanticsNode().config[SemanticsProperties.StateDescription].startsWith(first))
        compose.onNodeWithText("14日間").performScrollTo().performClick()
        waitForPlot()
        val description = plot().fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue(description.startsWith(LocalDate.now().toString()))
        assertTrue(description.contains("期間累積収支：-2800.0 kcal"))
    }
}
