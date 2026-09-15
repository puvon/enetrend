package io.github.puvon.enetrend

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.SnackbarHostState
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import io.github.puvon.enetrend.health.AndroidHealthConnection
import io.github.puvon.enetrend.health.HealthConnection
import io.github.puvon.enetrend.health.HealthConnectionState
import io.github.puvon.enetrend.health.AndroidHealthDataSource
import io.github.puvon.enetrend.health.DashboardLoader
import io.github.puvon.enetrend.health.HealthDataRepository
import io.github.puvon.enetrend.health.MovingAveragePeriod
import io.github.puvon.enetrend.ui.DashboardRoute
import io.github.puvon.enetrend.ui.HealthConnectionScreen
import io.github.puvon.enetrend.ui.theme.EnetrendTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val connection by lazy { HealthConnection(AndroidHealthConnection(this)) }
    private val dashboardLoader by lazy { DashboardLoader(HealthDataRepository(AndroidHealthDataSource(this))) }
    private var state: HealthConnectionState by mutableStateOf(HealthConnectionState.Checking)
    private var actionError by mutableStateOf(false)
    private var readVersion by mutableStateOf(0)
    private var checkJob: Job? = null
    private val snackbarHostState = SnackbarHostState()
    private val permissionsLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EnetrendTheme {
                var displayDays by rememberSaveable { mutableStateOf(30) }
                var averageDays by rememberSaveable { mutableStateOf(7) }
                if (state == HealthConnectionState.Ready) {
                    DashboardRoute(
                        loader = dashboardLoader,
                        displayDays = displayDays,
                        averagePeriod = MovingAveragePeriod.entries.first { it.days == averageDays },
                        onDisplayDays = { displayDays = it },
                        onAveragePeriod = { averageDays = it.days },
                        onRetry = { refresh(notifyResult = true) },
                        onSettings = { openExternal(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) },
                        onPrivacy = { startActivity(Intent(this, PermissionsRationaleActivity::class.java)) },
                        actionError = actionError,
                        snackbarHostState = snackbarHostState,
                        readVersion = readVersion,
                    )
                } else HealthConnectionScreen(
                    state = state,
                    actionError = actionError,
                    onRequestPermissions = { refresh(requestPermissions = true) },
                    onRetry = { refresh(notifyResult = true) },
                    snackbarHostState = snackbarHostState,
                    onSettings = {
                        openExternal(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
                    },
                    onInstall = {
                        openExternal(Intent(Intent.ACTION_VIEW, (
                            "https://play.google.com/store/apps/details?id=" +
                                AndroidHealthConnection.PROVIDER_PACKAGE
                        ).toUri()))
                    },
                    onPrivacy = {
                        startActivity(Intent(this, PermissionsRationaleActivity::class.java))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        checkJob?.cancel()
        state = HealthConnectionState.Checking
        super.onPause()
    }

    private fun refresh(requestPermissions: Boolean = false, notifyResult: Boolean = false) {
        checkJob?.cancel()
        state = HealthConnectionState.Checking
        actionError = false
        checkJob = lifecycleScope.launch {
            state = connection.check()
            if (state == HealthConnectionState.Ready) readVersion++
            if (notifyResult) {
                snackbarHostState.showSnackbar(
                    when (val result = state) {
                        HealthConnectionState.Ready -> "再確認しました。読み取り権限は許可されています。"
                        is HealthConnectionState.PermissionsRequired ->
                            "再確認しました。読み取り権限は ${result.grantedCount}/3 許可済みです。"
                        HealthConnectionState.Unavailable -> "再確認しました。この端末では利用できません。"
                        HealthConnectionState.UpdateRequired -> "再確認しました。インストールまたは更新が必要です。"
                        else -> "再確認に失敗しました。しばらくしてからお試しください。"
                    },
                )
            }
            if (requestPermissions && state is HealthConnectionState.PermissionsRequired) {
                try {
                    permissionsLauncher.launch(AndroidHealthConnection.READ_PERMISSIONS)
                } catch (_: ActivityNotFoundException) {
                    actionError = true
                } catch (_: SecurityException) {
                    actionError = true
                }
            }
        }
    }

    private fun openExternal(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            actionError = true
        } catch (_: SecurityException) {
            actionError = true
        }
    }
}
