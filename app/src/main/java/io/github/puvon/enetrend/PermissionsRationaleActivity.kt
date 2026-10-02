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
                        Text("栄養は摂取カロリー、総消費カロリーは収支の計算、体重は推移と移動平均の表示に使用するため、読み取り権限を要求します。許可後、Health Connect から必要なデータを読み取り、端末内で集計・表示します。取得した健康データをアプリのファイルやデータベースには保存しません。")
                        Text("Health Connect への書き込みは行いません。健康データを外部へ送信したり、ログへ出力したりしません。")
                        Text("体組成による消費の補正には、追加で活動消費・体脂肪率・除脂肪体重の読み取り権限を任意で要求します。Fitbit由来の体組成を直近14日で平滑化して安静時代謝を推定し、活動消費と合わせて昨日以前の消費・収支・今日の予測に使います。追加権限がなくても、従来の表示を利用できます。")
                        Text("権限の許可は任意です。Health Connect の設定から、いつでも許可を変更・取り消しできます。基本の3権限がない場合は接続済みとして扱いません。補正用権限の自動要求は一度だけ行い、要求済みフラグを端末内に保存します。拒否後も画面のボタンから再要求できます。")
                        TextButton(onClick = { finish() }) { Text("閉じる") }
                    }
                }
            }
        }
    }
}
