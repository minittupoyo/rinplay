# プロジェクト概要
個人用の高音質音楽プレイヤー(Android)。ローカルファイル + DLNAクライアント対応。

## 技術スタック
- Kotlin / Jetpack Compose / Media3 1.11.1
- AGP 9.4.1(Kotlin 内蔵。`kotlin.android` プラグインは不要。Compose は `kotlin.plugin.compose`)
- minSdk 26 / compileSdk・targetSdk 37
- DLNA: jUPnP 3.0.5(`org.jupnp` / `.android` / `.support`)。クライアント専用(検出・Browse・再生)

## 実機
- 接続: `adb -s <シリアル>`(複数端末がある場合は必ず -s を付ける)
- 端末: Pixel 6a(Android 17 / API 37)、USB DAC: Shanling UA3
- 無線デバッグ: USB 接続中に `adb tcpip 5555` → `adb connect <端末IP>:5555`。端末の再起動や IP 変更で切れる(IP は DHCP)
- パッケージ名: `com.example.myplayer`(変更したらここも更新)
- USB接続中は `adb shell svc power stayon usb` で画面点灯を維持
- テスト音源: `/sdcard/Music/` に FLAC 4 つ(44.1k/16, 48k/16, 88.2k/24, 96k/24)。ffmpeg で生成した正弦波

## よく使うコマンド
- ビルド+インストール: `./gradlew installDebug`
- 起動: `adb shell am start -n com.example.myplayer/.MainActivity`
- ログ: `adb logcat --pid=$(adb shell pidof com.example.myplayer)`
- スクショ: `adb exec-out screencap -p > "$TEMP/screen.png"`(Git Bash。`/tmp` は使わない)
- 音声出力の確認: `adb shell dumpsys media.audio_flinger` / `adb shell dumpsys audio`
- メディアセッション確認: `adb shell dumpsys media_session`
- ネットワークポリシー確認: `adb shell dumpsys netpolicy`(UID は `pm list packages -U` で調べる)
- jUPnP のログを出す: `adb shell setprop log.tag D`。確認後に `adb shell 'setprop log.tag ""'` で戻す

## 開発ルール
- 変更後は「ビルド → インストール → 起動 → logcat → スクショ」で動作確認する
- 実機で確認していない場合は、その旨を明記する(音が実際に出ているかは、ログ・dumpsys では見えない)
- 破壊的なadb操作(pm clear / uninstall / rm 等)は事前に確認する
- 高音質関連の変更では、実際の出力サンプリングレートを dumpsys で確認する
- 画面タップ座標は、上部の出力表示の行数(1行/3行)でリスト位置がずれる。タップ前にスクショで確認する
- Windows の Git Bash 注意: `adb shell` に `/sdcard/...` を渡すときは `MSYS_NO_PATHCONV=1`。`javap -cp` の区切りは `;`。`adb shell setprop X ""` は空文字がシェルに消されるので全体を `'...'` で囲む

## 実装上の注意

### 出力表示とビットパーフェクト
- 画面の「出力」表示は、AudioTrack に渡した形式(`onAudioTrackInitialized`)。端末の実出力ではない。実出力は `dumpsys media.audio_flinger` で、アプリ(uid)のトラックが乗るスレッドを見る
- `setEnableAudioFloatOutput(true)` を維持する。false にすると 24bit FLAC が 16bit 出力になる
- AudioTrack の解放イベントは、新トラック作成後に届くことがある。出力表示は「生きているトラック数」で消す(構成の比較はしない)
- ビットパーフェクトは Android 14+ の `setPreferredMixerAttributes`。要点:
  - `MODIFY_AUDIO_SETTINGS` の宣言が必要(なしだと `Permission Denial`)
  - `getSupportedMixerAttributes` は behavior=default で返る。`MIXER_BEHAVIOR_BIT_PERFECT` を指定して組み直して渡す
  - 設定は AudioTrack 作成前に行う(`onAudioInputFormatChanged` で呼んでいる)
  - Pixel 6a + Shanling UA3 では `no compatible profile found`(Error -2)で失敗。USB 出力プロファイル(`hifi_playback`)が `AUDIO_OUTPUT_FLAG_NONE` で、ビットパーフェクトの経路が見当たらない。別の端末・DAC では未検証
- この端末の USB 出力ミキサーはレート固定で、曲に追従しない。48kHz と 96kHz のスレッドのみ。48k/96k はレート一致、44.1k は 48k に、88.2k は 96k にリサンプルされる(実測)

### DLNA(jUPnP)
- 検出には `WifiManager.MulticastLock` が必要(`DlnaRepository.start()` で取得)
- 標準の `AndroidUpnpServiceConfiguration` は Jetty に依存し、Android 上で `NoClassDefFoundError: org/eclipse/jetty/server/Server` で落ちる。`MyUpnpService`/`MyUpnpConfiguration`(`dlna/`)で、HTTP クライアントを `HttpURLConnection` 版(`HttpUrlStreamClient`)に差し替え、サーバー(`createStreamServer`)は `null`。このため SUBSCRIBE(イベント購読)は未対応
- slf4j は 2.x 系の `uk.uuid.slf4j:slf4j-android` を使う。`org.slf4j:slf4j-android` 1.7.x だと NOP になり、jUPnP のログが全部消える
- Android 17 の `android.permission.ACCESS_LOCAL_NETWORK`(NEARBY_DEVICES グループ、実行時権限)が必要。許可した直後は OS がプロセスを終了することがある(再起動で復帰)
- 通信が止まる場合は `dumpsys netpolicy` を確認する。`UID=<アプリ> policy=262144 (REJECT_ALL)` と `Restricted networking mode: true` のとき、マルチキャスト送信が `sendto failed: EPERM` で全部失敗する。アプリ側では直らない。端末の設定側で通信制限を外す(今回は解除済み)
- targetSdk を下げても EPERM は変わらなかった(ターゲット SDK は原因ではない)
- PC から SSDP を確認するときは、VPN や複数 NIC の影響を避けるため、端末と同じサブネットの NIC に bind して送る(bind しないと VPN 側へ出て「見えない」と誤判定する)
- Browse は 1 回で先頭 500 件まで。ページ送りは未実装

### その他
- 依存バージョンは libs.versions.toml で一元管理

## 未確認・未実装
- 音が実際に出ているかの聴感確認、ロック画面からの操作、通知のシークバー操作
- DLNA: `rec` サーバーの閲覧、500 件超のフォルダ、サーバー/フォルダの記憶
- 44.1kHz 系をそのまま DAC に渡す経路(OS のミキサーを通らない直接出力は未着手)
