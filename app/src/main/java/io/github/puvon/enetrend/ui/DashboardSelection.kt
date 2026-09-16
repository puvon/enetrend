package io.github.puvon.enetrend.ui

import io.github.puvon.enetrend.health.DisplayValue
import java.util.Locale
import kotlin.math.floor

/** The start boundary belongs to the first day, not to a fabricated zero-valued record. */
internal fun chartDayAt(x: Double, width: Double, inset: Double, dayCount: Int): Int? {
    if (!x.isFinite() || !width.isFinite() || !inset.isFinite() || inset < 0 || width <= 2 * inset || dayCount <= 0) return null
    val fraction = ((x - inset) / (width - 2 * inset)).coerceIn(0.0, 1.0)
    return floor(fraction * dayCount).toInt().coerceIn(0, dayCount - 1)
}

internal enum class DetailStatus(val label: String) {
    AVAILABLE("値あり"), MISSING("欠測"), UNAVAILABLE("未算出"),
    INTERPOLATED("補間"), ESTIMATED("推定"), REFERENCE("参考累積"), INSUFFICIENT("日数不足"),
}

internal data class DashboardDetail(val text: String, val statuses: List<DetailStatus>, val notes: List<String> = emptyList())

/** Structured states are shared by the visible rows and the chart's accessibility description. */
internal fun DashboardChartDay.details(): List<DashboardDetail> = buildList {
    fun source(name: String, value: DisplayValue?) = DashboardDetail("$name：${value.label("kcal")}", listOf(value.status()))
    add(source("摂取", daily?.source?.intake))
    add(source("消費", daily?.source?.burned))
    add(DashboardDetail("日別収支：${daily?.kilocalories.formatted("kcal")}${if (daily?.isEstimated == true) "（推定）" else ""}",
        listOf(if (daily?.kilocalories == null) DetailStatus.UNAVAILABLE else if (daily.isEstimated) DetailStatus.ESTIMATED else DetailStatus.AVAILABLE)))
    val period = periodCumulative
    add(DashboardDetail("期間累積収支：${period?.kilocalories.formatted("kcal")}${if (period?.isEstimated == true) "（推定を含む）" else ""}",
        buildList {
            if (period?.kilocalories == null) add(DetailStatus.UNAVAILABLE)
            else {
                if (!period.isComplete) add(DetailStatus.REFERENCE)
                if (period.isEstimated) add(DetailStatus.ESTIMATED)
                if (isEmpty()) add(DetailStatus.AVAILABLE)
            }
        }, if (period?.kilocalories != null && !period.isComplete)
            listOf("欠測日を除いた参考累積（不完全・欠測${period.missingDates.size}日を除外）") else emptyList()))
    add(DashboardDetail("体重：${weight?.display.label("kg")}", listOf(weight?.display.status())))
    val average = weight?.movingAverage
    add(DashboardDetail(average?.let {
        "${it.period.days}日移動平均：${it.kilograms.formatted("kg")}（実測${it.recordedDays}/${it.period.days}日${if (it.hasInsufficientDays) "・日数不足" else ""}）"
    } ?: "移動平均：未算出", buildList {
        add(if (average?.kilograms == null) DetailStatus.UNAVAILABLE else DetailStatus.AVAILABLE)
        if (average?.hasInsufficientDays == true) add(DetailStatus.INSUFFICIENT)
    }, if (average?.hiddenForLongGap == true) listOf("長期間の欠測のため移動平均は表示しません。") else emptyList()))
}

internal fun DashboardChartDay.detailLines(): List<String> = listOf(date.toString()) + details().flatMap {
    listOf(it.text, it.statuses.joinToString("・") { status -> status.label }) + it.notes
}

private fun DisplayValue?.status(): DetailStatus = when (this) {
    is DisplayValue.Recorded -> DetailStatus.AVAILABLE
    is DisplayValue.Interpolated -> DetailStatus.INTERPOLATED
    DisplayValue.Missing, null -> DetailStatus.MISSING
}

private fun Double?.formatted(unit: String): String = this?.let { String.format(Locale.JAPAN, "%.1f %s", it, unit) } ?: "未算出"
private fun DisplayValue?.label(unit: String): String = when (this) {
    is DisplayValue.Recorded -> value.formatted(unit)
    is DisplayValue.Interpolated -> "${value.formatted(unit)}（補間：$previousRecordedDate ～ $nextRecordedDate）"
    DisplayValue.Missing, null -> "欠測"
}
