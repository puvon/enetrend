package io.github.puvon.enetrend

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import io.github.puvon.enetrend.health.*
import io.github.puvon.enetrend.ui.DashboardRoute
import io.github.puvon.enetrend.ui.theme.EnetrendTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DashboardTimeChangeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instant = AtomicReference(Instant.parse("2026-09-30T14:59:58Z"))
    private val zone = AtomicReference(ZoneId.of("Asia/Tokyo"))
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = this@DashboardTimeChangeTest.zone.get()
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant.get(), zone)
        override fun instant(): Instant = instant.get()
    }
    private val time = DashboardTimeSource(clock) { zone.get() }
    private val changes = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
    private class Source : HealthDataSource {
        override val requiredPermissions = setOf("read")
        @Volatile var granted = requiredPermissions
        @Volatile var fail = false
        @Volatile var calories = CalorieTotals(2000.0, 2200.0)
        val calls = AtomicInteger()
        val gate = AtomicReference<CompletableDeferred<Unit>?>(null)
        val started = AtomicInteger()
        override fun availability() = HealthAvailability.AVAILABLE
        override suspend fun grantedPermissions() = granted
        override suspend fun readCalorieTotals(range: HealthDataRange): CalorieTotals {
            calls.incrementAndGet()
            gate.getAndSet(null)?.let { started.incrementAndGet(); it.await() }
            if (fail) error("test failure")
            return calories
        }
        override suspend fun readWeightPage(range: HealthDataRange, pageToken: String?) = WeightPage(emptyList(), null)
    }
    private fun route(source: Source) {
        val loader = DashboardLoader(HealthDataRepository(source))
        compose.setContent { EnetrendTheme {
            var version by remember { mutableStateOf(0) }
            DashboardRoute(loader, 7, MovingAveragePeriod.SEVEN_DAYS, {}, {}, { version++ }, {}, {}, false,
                remember { SnackbarHostState() }, version, time, changes)
        } }
    }
    private fun plot() = compose.onNodeWithContentDescription("統合グラフ。", substring = true)
    private fun waitForDay(date: String) = compose.waitUntil(10000) {
        compose.onAllNodesWithText("今日の状況（$date）").fetchSemanticsNodes().size == 1
    }
    private fun waitForText(text: String) = compose.waitUntil(10000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun openScreenRollsOverAtMidnightWithoutBroadcastOrUserAction() {
        val source = Source()
        route(source)
        waitForDay("2026-09-30")
        instant.set(Instant.parse("2026-09-30T15:00:01Z"))
        source.calories = CalorieTotals(null, 10.0)
        waitForDay("2026-10-01")
        compose.onNodeWithText("2026-09-25 ～ 2026-10-01").assertExists()
        compose.onNodeWithText("取得済み消費：10.0 kcal").assertExists()
        compose.onNodeWithText("今日の状況（2026-09-30）").assertDoesNotExist()
    }

    @Test fun zoneNotificationCancelsInFlightReadAndUsesNewCalendarWindow() {
        instant.set(Instant.parse("2026-10-01T01:00:00Z"))
        val source = Source()
        route(source)
        waitForDay("2026-10-01")
        val oldRead = CompletableDeferred<Unit>()
        source.gate.set(oldRead)
        compose.onNodeWithText("再確認").performScrollTo().performClick()
        compose.waitUntil(10000) { source.started.get() == 1 }
        zone.set(ZoneId.of("America/Los_Angeles"))
        assertTrue(changes.tryEmit(Unit))
        waitForDay("2026-09-30")
        oldRead.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText("2026-09-24 ～ 2026-09-30").assertExists()
        compose.onNodeWithText("今日の状況（2026-10-01）").assertDoesNotExist()
    }

    @Test fun resumeRefreshesBothAreasAndStoppedScreenDoesNotRead() {
        instant.set(Instant.parse("2026-09-30T03:00:00Z"))
        val source = Source()
        route(source)
        waitForDay("2026-09-30")
        plot().performScrollTo().performTouchInput { click(Offset(1f, height / 2f)) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(10000) { changes.subscriptionCount.value == 0 }
        val stoppedCalls = source.calls.get()
        instant.set(Instant.parse("2026-10-01T03:00:00Z"))
        source.calories = CalorieTotals(null, 600.0)
        assertTrue(changes.tryEmit(Unit))
        assertEquals(stoppedCalls, source.calls.get())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForDay("2026-10-01")
        compose.onNodeWithText("取得済み消費：600.0 kcal").assertExists()
        compose.onNodeWithText("2026-09-25 ～ 2026-10-01").assertExists()
        assertTrue(plot().fetchSemanticsNode().config[SemanticsProperties.StateDescription].startsWith("2026-10-01"))
        assertTrue(source.calls.get() > stoppedCalls)
    }

    @Test fun permissionRevocationFailureAndRecoveryReplaceBothAreas() {
        instant.set(Instant.parse("2026-10-01T03:00:00Z"))
        val source = Source()
        route(source)
        waitForDay("2026-10-01")
        source.granted = emptySet()
        compose.onNodeWithText("再確認").performScrollTo().performClick()
        waitForText("読み取り権限がありません。")
        plot().assertDoesNotExist()
        compose.onNodeWithText("今日の状況（2026-10-01）").assertDoesNotExist()
        source.granted = source.requiredPermissions
        source.fail = true
        compose.onNodeWithText("再確認").performScrollTo().performClick()
        waitForText("データを取得できませんでした。")
        source.fail = false
        source.calories = CalorieTotals(500.0, 3000.0)
        compose.onNodeWithText("再確認").performScrollTo().performClick()
        waitForDay("2026-10-01")
        compose.onNodeWithText("取得済み摂取：500.0 kcal").assertExists()
        compose.onNodeWithText("取得済み消費：3000.0 kcal").assertExists()
        plot().assertExists()
    }
}
