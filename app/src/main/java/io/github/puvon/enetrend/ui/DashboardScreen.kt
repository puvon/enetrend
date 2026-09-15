package io.github.puvon.enetrend.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.puvon.enetrend.health.*
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun DashboardRoute(
    loader: DashboardLoader,
    displayDays: Int,
    averagePeriod: MovingAveragePeriod,
    onDisplayDays: (Int) -> Unit,
    onAveragePeriod: (MovingAveragePeriod) -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onPrivacy: () -> Unit,
    actionError: Boolean,
    snackbarHostState: SnackbarHostState,
) {
    var state: DashboardState by remember { mutableStateOf(DashboardState.Loading) }
    LaunchedEffect(loader, displayDays, averagePeriod) {
        state = DashboardState.Loading
        state = try {
            withContext(Dispatchers.IO) {
                val zone = ZoneId.systemDefault()
                loader.load(LocalDate.now(zone), zone, displayDays, averagePeriod)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DashboardState.Failed(HealthDataResult.Error)
        }
    }
    DashboardScreen(state, displayDays, averagePeriod, onDisplayDays, onAveragePeriod,
        onRetry, onSettings, onPrivacy, actionError, snackbarHostState)
}

@Composable
fun DashboardScreen(
    state: DashboardState,
    displayDays: Int,
    averagePeriod: MovingAveragePeriod,
    onDisplayDays: (Int) -> Unit,
    onAveragePeriod: (MovingAveragePeriod) -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onPrivacy: () -> Unit,
    actionError: Boolean = false,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("EneTrend", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry) { Text("再確認") }
                TextButton(onClick = onSettings) { Text("Health Connect の設定") }
            }
            if (actionError) Text("画面を開けませんでした。端末の設定から確認してください。")
            when (state) {
                DashboardState.Loading -> {
                    CircularProgressIndicator()
                    Text("データを読み込んでいます。")
                }
                is DashboardState.Failed -> Text(when (state.reason) {
                    is HealthDataResult.PermissionsRequired -> "読み取り権限がありません。再確認から権限を確認してください。"
                    HealthDataResult.Unavailable -> "この端末では Health Connect を利用できません。"
                    HealthDataResult.UpdateRequired -> "Health Connect の更新が必要です。再確認してください。"
                    HealthDataResult.AccessDenied -> "データへのアクセスが制限されています。権限と読み取り可能な期間を確認してください。"
                    HealthDataResult.Error -> "データを取得できませんでした。再確認でやり直してください。"
                })
                is DashboardState.Ready -> DashboardContent(state.data) { PeriodControls(displayDays, averagePeriod, onDisplayDays, onAveragePeriod) }
            }
            if (state !is DashboardState.Ready) PeriodControls(displayDays, averagePeriod, onDisplayDays, onAveragePeriod)
            TextButton(onClick = onPrivacy) { Text("データの利用とプライバシー") }
        }
    }
}

@Composable
private fun DashboardContent(data: DashboardData, controls: @Composable () -> Unit) {
    val chart = remember(data) { DashboardChartProjector.project(data) }
    val days = chart.days
    val range = data.balances.range
    Text("${range.startDate} ～ ${range.endDateExclusive.minusDays(1)}")
    Text("収支 = 摂取 − 消費（−：消費超過／＋：摂取超過）")
    if (data.historyLimited) Text("表示期間前のデータへのアクセスが制限されています。開始付近の平均・補間は利用できる記録だけに基づきます。")
    if (!data.hasData) {
        Text("この期間に表示できるデータがありません。")
        controls()
        return
    }

    var selected by rememberSaveable(range.startDate.toString(), range.endDateExclusive.toString()) {
        mutableStateOf(days.lastIndex)
    }
    DashboardChart(chart, selected) { selected = it }
    controls()
    Text("表示期間の開始を0 kcalとして計算します。期間を変えると、同じ日の期間累積収支も変わります。")
    if (data.balances.periodCumulative.any { !it.isComplete }) Text("欠測日以降の期間累積収支は未算出です。詳細の小計は算出できた日のみです。")
    Text("グラフのタップまたはスライダーで日付を選択")
    Slider(value = selected.toFloat(), onValueChange = { selected = it.roundToInt().coerceIn(0, days.lastIndex) },
        valueRange = 0f..days.lastIndex.coerceAtLeast(1).toFloat(), steps = (days.size - 2).coerceAtLeast(0), enabled = days.size > 1,
        modifier = Modifier.semantics {
            contentDescription = "詳細を表示する日付"
            stateDescription = days[selected].date.toString()
        })
    days[selected].detailLines().forEachIndexed { index, line ->
        Text(line, style = if (index == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
    }
    Text("今日の記録は途中です。記録がある日も記録漏れがないとは限りません。欠測は線でつなぎません。移動平均は取得できた実測値のみを使用します。")
}

@Composable
private fun PeriodControls(displayDays: Int, averagePeriod: MovingAveragePeriod, onDisplayDays: (Int) -> Unit, onAveragePeriod: (MovingAveragePeriod) -> Unit) {
    Text("表示期間（今日まで）")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(7, 14, 30).forEach { days ->
            FilterChip(selected = days == displayDays, onClick = { onDisplayDays(days) },
                label = { Text("${days}日間") })
        }
    }
    Text("移動平均の計算期間")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MovingAveragePeriod.entries.forEach { period ->
            FilterChip(selected = period == averagePeriod, onClick = { onAveragePeriod(period) },
                label = { Text("${period.days}日平均") })
        }
    }
}
