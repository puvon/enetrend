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

/** Shared by visible details and the chart's accessibility description. */
internal fun DashboardChartDay.detailLines(): List<String> = buildList {
    add(date.toString())
    add("摂取：${daily?.source?.intake.label("kcal")}／消費：${daily?.source?.burned.label("kcal")}")
    add("日別収支：${daily?.kilocalories.formatted("kcal")}${if (daily?.isEstimated == true) "（推定）" else ""}")
    add("期間累積収支：${periodCumulative?.kilocalories.formatted("kcal")}${if (periodCumulative?.isEstimated == true) "（推定を含む）" else ""}")
    periodCumulative?.takeUnless { it.isComplete }?.let {
        add("算出可能日の小計：${it.availableDaysSubtotalKilocalories.formatted("kcal")}（不完全・欠測${it.missingDates.size}日${if (it.isEstimated) "・推定を含む" else ""}）")
    }
    add("体重：${weight?.display.label("kg")}")
    weight?.movingAverage?.let {
        add("${it.period.days}日移動平均：${it.kilograms.formatted("kg")}（実測${it.recordedDays}/${it.period.days}日${if (it.hasInsufficientDays) "・日数不足" else ""}）")
        if (it.hiddenForLongGap) add("長期間の欠測のため移動平均は表示しません。")
    } ?: add("移動平均：未算出")
}

private fun Double?.formatted(unit: String): String = this?.let { String.format(Locale.JAPAN, "%.1f %s", it, unit) } ?: "未算出"
private fun DisplayValue?.label(unit: String): String = when (this) {
    is DisplayValue.Recorded -> value.formatted(unit)
    is DisplayValue.Interpolated -> "${value.formatted(unit)}（補間：$previousRecordedDate ～ $nextRecordedDate）"
    DisplayValue.Missing, null -> "欠測"
}
