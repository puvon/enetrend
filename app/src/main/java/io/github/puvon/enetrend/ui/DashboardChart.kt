package io.github.puvon.enetrend.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.puvon.enetrend.health.DisplayValue
import java.util.Locale

@Composable
internal fun DashboardChart(chart: DashboardChartData, selected: Int, onSelect: (Int) -> Unit) {
    val selectDay by rememberUpdatedState(onSelect)
    val axisStyle = MaterialTheme.typography.labelSmall
    val textMeasurer = rememberTextMeasurer()
    val leftLabels = axisLabels(chart.dailyScale)
    val rightLabels = axisLabels(chart.weightScale)
    val density = LocalDensity.current
    fun axisWidth(labels: List<String>) = with(density) {
        labels.maxOf { textMeasurer.measure(it, axisStyle).size.width }.toDp() + 8.dp
    }
    val leftWidth = axisWidth(leftLabels)
    val rightWidth = axisWidth(rightLabels)
    val plotInset = with(density) { textMeasurer.measure("0", axisStyle).size.height.toDp() / 2 }
    val insetPx = with(LocalDensity.current) { plotInset.toPx() }
    val dailyColor = MaterialTheme.colorScheme.primary
    val cumulativeColor = MaterialTheme.colorScheme.tertiary
    val weightColor = MaterialTheme.colorScheme.onSurface
    val averageColor = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Text("カロリー収支と体重", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text("■ 日別カロリー収支", color = dailyColor, style = MaterialTheme.typography.labelMedium)
            Text("━ 期間累積収支", color = cumulativeColor, style = MaterialTheme.typography.labelMedium)
        }
        Column {
            Text("● 体重", color = weightColor, style = MaterialTheme.typography.labelMedium)
            Text("┄ 移動平均", color = averageColor, style = MaterialTheme.typography.labelMedium)
        }
    }
    Text("白抜き・破線：補間／推定　点線：移動平均", style = MaterialTheme.typography.labelSmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("日別 kcal", style = MaterialTheme.typography.labelSmall)
        Text(if (chart.weightScale != null) "体重 kg" else "体重データなし", style = MaterialTheme.typography.labelSmall)
    }
    Row(Modifier.fillMaxWidth().height(240.dp)) {
        ChartAxis(leftLabels, Modifier.width(leftWidth))
        Canvas(Modifier.weight(1f).fillMaxHeight().pointerInput(chart.range, chart.days.size, insetPx) {
            detectTapGestures { point ->
                chartDayAt(point.x.toDouble(), size.width.toDouble(), insetPx.toDouble(), chart.days.size)?.let(selectDay)
            }
        }.semantics {
            contentDescription = "統合グラフ。共通の日付軸に日別収支の棒、期間累積収支、体重、移動平均の線。タップで日付を選択。数値は日付選択の下に表示。"
            stateDescription = chart.days.getOrNull(selected)?.detailLines()?.joinToString("。") ?: "選択できる日付がありません。"
            customActions = buildList {
                if (selected > 0) add(CustomAccessibilityAction("前の日") { selectDay(selected - 1); true })
                if (selected < chart.days.lastIndex) add(CustomAccessibilityAction("次の日") { selectDay(selected + 1); true })
            }
        }) {
            // Inset the common plot so edge points and extreme values remain visible.
            val inset = plotInset.toPx()
            val plotWidth = (size.width - 2 * inset).coerceAtLeast(1f)
            val plotHeight = (size.height - 2 * inset).coerceAtLeast(1f)
            fun x(value: Double) = inset + (plotWidth * value).toFloat()
            fun y(value: Double) = inset + (plotHeight * value).toFloat()
            val lineWidth = 1.5.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
            val dots = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.dp.toPx()))
            clipRect(inset - 3.dp.toPx(), inset - 3.dp.toPx(), size.width - inset + 3.dp.toPx(), size.height - inset + 3.dp.toPx()) {
                for (i in 0..4) drawLine(grid, Offset(x(0.0), y(i / 4.0)), Offset(x(1.0), y(i / 4.0)))
                drawLine(dailyColor.copy(alpha = 0.6f), Offset(x(0.0), y(chart.dailyZeroY)), Offset(x(1.0), y(chart.dailyZeroY)))
                drawLine(grid, Offset(x(0.0), y(0.0)), Offset(x(0.0), y(1.0)))
                drawLine(grid, Offset(x(1.0), y(0.0)), Offset(x(1.0), y(1.0)))
                chart.days.getOrNull(selected)?.let {
                    drawLine(grid, Offset(x(it.x), y(0.0)), Offset(x(it.x), y(1.0)), lineWidth)
                }
                val barWidth = plotWidth / chart.days.size.coerceAtLeast(1) * 0.6f
                chart.days.forEach { day -> day.dailyY?.let { value ->
                    val top = minOf(y(value), y(chart.dailyZeroY))
                    val height = kotlin.math.abs(y(value) - y(chart.dailyZeroY))
                    if (height == 0f) {
                        drawLine(dailyColor, Offset(x(day.x) - barWidth / 2, top), Offset(x(day.x) + barWidth / 2, top), lineWidth)
                    } else {
                        val position = Offset(x(day.x) - barWidth / 2, top)
                        if (day.daily?.isEstimated == true) drawRect(dailyColor, position, Size(barWidth, height), style = Stroke(lineWidth))
                        else drawRect(dailyColor.copy(alpha = 0.35f), position, Size(barWidth, height))
                    }
                } }
                fun line(color: Color, value: (DashboardChartDay) -> Double?, estimated: (DashboardChartDay) -> Boolean,
                    dotted: Boolean = false, origin: Boolean = false) {
                    if (origin) {
                        val start = Offset(x(chart.periodStartX), y(chart.periodStartY))
                        val baselineEstimated = chart.baseline?.source is DisplayValue.Interpolated
                        if (baselineEstimated) drawCircle(color, 2.5.dp.toPx(), start, style = Stroke(lineWidth))
                        else drawCircle(color, 2.dp.toPx(), start)
                        chart.days.firstOrNull()?.let { first -> value(first)?.let {
                            drawLine(color, start, Offset(x(first.x), y(it)), lineWidth,
                                pathEffect = if (estimated(first)) dash else null)
                        } }
                    }
                    chart.days.forEachIndexed { index, day -> value(day)?.let { v ->
                        val point = Offset(x(day.x), y(v))
                        chart.days.getOrNull(index - 1)?.let { previous -> value(previous)?.let { pv ->
                            drawLine(color, Offset(x(previous.x), y(pv)), point, lineWidth,
                                pathEffect = if (dotted) dots else if (estimated(day) || estimated(previous)) dash else null)
                        } }
                        if (estimated(day)) drawCircle(color, 2.5.dp.toPx(), point, style = Stroke(lineWidth))
                        else drawCircle(color, 2.dp.toPx(), point)
                    } }
                }
                line(cumulativeColor, { it.periodCumulativeY },
                    { it.periodCumulative?.isEstimated == true || chart.baseline?.source is DisplayValue.Interpolated }, origin = true)
                line(weightColor, { it.weightY }, { it.weight?.display is DisplayValue.Interpolated })
                line(averageColor, { it.movingAverageY }, { it.weight?.movingAverage?.hasInsufficientDays == true }, dotted = true)
            }
        }
        ChartAxis(rightLabels, Modifier.width(rightWidth))
    }
    Row(Modifier.fillMaxWidth().padding(start = leftWidth + plotInset, end = rightWidth + plotInset), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${chart.range.startDate.monthValue}/${chart.range.startDate.dayOfMonth}", style = MaterialTheme.typography.labelSmall)
        val last = chart.range.endDateExclusive.minusDays(1)
        Text("${last.monthValue}/${last.dayOfMonth}", style = MaterialTheme.typography.labelSmall)
    }
    Text("期間開始（${chart.range.startDate}）：0.0 kcal", style = MaterialTheme.typography.labelMedium)
    chart.baseline?.let {
        Text("累積0の基準：${it.date} ${String.format(Locale.JAPAN, "%.1f", it.kilograms)} kg${if (it.source is DisplayValue.Interpolated) "（補間）" else "（実測）"}", style = MaterialTheme.typography.labelSmall)
        Text("期間累積は右軸の1 kg幅＝${String.format(Locale.JAPAN, "%.0f", chart.kilocaloriesPerKilogram)} kcal幅。体重の予測値ではありません。", style = MaterialTheme.typography.labelSmall)
    } ?: run {
        val scale = requireNotNull(chart.independentPeriodCumulativeScale)
        Text("期間累積は独立スケール（体重との連動なし）", style = MaterialTheme.typography.labelMedium)
        Text("期間累積の範囲：${String.format(Locale.JAPAN, "%.1f ～ %.1f kcal", scale.min, scale.max)}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ChartAxis(labels: List<String>, modifier: Modifier) {
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
        for (label in labels) Text(label, modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

private fun axisLabels(scale: ChartScale?): List<String> = (0..4).map { i ->
    scale?.let { String.format(Locale.JAPAN, "%.1f", it.max - (it.max - it.min) * i / 4) } ?: "—"
}
