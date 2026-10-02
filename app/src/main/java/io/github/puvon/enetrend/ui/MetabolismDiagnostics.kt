package io.github.puvon.enetrend.ui

import io.github.puvon.enetrend.health.BodyMeasurementKind
import io.github.puvon.enetrend.health.MetabolismEstimate
import io.github.puvon.enetrend.health.MetabolismFallback
import io.github.puvon.enetrend.health.OptionalReadState

internal fun MetabolismEstimate.unavailableReason(): String = when {
    totalKilocalories != null -> "補正を適用しています"
    fallback == MetabolismFallback.TODAY -> "当日は補正対象外"
    else -> buildList {
        if (restingKilocaloriesPerDay == null) {
            val blocked = bodyReadStates.filterValues { states -> states.any { it != OptionalReadState.AVAILABLE } }
            if (blocked.isNotEmpty()) blocked.forEach { (kind, states) ->
                add("${kind.label()}：${states.filter { it != OptionalReadState.AVAILABLE }.joinToString("・") { it.label() }}")
            }
            if (OptionalReadState.ERROR in bodyIssues && blocked.isEmpty()) add("体組成の取得失敗")
            if (OptionalReadState.ACCESS_DENIED in bodyIssues && blocked.isEmpty()) add("体組成のアクセス制限")
            if (recordedDays < 3) add("体組成の有効日数$recordedDays/3日")
            else if (fallback == MetabolismFallback.STALE_MEASUREMENT) add("最新測定から7日以上経過")
            else if (fallback == MetabolismFallback.INVALID_VALUE) add("体組成の値が無効")
        }
        if (activeKilocalories == null) add("Fitbit活動消費：${if (activeState == OptionalReadState.AVAILABLE) "有効な記録なし" else activeState.label()}")
        if (isEmpty()) add("補正値を算出できません")
    }.joinToString("／")
}

internal fun MetabolismEstimate.readDiagnostics(): List<String> = buildList {
    add("取得元はFitbitに限定しています。Fitbit以外が保存した記録は補正に使いません。")
    for (kind in BodyMeasurementKind.entries) {
        val states = bodyReadStates[kind]
        val state = states?.joinToString("・") { it.label() } ?: "取得状態の情報なし"
        add("${kind.label()}：$state、直近14日の有効記録${bodyRecordedDays[kind] ?: 0}日")
    }
    add("Fitbit活動消費：${activeState.label()}、選択日の有効記録${if (activeKilocalories == null) "なし" else "あり"}")
    if (bodyReadStates.values.any { OptionalReadState.PERMISSION_REQUIRED in it } || activeState == OptionalReadState.PERMISSION_REQUIRED)
        add("画面上部の「体組成による補正の読み取り権限（任意）」から、活動消費と、除脂肪体重または体脂肪率を許可してください。体重は基本の読み取り権限を使います。")
    if (recordedDays == 0 && (bodyRecordedDays[BodyMeasurementKind.WEIGHT_KG] ?: 0) > 0 &&
        (bodyRecordedDays[BodyMeasurementKind.FAT_PERCENT] ?: 0) > 0)
        add("体重と体脂肪率の記録日は一致していません。同じ日の組が必要です。")
    add("許可済みでも記録がない場合は、Health Connectの「データとアクセス」で種類ごとの記録と取得元を確認してください。Fitbitで表示できてもHealth Connectに保存されているとは限りません。")
    add("必要な記録がなければ従来総消費へ戻ります。活動消費を0と仮定したり、総消費にRMRを足したりはしません。")
}

private fun BodyMeasurementKind.label(): String = when (this) {
    BodyMeasurementKind.WEIGHT_KG -> "Fitbit体重"
    BodyMeasurementKind.FAT_PERCENT -> "Fitbit体脂肪率"
    BodyMeasurementKind.LEAN_KG -> "Fitbit除脂肪体重"
}

private fun OptionalReadState.label(): String = when (this) {
    OptionalReadState.AVAILABLE -> "読み取り成功"
    OptionalReadState.PERMISSION_REQUIRED -> "権限未許可"
    OptionalReadState.ACCESS_DENIED -> "アクセス制限"
    OptionalReadState.ERROR -> "取得失敗"
}
