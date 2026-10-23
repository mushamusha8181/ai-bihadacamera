# AI美肌カメラ v1.4.5

Android向けの、自然な美肌だけに絞ったシンプルなカメラです。外部AI、API通信、広告、解析SDKは使いません。

## 主な機能

- 前面 / 背面カメラ
- 4:3 / 16:9 / 1:1
- 美肌 ON / OFF、強度0〜30、3プリセット
- 複数人では顔の写りの大きさと明るさに応じて人物ごとにごく弱く自動調整
- 3 / 5 / 10秒タイマー
- 音量ボタン撮影
- 3分割グリッド
- インカメラを見たまま保存
- 補正前JPEGの任意保存
- 直近写真を開く
- AIトークン / 料金の今月・累計表示とリセット

## v1.4.5 トークン / 料金表示

設定画面の「AI・通信コスト」で以下を表示します。

- 今月のトークン数
- 今月の推定料金
- 累計トークン数
- 累計推定料金
- 最終リセット日時
- 「トークン累計をリセット」ボタン

リセットは確認ダイアログ付きで、実行すると今月・累計のトークン数と推定料金を0へ戻します。

利用量は端末内SharedPreferencesだけで管理します。月替わり判定は利用量を読み出した時だけ行い、AlarmManager、Service、定期ポーリング、通信監視は追加していません。

現在のバージョンは外部AIを一切呼ばないため、実際の値は0 tokens / 0円のままです。過去のDeepSeek利用量は旧版で記録していなかったため、後から推計・復元はしません。

将来トークン課金型機能を追加する場合に備え、`TokenUsageStats.recordUsage()`でプロバイダーが返した利用量を明示的に記録できる構造だけ用意しています。現在は呼び出し元がありません。

## 低FPS対策

CPU Bitmap方式だったリアルタイム美肌プレビューは完全廃止しています。美肌処理と顔検出はシャッター後の静止画にだけ実行します。

通常プレビューはCameraXの`PreviewView.ImplementationMode.PERFORMANCE`を使い、可能な端末ではSurfaceViewの高速経路を利用します。

## 現在残している主要機能

- 前面 / 背面切替
- 4:3 / 16:9 / 1:1
- 美肌ON/OFF
- 強度0〜30
- ナチュラル / やや美肌 / しっかり美肌
- 複数人の人物別弱調整
- 3 / 5 / 10秒タイマー
- 音量ボタン撮影
- 3分割グリッド
- インカメラ見たまま保存
- 補正前JPEG任意保存
- 直近写真表示
- 標準撮影音
- 初回カメラ権限案内

意図的に削除したまま:

- DeepSeek / APIキー / AI相談
- CPU Bitmap方式のリアルタイム美肌プレビュー
- 位置推測だけだった目の下補正
- 撮影後に単純増光するだけだった簡易暗所補正
- シャッター音OFF設定

## 旧DeepSeek / APIキーの扱い

現在のアプリにはDeepSeekクライアント、APIキー入力画面、APIキー読込・復号、HTTP通信コードはありません。

v1.4.2以降では旧版の`secure_settings / deepseek_api_key`とAndroid Keystore alias`natural_skin_camera_deepseek_key`を、上書き更新後に一度だけ削除します。ソースに残るDeepSeek関連文字列は旧秘密情報を削除するための識別子だけです。

Manifestでは`INTERNET`と`ACCESS_NETWORK_STATE`を`tools:node="remove"`で明示除外します。

## 美肌処理

ML Kit Face Detectionのモデル同梱版で顔領域を端末内検出し、その領域内の肌らしい画素だけを弱く処理します。

- 輪郭変更なし
- 目の拡大なし
- 強いぼかしなし
- 赤み・テカリはごく弱く内部調整
- 顔未検出なら加工せずCameraX JPEGを保存
- 複数人では小さい顔を弱め、顔領域の明るさによる差も約±5%以内
- 処理用Bitmapは最大辺2048px
- 美肌OFFではCameraX JPEGを再圧縮しない

## 権限

実行時に要求するのは`CAMERA`だけです。初回案内の最後で要求します。

要求しないもの:

- 位置情報
- マイク
- ストレージ
- 写真ライブラリ
- INTERNET
- ACCESS_NETWORK_STATE

## 小型スマホ

`Configuration.screenWidthDp / screenHeightDp`を基準にUI密度を切り替えます。320〜360dp幅程度の小型スマホや分割画面でも、主要タップ領域は原則48dp以上を維持します。

## 保存先

- 通常 / 美肌写真: `Pictures/AI美肌カメラ/`
- 補正前JPEG: `Pictures/AI美肌カメラ/Original/`

## ビルド

- minSdk 29
- targetSdk / compileSdk 35
- Java 17
- Android Gradle Plugin 8.7.3
- CameraX 1.6.2
- ExifInterface 1.4.2
- ML Kit Face Detection 16.1.7（モデル同梱版）

GitHub ActionsではGradle 8.10.2、Android SDK 35を用意し、`lintDebug`と`assembleDebug`を実行する設定です。
