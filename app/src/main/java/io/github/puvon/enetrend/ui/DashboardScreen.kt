package io.github.puvon.enetrend.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.puvon.enetrend.health.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
                is DashboardState.Ready -> DashboardContent(state.data)
            }
            TextButton(onClick = onPrivacy) { Text("データの利用とプライバシー") }
        }
    }
}

@Composable
private fun DashboardContent(data: DashboardData) {
    val days = data.balances.daily
    val range = data.balances.range
    Text("${range.startDate} ～ ${range.endDateExclusive.minusDays(1)}")
    Text("収支 = 摂取 − 消費（−：消費超過／＋：摂取超過）")
    if (data.historyLimited) Text("表示期間前のデータへのアクセスが制限されています。開始付近の平均・補間は利用できる記録だけに基づきます。")
    if (!data.hasData) {
        Text("この期間に表示できるデータがありません。")
        return
    }
    Text("白抜き・破線：補間／推定　点線：移動平均", style = MaterialTheme.typography.labelMedium)
    var selected by rememberSaveable(range.startDate.toString(), range.endDateExclusive.toString()) {
        mutableStateOf(days.lastIndex)
    }
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    TrendChart("日別カロリー収支", "kcal", listOf(PlotSeries(days.map { it.kilocalories },
        days.map { it.isEstimated }, primary)), selected, bars = true, includeZero = true)
    TrendChart("累積カロリー収支", "kcal", listOf(PlotSeries(data.balances.cumulative.map { it.kilocalories },
        data.balances.cumulative.map { it.isEstimated }, primary)), selected, includeZero = true)
    if (data.balances.cumulative.any { !it.isComplete }) Text("欠測日以降の累積は未算出です。詳細の小計は算出できた日のみです。")
    TrendChart("体重・移動平均", "kg", listOf(
        PlotSeries(data.weights.map { it.display.number() }, data.weights.map { it.display is DisplayValue.Interpolated }, primary),
        PlotSeries(data.weights.map { it.movingAverage.kilograms },
            data.weights.map { it.movingAverage.hasInsufficientDays }, secondary, dotted = true),
    ), selected)
    Text("体重：実線・点／移動平均：点線。平均の白抜き点は実測日数不足。")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(range.startDate.format(DateTimeFormatter.ofPattern("M/d")))
        Text(range.endDateExclusive.minusDays(1).format(DateTimeFormatter.ofPattern("M/d")))
    }
    Text("日付を選んで詳細を確認（3つのグラフで共通）")
    Slider(value = selected.toFloat(), onValueChange = { selected = it.roundToInt() },
        valueRange = 0f..days.lastIndex.toFloat(), steps = (days.size - 2).coerceAtLeast(0),
        modifier = Modifier.semantics { contentDescription = "詳細を表示する日付" })
    val daily = days[selected]
    val cumulative = data.balances.cumulative[selected]
    val weight = data.weights[selected]
    Text(daily.date.toString(), style = MaterialTheme.typography.titleMedium)
    Text("摂取：${daily.source.intake.label("kcal")}／消費：${daily.source.burned.label("kcal")}")
    Text("日別収支：${daily.kilocalories.formatted("kcal")}${if (daily.isEstimated) "（推定）" else ""}")
    Text("累積：${cumulative.kilocalories.formatted("kcal")}${if (cumulative.isEstimated) "（推定を含む）" else ""}")
    if (!cumulative.isComplete) Text("算出可能日の小計：${cumulative.availableDaysSubtotalKilocalories.formatted("kcal")}（不完全・欠測${cumulative.missingDates.size}日${if (cumulative.isEstimated) "・推定を含む" else ""}）")
    Text("体重：${weight.display.label("kg")}")
    val average = weight.movingAverage
    Text("${average.period.days}日移動平均：${average.kilograms.formatted("kg")}（実測${average.recordedDays}/${average.period.days}日${if (average.hasInsufficientDays) "・日数不足" else ""}）")
    if (average.hiddenForLongGap) Text("長期間の欠測のため移動平均は表示しません。")
    Text("今日の記録は途中です。記録がある日も記録漏れがないとは限りません。欠測は線でつなぎません。移動平均は取得できた実測値のみを使用します。")
}

private data class PlotSeries(val values: List<Double?>, val estimated: List<Boolean>, val color: Color, val dotted: Boolean = false)

@Composable
private fun TrendChart(title: String, unit: String, series: List<PlotSeries>, selected: Int,
    bars: Boolean = false, includeZero: Boolean = false) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    val values = series.flatMap { it.values }.filterNotNull()
    val rawMin = values.minOrNull() ?: 0.0
    val rawMax = values.maxOrNull() ?: 1.0
    val min = if (includeZero) minOf(0.0, rawMin) else rawMin - 0.5
    val max = maxOf(if (includeZero) maxOf(0.0, rawMax) else rawMax + 0.5, min + 1.0)
    Text(if (values.isEmpty()) "データなし／未算出" else "${min.formatted(unit)} ～ ${max.formatted(unit)}",
        style = MaterialTheme.typography.labelMedium)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(110.dp).semantics {
        contentDescription = "$title。横軸は共通の日付。数値は日付選択の下に表示。"
    }) {
        val n = series.first().values.size
        fun x(index: Int) = size.width * (index + 0.5f) / n
        fun y(value: Double) = (size.height * (1 - (value - min) / (max - min))).toFloat()
        drawLine(grid, Offset(x(selected), 0f), Offset(x(selected), size.height), 2f)
        if (includeZero) drawLine(grid, Offset(0f, y(0.0)), Offset(size.width, y(0.0)))
        series.forEach { line ->
            line.values.forEachIndexed { index, value ->
                if (value != null) {
                    val point = Offset(x(index), y(value))
                    if (bars) {
                        val width = size.width / n * 0.65f
                        val top = minOf(y(0.0), point.y)
                        val height = kotlin.math.abs(y(0.0) - point.y).coerceAtLeast(1f)
                        if (line.estimated[index]) drawRect(line.color, Offset(point.x - width / 2, top), Size(width, height), style = Stroke(2f))
                        else drawRect(line.color, Offset(point.x - width / 2, top), Size(width, height))
                    } else {
                        if (index > 0) line.values[index - 1]?.let { previous ->
                            val dashed = line.dotted || line.estimated[index] || line.estimated[index - 1]
                            drawLine(line.color, Offset(x(index - 1), y(previous)), point, 2f,
                                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null)
                        }
                        if (line.estimated[index]) drawCircle(line.color, 4f, point, style = Stroke(2f))
                        else drawCircle(line.color, 3f, point)
                    }
                }
            }
        }
    }
}

private fun DisplayValue.number(): Double? = when (this) {
    is DisplayValue.Recorded -> value
    is DisplayValue.Interpolated -> value
    DisplayValue.Missing -> null
}
private fun Double?.formatted(unit: String): String = this?.let { String.format(Locale.JAPAN, "%.1f %s", it, unit) } ?: "未算出"
private fun DisplayValue.label(unit: String): String = when (this) {
    is DisplayValue.Recorded -> value.formatted(unit)
    is DisplayValue.Interpolated -> "${value.formatted(unit)}（補間：$previousRecordedDate ～ $nextRecordedDate）"
    DisplayValue.Missing -> "欠測"
}
