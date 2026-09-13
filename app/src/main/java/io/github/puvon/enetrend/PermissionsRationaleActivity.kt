package io.github.puvon.enetrend

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.puvon.enetrend.ui.theme.EnetrendTheme

class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EnetrendTheme {
                Scaffold { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding)
                            .verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("データの利用とプライバシー", style = MaterialTheme.typography.headlineSmall)
                        Text("EneTrend はカロリー収支と体重変化を分析・可視化するためのアプリです。")
                        Text("栄養は摂取カロリー、総消費カロリーは収支の計算、体重は推移と移動平均の表示に使用するため、読み取り権限を要求します。現在のバージョンは接続と権限の確認までを行い、健康データの取得はまだ行いません。")
                        Text("Health Connect への書き込みは行いません。健康データを外部へ送信したり、ログへ出力したりしません。")
                        Text("権限の許可は任意です。Health Connect の設定から、いつでも許可を変更・取り消しできます。必要な権限がない場合は接続済みとして扱いません。")
                        TextButton(onClick = { finish() }) { Text("閉じる") }
                    }
                }
            }
        }
    }
}
