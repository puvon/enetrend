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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import io.github.puvon.enetrend.health.AndroidHealthConnection
import io.github.puvon.enetrend.health.HealthConnection
import io.github.puvon.enetrend.health.HealthConnectionState
import io.github.puvon.enetrend.ui.HealthConnectionScreen
import io.github.puvon.enetrend.ui.theme.EnetrendTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val connection by lazy { HealthConnection(AndroidHealthConnection(this)) }
    private var state: HealthConnectionState by mutableStateOf(HealthConnectionState.Checking)
    private var actionError by mutableStateOf(false)
    private var checkJob: Job? = null
    private val permissionsLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EnetrendTheme {
                HealthConnectionScreen(
                    state = state,
                    actionError = actionError,
                    onRequestPermissions = { refresh(requestPermissions = true) },
                    onRetry = { refresh() },
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

    private fun refresh(requestPermissions: Boolean = false) {
        checkJob?.cancel()
        state = HealthConnectionState.Checking
        actionError = false
        checkJob = lifecycleScope.launch {
            state = connection.check()
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
