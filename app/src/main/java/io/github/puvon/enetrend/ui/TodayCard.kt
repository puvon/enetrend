package io.github.puvon.enetrend.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.puvon.enetrend.health.ConsumptionBasis
import io.github.puvon.enetrend.health.TodayStatus
import java.time.format.DateTimeFormatter

@Composable
internal fun TodayCard(status: TodayStatus) {
    val summary = status.calorieSummary
    var expanded by remember(status) { mutableStateOf(false) }
    val allowance = "摂取可能量：${summary.intakeAllowanceKilocalories.formatted("kcal", "算出不可")}"
    val quality = buildList {
        if (summary.hasMissingRecords) add("欠測を含む参考値")
        if (summary.accessRestrictedDays > 0) add("履歴アクセス制限${summary.accessRestrictedDays}/7日")
    }.joinToString("・")
    val basis = when (summary.consumptionBasis) {
        ConsumptionBasis.RECORDED -> if (summary.predictedBurnedKilocalories == null)
            "予測消費なし・取得済み消費のみで計算"
        else "取得済み消費が予測を上回るため、取得済み消費で計算"
        ConsumptionBasis.PREDICTED -> if (status.calories.burnedKilocalories == null)
            "当日消費未取得・予測消費で計算" else "予測消費で計算"
        null -> "消費の記録がないため算出できません。"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("今日の状況（${status.date}）", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(allowance, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)
                    .semantics { stateDescription = "一日合計の参考値。$quality" })
                IconButton(onClick = { expanded = true }, modifier = Modifier.semantics {
                    contentDescription = "摂取可能量の参考情報"
                }) { DetailStatusIcon(DetailStatus.ESTIMATED) }
            }
            Text("一日合計の参考値（残量ではありません）", style = MaterialTheme.typography.bodySmall)
            if (quality.isNotEmpty()) Text(quality, style = MaterialTheme.typography.bodySmall)
            Text(basis, style = MaterialTheme.typography.bodySmall)
            Text("取得済み消費：${status.calories.burnedKilocalories.formatted("kcal", "未取得")}")
            Text("予測消費：${summary.predictedBurnedKilocalories.formatted("kcal", "算出不可")}")
            Text("取得済み摂取：${status.calories.intakeKilocalories.formatted("kcal", "未取得")}")
            Text("過去7日収支：${summary.previousBalanceKilocalories.formatted("kcal", "算出不可")}")
            if (summary.previousBalanceKilocalories == null) Text("過去収支の補正なし", style = MaterialTheme.typography.bodySmall)
            Text("${status.date.minusDays(7)} ～ ${status.date.minusDays(1)}", style = MaterialTheme.typography.bodySmall)
            Text("消費記録${summary.burnedRecordedDays}/7日・収支算出${summary.balanceRecordedDays}/7日", style = MaterialTheme.typography.bodySmall)
            Text("取得開始：${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(status.readStartedAt.atZone(status.previousSevenDays.range.zoneId))}",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (expanded) {
        AlertDialog(onDismissRequest = { expanded = false },
            title = { Text("摂取可能量の参考情報") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(allowance)
                    Text("摂取可能量 = 計算に使う消費 − 過去7日収支")
                    Text("計算に使う消費：${summary.basisBurnedKilocalories.formatted("kcal", "算出不可")}")
                    Text("予測消費は昨日まで7暦日の取得済み消費の平均です。取得済みの当日消費が予測を上回れば、その値を使います。片方だけある場合は取得できた側を使います。")
                    Text("過去7日収支は摂取と消費の両方がある日の「摂取 − 消費」の合計です。全額を反映し、過去7日と今日の計8日分を相殺する計算です。今日の摂取済み量は差し引いていません。")
                    Text("欠測・補間値は平均や収支に含めません。欠測日は0に置き換えず、収支を算出できる日がなければ補正しません。7日窓が動くと摂取可能量も変わります。")
                    if (quality.isNotEmpty()) {
                        Text(quality)
                        Text("取得できた日だけの参考値です。不足する記録により、摂取可能量は多くも少なくもなり得ます。")
                    }
                    if (summary.intakeAllowanceKilocalories?.let { it < 0.0 } == true)
                        Text("負の値は、今日の摂取を0として計算しても過去の超過が残ることを表します。")
                    Text("当日の値は日別集計で取得できた途中の値です。取得開始時刻は記録元の同期完了や現在までの記録の網羅性を保証しません。")
                    Text("計算上の参考値であり、栄養上の適量や安全な上限を保証するものではありません。")
                }
            },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text("閉じる") } })
    }
}
