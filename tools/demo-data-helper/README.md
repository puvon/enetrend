# EneTrend デモデータ投入ツール

**標準 Android Emulator（AVD）専用です。実機では絶対に使用しないでください。**

EneTrend 本体とは別の Android アプリ `io.github.puvon.enetrend.demodata`（表示名 `EneTrend Demo Data`）で、Health Connect に架空データを追加します。本体の読み取り専用権限・ビルド構成は変更しません。

ソースと実行スクリプトはこの `tools/` 配下に保存します。本体の `clean` では削除されません。APK・キャッシュ・SDK パスは `.gitignore` で除外します。Git への commit は通常のプロジェクト運用で行ってください。

## 必要な環境

- Windows PowerShell 5.1 以上、Android SDK（platform-tools、API 37 のビルド環境）
- JDK 17 以上（本体で利用中の JDK 25 を使用可能）
- 起動済みの標準 AVD、API 34 以上、Health Connect が利用できること
- 初回ビルド時は依存関係の取得にネットワークが必要な場合があります。

JDK は `JAVA_HOME`、Android Studio の JBR、Gradle の JDK キャッシュから検索します。SDK は `ANDROID_HOME`、`ANDROID_SDK_ROOT`、通常の `%LOCALAPPDATA%\Android\Sdk` から検索します。見つからなければ `-JavaHome` と `-SdkPath` を指定してください。端末は自動選択しません。

## 実行（リポジトリルートから）

まず Android Studio で AVD を起動し、Device Manager または `adb devices` で `emulator-5554` などのシリアルを確認してください。

```powershell
# ビルド・単体テスト・lint のみ。端末へ接続しません。
.\tools\demo-data-helper\Run-DemoData.ps1 -BuildOnly

# ツールをインストールし、既存の件数・提供元を確認（Health Connect に追加しません）。
.\tools\demo-data-helper\Run-DemoData.ps1 -Serial emulator-5554 -Mode Inspect

# エミュレータの今日を含む30日分を追加。
.\tools\demo-data-helper\Run-DemoData.ps1 -Serial emulator-5554 -Mode Seed

# 同じ期間を繰り返す場合は終端日を固定（未来日は不可）。
.\tools\demo-data-helper\Run-DemoData.ps1 -Serial emulator-5554 -Mode Seed -EndDate 2026-09-14
```

毎回ビルド・テスト・lint 後にエミュレータを再確認し、ツールを上書きインストールします。Inspect は3種類の読み取り権限、Seed はさらに4種類の書き込み権限をツールに付与します。EneTrend 本体の権限には触れません。データ投入完了は `OK SEEDED`、件数確認完了は `OK INSPECT` で表示されます。失敗・タイムアウトを成功扱いにしません。失敗時に一部処理が完了している可能性はあるため、再実行前に Inspect で確認できます。

投入後、EneTrend を開いて「再確認」を押し、表示期間を30日にしてください。必要なら Health Connect の設定で **EneTrend 本体**の3つの読み取り権限を許可します。

## データと再実行

- 摂取29件、消費28件、体重27件、補助の基礎代謝1件：通常は計85件。
- 体重は74 kg台から緩やかに減り、日々の揺れと欠測を含みます。摂取・消費にも増減と欠測があります。
- 端末のローカル日付を使用します。今日の7時より前は今日の体重を入れず、日付切り替え直後も未来の記録や長さゼロの区間を作りません。この場合は件数が減ります。
- 日付・種類ごとの `clientRecordId` と version 1 を維持します。同じ期間の再実行では重複せず、既に存在する同一 ID のデモ値を上書きしません。以前の一時ツールと同じ package / ID を使用します。
- 終端日を変えると未登録の日・種類が追加されます。以前のデモレコードは削除しないため、総件数が85件を超えたり、以前の欠測日が埋まったりする場合があります。
- 他アプリの記録の削除・上書きは行いません。既存データのある AVD では集計に混ざり得るため、デモ用 AVD を使ってください。
- 基礎代謝1,600 kcal/日の架空データも同時に登録します。API 34 で、これがない場合に未記録時間の消費集計が極端な値になる事象を確認したためです。Health Connect 自身の推定値は、EneTrend 側の補間とは別です。
- 削除が必要な場合は、**エミュレータの** Health Connect 設定から提供元 `EneTrend Demo Data` のデータだけを対象にしてください。このツールに自動削除機能はありません。

## 実機を拒否する仕組み

スクリプトは `emulator-数字` の明示指定、接続状態、QEMU プロパティ、hardware・model・fingerprint の許可リスト、API レベル、AVD コンソール応答を確認します。USB 実機・IP アドレス指定・不明な端末・取得失敗は停止します。インストール・権限付与・起動などの各変更操作前にも再確認し、すべての ADB 操作に `-s` を付けます。実機用の例外や強制実行オプションはありません。

投入アプリ自身も debuggable、QEMU プロパティ、hardware・model・fingerprint を検査します。Health Connect クライアント生成前、および書き込み直前に判定し、不明・実機ならアクセスしません。APK を直接起動してもこの検査を通る必要があります。対応外のエミュレータで検査を迂回しないでください。

## 安全性の検証

```powershell
# ADB をモック化した14シナリオ。実機にもエミュレータにも接続しません。
.\tools\demo-data-helper\Test-EmulatorSafety.ps1

# アプリ内ガードの JVM テスト8件、lint、APK ビルド。
.\tools\demo-data-helper\Run-DemoData.ps1 -BuildOnly
```

実機拒否の検証は模擬した端末情報で行います。検証目的でも実機へインストール・実行しないでください。
