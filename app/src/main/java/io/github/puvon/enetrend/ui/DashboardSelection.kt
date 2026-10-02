package io.github.puvon.enetrend.ui

import io.github.puvon.enetrend.health.DisplayValue
import io.github.puvon.enetrend.health.HealthDataRange
import io.github.puvon.enetrend.health.MetabolismFallback
import io.github.puvon.enetrend.health.OptionalReadState
import java.time.LocalDate
import java.util.Locale
import kotlin.math.floor

internal fun selectedChartDate(savedDate: String?, range: HealthDataRange): LocalDate {
    val saved = savedDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return saved?.takeIf { it >= range.startDate && it < range.endDateExclusive }
        ?: range.endDateExclusive.minusDays(1)
}

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
    daily?.source?.recorded?.metabolism?.let { estimate ->
        val notes = buildList {
            addAll(estimate.readDiagnostics())
            add("体組成はFitbit由来の同日代表値から直近14暦日で平滑化（有効${estimate.recordedDays}/14日）。最新測定：${estimate.latestMeasurementDate ?: "なし"}。")
            add("推定RMR = 370 + 21.6 × 平滑化した除脂肪体重kg。筋肉量の実測や筋トレの因果効果ではありません。")
            estimate.fallback?.let { add(when (it) {
                MetabolismFallback.TODAY -> "当日は補正せず、途中の従来取得値を使用します。"
                MetabolismFallback.INSUFFICIENT_DAYS -> "有効な体組成が3日未満のため従来値を使用します。"
                MetabolismFallback.STALE_MEASUREMENT -> "最新測定から7日以上経過したため従来値を使用します。"
                MetabolismFallback.ACTIVE_MISSING -> "Fitbit活動消費が未取得のため従来値を使用します。"
                MetabolismFallback.INVALID_VALUE -> "補正用の値が有効でないため従来値を使用します。"
            }) }
            for (issue in estimate.bodyIssues + estimate.activeState + estimate.fitbitTotalState) when (issue) {
                OptionalReadState.PERMISSION_REQUIRED -> add("補正用データの一部に読み取り権限がありません。")
                OptionalReadState.ACCESS_DENIED -> add("補正用データの一部に履歴等のアクセス制限があります。")
                OptionalReadState.ERROR -> add("補正用データの一部を取得できませんでした。再確認してください。")
                OptionalReadState.AVAILABLE -> Unit
            }
        }
        val correctionText = estimate.totalKilocalories.formatted("kcal") +
            if (estimate.totalKilocalories == null) " — ${estimate.unavailableReason()}" else ""
        add(DashboardDetail("補正後の推定総消費カロリー：$correctionText",
            buildList {
                add(if (estimate.totalKilocalories != null) DetailStatus.ESTIMATED else DetailStatus.UNAVAILABLE)
                if (estimate.recordedDays in 1..13) add(DetailStatus.INSUFFICIENT)
            }, notes))
        add(DashboardDetail("従来の総消費：${daily.source.recorded.burnedKilocalories.formatted("kcal", "欠測")}",
            listOf(if (daily.source.recorded.burnedKilocalories != null) DetailStatus.AVAILABLE else DetailStatus.MISSING)))
        add(DashboardDetail("Fitbit総消費：${estimate.fitbitTotalKilocalories.formatted("kcal", "未取得")}",
            listOf(if (estimate.fitbitTotalKilocalories != null) DetailStatus.AVAILABLE else DetailStatus.MISSING)))
        add(DashboardDetail("推定安静時代謝：${estimate.restingKilocaloriesPerDay.formatted("kcal/day")}",
            listOf(if (estimate.restingKilocaloriesPerDay != null) DetailStatus.ESTIMATED else DetailStatus.UNAVAILABLE)))
        add(DashboardDetail("Fitbit活動消費：${estimate.activeKilocalories.formatted("kcal", "未取得")}",
            listOf(if (estimate.activeKilocalories != null) DetailStatus.AVAILABLE else DetailStatus.MISSING)))
        add(DashboardDetail("基準日比の推定安静時代謝増減：${restingChangeKilocaloriesPerDay.formatted("kcal/day")}",
            listOf(if (restingChangeKilocaloriesPerDay != null) DetailStatus.ESTIMATED else DetailStatus.UNAVAILABLE),
            listOf("基準日：${restingBaselineDate ?: "なし"}。表示期間を変えると基準日も変わります。")))
        val difference = estimate.totalKilocalories?.let { corrected -> estimate.fitbitTotalKilocalories?.let { corrected - it } }
        add(DashboardDetail("Fitbit総消費との差：${difference.formatted("kcal")}",
            listOf(if (difference != null) DetailStatus.ESTIMATED else DetailStatus.UNAVAILABLE)))
    }
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
    is DisplayValue.Estimated -> DetailStatus.ESTIMATED
    is DisplayValue.Recorded -> DetailStatus.AVAILABLE
    is DisplayValue.Interpolated -> DetailStatus.INTERPOLATED
    DisplayValue.Missing, null -> DetailStatus.MISSING
}

internal fun Double?.formatted(unit: String, missing: String = "未算出"): String =
    this?.let { String.format(Locale.JAPAN, "%.1f %s", it, unit) } ?: missing
private fun DisplayValue?.label(unit: String): String = when (this) {
    is DisplayValue.Estimated -> "${value.formatted(unit)}（体組成による推定）"
    is DisplayValue.Recorded -> value.formatted(unit)
    is DisplayValue.Interpolated -> "${value.formatted(unit)}（補間：$previousRecordedDate ～ $nextRecordedDate）"
    DisplayValue.Missing, null -> "欠測"
}
