package io.github.puvon.enetrend.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.puvon.enetrend.health.HealthConnectionState

@Composable
fun HealthConnectionScreen(
    state: HealthConnectionState,
    actionError: Boolean,
    onRequestPermissions: () -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onInstall: () -> Unit,
    onPrivacy: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        ScreenWithBottomActions(Modifier.fillMaxSize().padding(padding), 24.dp, 16.dp, actions = {
            if (state is HealthConnectionState.PermissionsRequired || state == HealthConnectionState.Ready) {
                TextButton(onClick = onSettings) { Text("Health Connect の設定") }
            }
            if (state != HealthConnectionState.Checking) {
                TextButton(onClick = onRetry) { Text("再確認") }
            }
            if (actionError) {
                Text("画面を開けませんでした。端末の設定から Health Connect を確認してください。", color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onPrivacy) { Text("データの利用とプライバシー") }
        }) {
            Text("EneTrend", style = MaterialTheme.typography.headlineMedium)
            Text("Health Connect との接続")
            Text("カロリー収支と体重変化の分析のため、栄養・総消費カロリー・体重の読み取りを許可してください。")
            when (state) {
                HealthConnectionState.Checking -> {
                    CircularProgressIndicator()
                    Text("接続と権限を確認しています。")
                }
                HealthConnectionState.Unavailable -> Text("この端末では Health Connect を利用できません。")
                HealthConnectionState.UpdateRequired -> {
                    Text("Health Connect のインストールまたは更新が必要です。")
                    Button(onClick = onInstall) { Text("インストール・更新") }
                }
                is HealthConnectionState.PermissionsRequired -> {
                    Text("必要な読み取り権限がありません（${state.grantedCount}/3 許可済み）。拒否・取り消し後も、ここから接続できます。")
                    Button(onClick = onRequestPermissions) { Text("読み取り権限を許可") }
                    Text("権限画面が表示されない場合は Health Connect の設定で変更してください。")
                }
                HealthConnectionState.Ready -> Text("必要な読み取り権限が許可されています。")
                HealthConnectionState.Error -> Text("接続を確認できませんでした。しばらくしてから再確認してください。")
            }

        }
    }
}
