# プロジェクト概要
個人用の高音質音楽プレイヤー RinPlay(Android)。ローカルファイル + DLNA クライアント + USB DAC への直接出力(ビットパーフェクト)。

## 技術スタック
- Kotlin / Jetpack Compose / Media3 1.11.1
- AGP 9.4.1(Kotlin 内蔵。`kotlin.android` プラグインは不要。Compose は `kotlin.plugin.compose`)
- minSdk 26 / compileSdk・targetSdk 37
- DLNA: jUPnP 3.0.5(`org.jupnp` / `.android` / `.support`)。クライアント専用(検出・Browse・再生)
- 画像: Coil 3(`coil-compose` + `coil-network-okhttp`)
- ネイティブ: NDK 28.2 / CMake 3.22.1(USB 直結の ISO 転送。abi は arm64-v8a のみ)
- 依存バージョンは `libs.versions.toml` で一元管理

## 実機・環境
- 接続: `adb -s <シリアル>`(複数端末がある場合は必ず -s を付ける)
- 端末: Pixel 6a(Android 17 / API 37)、Galaxy S26(SM-S942Q / SM8850 / Android 17)
- USB DAC: Shanling UA3、Fosi Audio ZH3
- アプリ名: RinPlay / パッケージ名(applicationId・namespace): `net.minittu.rinplay`(変更したらここも更新)。旧名 `com.example.myplayer` は廃止(別アプリ扱いで、設定・権限は引き継がれない)
- 無線デバッグ: USB 接続中に `adb tcpip 5555` → `adb connect <端末IP>:5555`。端末の再起動や IP 変更で切れる(IP は DHCP)。**DAC を挿すと USB ケーブルは PC に挿せない**ので、先に無線デバッグにしてから DAC に付け替える
- USB 接続中は `adb shell svc power stayon usb` で画面点灯を維持
- テスト音源(ffmpeg で生成):
  - `/sdcard/Music/`: FLAC 4 つ(44.1k/16, 48k/16, 88.2k/24, 96k/24)の正弦波。タグなし(アルバムはフォルダ名の `Music`、アーティストは `Generated`)
  - `/sdcard/Music/TestFormats/`(形式表示の確認用): アルバム `Formats` / Fmt Test の 4 曲(`MP3 320k`、`MP3 VBR V2`、`AAC 256k`、`FLAC 24bit 48k`)と、タグなしの WAV 24bit 1 つ(`fmt_wav_24`。タグなしだと MediaStore に音楽として載らず、アルバムは `TestFormats`、アーティストは不明になる)
  - `/sdcard/Music/TestAlbums/`(ライブラリ表示の確認用): ジャケット埋め込み・タグ付きの FLAC 5 つ(`Blue Album` / Artist A が 3 曲、`Red Album` / Artist B が 2 曲。`-disposition:v attached_pic` で画像を埋めた)

## よく使うコマンド
- ビルド+インストール: `./gradlew installDebug`
- 起動: `adb shell am start -n net.minittu.rinplay/.MainActivity`
- ログ: `adb logcat --pid=$(adb shell pidof net.minittu.rinplay)`
- スクショ: `adb exec-out screencap -p > "$TEMP/screen.png"`(Git Bash。`/tmp` は使わない)
- 音声出力: `adb shell dumpsys media.audio_flinger` / `adb shell dumpsys audio`
- メディアセッション: `adb shell dumpsys media_session`
- ネットワークポリシー: `adb shell dumpsys netpolicy`(UID は `pm list packages -U` で調べる)
- 保存内容(debug ビルド): `adb shell run-as net.minittu.rinplay cat shared_prefs/<名前>.xml`(`dlna` / `ui` / `usb`)
- jUPnP のログ: `adb shell setprop log.tag D`。確認後に `adb shell 'setprop log.tag ""'` で戻す

## 開発ルール
- 変更後は「ビルド → インストール → 起動 → logcat → スクショ」で動作確認する
- 実機で確認していない場合は、その旨を明記する(音が実際に出ているかは、ログ・dumpsys では見えない)
- 破壊的な adb 操作(pm clear / uninstall / rm 等)は事前に確認する
- コミットメッセージは Conventional Commits(`<type>(<scope>): <概要>`)。type は `feat` / `fix` / `refactor` / `perf` / `docs` / `chore` など、scope は `usb` / `dlna` / `ui` / `library` など(省略可)。概要は日本語で 1 行、本文に理由と確認結果を書く。破壊的変更は `!` か `BREAKING CHANGE:` で示す。例: `fix(dlna): フォルダの中でも検索の入力欄が開くようにする`
- 高音質関連の変更では、実際の出力サンプリングレートを dumpsys で確認する
- 画面タップ座標は、ミニプレイヤーの有無や上部の表示でずれる。タップ前に直前のスクショで確認する
- `adb shell cmd uimode night no|yes` でライト/ダークを切り替えて確認できる(確認後は元に戻す)
- 実機での入力テスト: 端末の日本語キーボードは、`adb shell input text` で送った英字をかな変換する(`blue` が `bぅえ` になる)。英数字モード(左下の「あA」)にしてから入力する(欄ごとにかなに戻ることがある)。`KEYCODE_BACK` は、戻る先がないとアプリを閉じるので、そのあとのタップがランチャーに当たる
- Windows の Git Bash 注意: `adb shell` に `/sdcard/...` を渡すときは `MSYS_NO_PATHCONV=1`。`javap -cp` の区切りは `;`。`adb shell setprop X ""` は空文字がシェルに消されるので全体を `'...'` で囲む

## リリース

- `v*` のタグ(例: `v0.1.1`)を push すると、`.github/workflows/release.yml` が署名つき APK をビルドして、GitHub Release に添付する(`RinPlay-<バージョン>.apk` と `.sha256`。説明に APK と署名証明書の SHA-256)。手動実行(`gh workflow run release.yml`)は、ビルドして artifact に置くだけで、Release は作らない。所要時間は 2〜5 分
- バージョン名はタグから(`v0.1.0` → `0.1.0`)、`versionCode` は Actions の実行番号(`-PappVersionName` / `-PappVersionCode`)。ローカルのビルドは `0.1` / `1`
- 署名の鍵は、リポジトリに入れない。GitHub Secrets(`RINPLAY_KEYSTORE_BASE64` / `RINPLAY_KEYSTORE_PASSWORD` / `RINPLAY_KEY_ALIAS` / `RINPLAY_KEY_PASSWORD`)に登録してある。ローカルのバックアップは `~/.rinplay-signing/`(`rinplay-release.jks` とパスワードの `README.txt`)。**失うと、同じアプリとして更新を配布できなくなる**ので、別の場所にもバックアップする。署名証明書の SHA-256: `71:B1:19:73:BB:7E:DF:27:AC:B3:45:B3:2D:9C:55:EC:AB:BB:8C:B7:FD:38:AE:21:0E:0A:9B:F2:21:FF:2D:4C`
- ローカルで署名つきのリリースビルドを試す: 環境変数 `RINPLAY_KEYSTORE`(鍵のパス)/ `RINPLAY_KEYSTORE_PASSWORD` / `RINPLAY_KEY_ALIAS` / `RINPLAY_KEY_PASSWORD` を設定して `./gradlew assembleRelease`(環境変数がなければ、署名なし)。署名の確認は `apksigner verify --print-certs`
- 署名がデバッグビルドと違うので、リリース APK はデバッグビルドの上に上書きインストールできない(先にアンインストール。設定は引き継がれない)
- `android-actions/setup-android@v3` は、`packages: ""` にしないと、既定の `tools` が見つからず失敗する(ランナーの cmdline-tools にない)。Actions の `uses:` はバージョンタグ指定(コミット SHA での固定はしていない)
- リモートのタグを付け直す(削除)のは、破壊的な操作なので、事前に確認する

## 実装メモ

### 出力表示とビットパーフェクト(OS 側)
- 画面の主な表示は、再生中のファイル自体の形式(`SourceFormat`。ミニプレイヤーの補足、全画面「再生中」の再生情報ダイアログ。例: `FLAC 24bit 48kHz` / `MP3 320kbps 44.1kHz`)。ダイアログの「出力形式」(`48 kHz · 32-bit float` など)は、AudioTrack(USB 直結では DAC)に渡した形式で、両者は別物(FLAC 24bit の出力が 32-bit float になる等)
- 元ファイルの形式は `PlaybackService.onAudioInputFormatChanged` の `Format` から作る(`SourceFormat.kt`)。FLAC / WAV は `pcmEncoding` からビット深度(16/24)が取れる。AAC は公称ビットレート(`Format.bitrate`。256kbps を 256 と出す。実測平均は 251)、MP3 は平均(CBR 320kbps → 320366bps → 320、VBR は実際の平均)。MediaStore の `bitrate` 列は VBR で外れる(224000 と出たが実際は 49kbps)ので使わない。ロスレスはビットレートを出さず、ロッシーはビット深度を出さない
- ExoPlayer は次の曲を先読みして `onAudioInputFormatChanged` を通知するので、元ファイルの形式は曲ごと(`SourceFormatState`、キーは mediaId、最大 8 件)に持ち、再生中の曲の mediaId で引く。同じ出力形式の曲が続くと AudioTrack が使い回されて `onAudioTrackInitialized` が来ないので、`OutputFormatState` は曲ごとの情報の根拠にならない。端末の実出力は `dumpsys media.audio_flinger` で、アプリ(uid)のトラックが乗るスレッドを見る
- `setEnableAudioFloatOutput(true)` を維持する。false にすると 24bit FLAC が 16bit 出力になる
- AudioTrack の解放イベントは、新トラック作成後に届くことがある。出力表示は「生きているトラック数」で消す(構成の比較はしない)
- OS 側のビットパーフェクトは Android 14+ の `setPreferredMixerAttributes`(`BitPerfect.kt`):
  - `MODIFY_AUDIO_SETTINGS` の宣言が必要(なしだと `Permission Denial`)
  - `getSupportedMixerAttributes` は behavior=default で返る。`MIXER_BEHAVIOR_BIT_PERFECT` を指定して組み直して渡す
  - 設定は AudioTrack 作成前に行う(`onAudioInputFormatChanged` で呼んでいる)
  - Pixel 6a + UA3 では `no compatible profile found`(Error -2)で失敗する。USB 出力プロファイル(`hifi_playback`)が `AUDIO_OUTPUT_FLAG_NONE` で、ビットパーフェクトの経路が見当たらない(Galaxy + ZH3 でも `要求失敗`)。これが USB 直結を自作した理由
- Pixel 6a の USB 出力ミキサーはレート固定で、曲に追従しない(48kHz と 96kHz のスレッドのみ)。48k/96k はレート一致、44.1k は 48k に、88.2k は 96k にリサンプルされる(実測)

### USB DAC 直接出力(OS のミキサーを通さない)

#### 方針と設定
- `usb/` で UAC2 の ISO 転送を自前で行う。`PlaybackService` が `UsbDirectAudioSink`(`ForwardingAudioSink`)に差し替え、使えないとき(DAC なし・権限なし・非対応のレート/形式・ステレオ以外・接続切れ・設定 OFF)は元の `DefaultAudioSink`(OS 経由)に任せる
- **既定は OFF(オプトイン)**。設定タブ(下部ナビの 3 つ目。`SettingsScreen`)の「USB DAC に直接出力」(`direct_output`、`UsbVolume.directOutput`)を ON にしたときだけ使う。直結では OS の音量が効かず DAC 側の音量で鳴るので、事故を避けるため
  - OFF の間は DAC に触らない(USB 権限のダイアログも出さない)。ON にすると(起動時に ON のときも)権限を要求し、許可されたら、再生中でも直結に切り替える。OFF にしたら、直結中でもすぐ OS 経由に戻る
  - 下の設定は ON のときだけ表示する: 初期音量 / 音量を保持 / 音量調整のない DAC でも使う(`allow_no_volume_dac`、既定 OFF)

#### 構成
- `UsbDacDescriptor.kt`: rawDescriptors の UAC2 解析(AC / AS / クロック / Feature Unit)
- `UsbDac.kt`: 検出・権限・GET_RANGE / SET_CUR / GET_CUR(レート・音量)
- `UsbOutput.kt`: claim・クロック設定・alt 選択・ストリームのスレッド・音量の復元
- `UsbDirectAudioSink.kt`: ExoPlayer との接点(PCM 変換・位置・曲の切り替え・抜き差し)
- `UsbVolume.kt` / `UsbVolumeGuard.kt`: 音量の設定と、元の音量の保存
- `cpp/usb_iso.cpp`: usbdevfs の ISO URB(OUT 16 本 × 8 パケット = 128 TRB)。フィードバック EP(16.16)でパケットごとのフレーム数を調整し、データ不足のときは無音を送る。完了が 0.6 秒来ないときは `STALL` として状態をログに出し、全 URB を取り消して投げ直す(最大 5 回)。Java の USB API は ISO に非対応なので、`UsbDeviceConnection.fileDescriptor` を JNI に渡して ioctl(SUBMITURB / REAPURB)を直接叩く
- 入力 PCM(16/24/32bit / float)は、上位詰め 32bit を経由して DAC のスロット幅(16/24/32bit)に詰める。24bit 以下の元ファイルを float にしたものは 24bit で欠けなく表せる

#### 実測した DAC
- UA3: UAC2、クロック ID 5、AS は IF1(alt1=16bit・alt2=24bit・alt3=32bit・alt4=DSD 用らしい)、出力 EP 0x01(非同期 ISO、maxPacket 776、interval 1)、フィードバック EP 0x81。対応レートは 32k/44.1k/48k/64k/88.2k/96k/128k/176.4k…(15 種の離散値)。音量はマスター −63.5〜0dB(単位 1/256dB)。挿し直すと音量は 0dB に戻る
- ZH3(据え置き型の DAC / アンプ): UAC2、Feature Unit なし(USB から音量を操作できない)。本体のダイヤル(OS の音量とは別)とゲインスイッチで調整する。alt1 が 24bit(32bit スロット)

#### USB の落とし穴(実機で踏んだ)
- 制御転送(GET_RANGE など)は、`claimInterface(force=true)` でカーネルの snd-usb-audio を切り離してからでないと `-1` で失敗する。claim 中は OS の音(通知音など)がこの DAC に出ない
- DAC に触る(claim する)前に、使えるかを判定する(音量の有無・alt・フィードバック EP)。claim 後に失敗して放置すると、ドライバが切り離されたまま OS 経由の再生が壊れる
- DAC を閉じるときは、claim を **AS → AC の順**に解放する(`UsbOutput.releaseAll`)。AC を先に解放すると、カーネルが AS を握られたまま付き直り、ALSA のカードが不完全になる(`controlC1` だけが登録され、OS 経由の再生が `BUFFERING` ⇄ `PLAYING` を 1 秒ごとに繰り返し、位置が 1 秒に約 0.25 秒しか進まない)。AS → AC の順だと `pcmC1D0p` / `controlC1` が両方登録される。`USBDEVFS_CONNECT`(`attachKernelDriver`)は保険で、すでに付いていると EBUSY(-16)
- Galaxy(SM8850)では、OUT の ISO URB を 8 本 × 32 パケット(常時 256 TRB)積むと、約 1 秒(URB 247 件)で OUT の完了だけが止まる(フィードバック IN は動き続け、エラーも出ず、取り消して投げ直しても戻らない。再生位置が数秒で止まる)。16 × 8(128 TRB = 16ms)なら止まらない。xHCI の ISO リングが 1 セグメント 256 TRB を超える深さを積むと詰まる、という見立て(カーネルのログは読めず、原因までは未確認)。`kOutUrbs` / `kOutPackets` を変えるときは、総 TRB を 256 未満に保つ。Pixel 6a は 8 × 32 でも 16 × 8 でも問題なし
- USB 権限は再インストールのたびに外れ、直接出力が ON なら起動時にダイアログが出る。出ている間にほかの場所をタップするとキャンセル扱いになり、そのセッションは OS 経由にフォールバックする
- 無線デバッグ中、再インストールのたびに権限ダイアログの画面を確認してタップする(座標は端末ごとに違う)

#### 音量
- USB 直結ではシステム音量が効かないので、DAC 自身の音量(UAC2 Feature Unit の VOLUME)を `UsbVolume` で持つ。開くときに、設定の音量へ下げてから出す(下げられない DAC は直結しない。ただし「音量調整のない DAC でも使う」が ON なら、アプリは音量を触らず、スライダーも出さない)
- 初期音量(設定。−60〜0dB、1dB 刻み、既定 −30dB。`SharedPreferences usb` の `initial_db256`)が、開くときの音量。全画面「再生中」下部のスライダー(直結で DAC を開いている間だけ表示)で変えた値は、既定ではプロセスが動いている間だけ有効(次回は初期音量から。最大音量で鳴らないため)。設定の「音量を保持」(`keep_volume`、既定 OFF)を ON にすると `last_db256` に保存し、次回もその音量で始める(保存がなければ初期音量)。OFF に戻すと保存済みの音量は捨てる。古い `volume_db256` は未使用
- **音量の復元**: 直結で下げた音量が閉じたあとも残ると、OS 経由の再生が小さい音のままになる。開くときに元の音量を読み(`UsbDac.getVolumes`)、閉じるとき(ドライバを付け直す前)に戻す(`restoreVolumes`)。開くのが途中で失敗したときも戻す
- 異常終了(強制終了など)で閉じる処理を通らなかったときのため、元の音量を `lowered_<vid>_<pid>_<deviceName>`(`SharedPreferences usb`。`UsbVolumeGuard`)に、下げる前に同期で保存し、復元できたら消す。次に開くとき保存が残っていれば、DAC から読んだ(下がったままの)値ではなく、保存した値を元の音量とする。サービスの起動時(`UsbOutput.recoverVolume`。権限があれば。直接出力の ON / OFF に関係なく)にも、保存が残っていれば戻す。`deviceName` は挿し直すと変わるので、挿し直された DAC(音量が初期値に戻る)の古い保存は使われない。音量調整のない DAC は対象外

#### 曲の切り替え
- ExoPlayer は、バッファの再生が済むのを待たずに、次の曲の `handleDiscontinuity()` → `configure()` を呼ぶ(`playToEndOfStream()` は曲の切り替えでは呼ばれない)。以前は、ここでリング(約 2.7 秒)と転送中の分を毎回捨てていて、曲の終わりの約 3 秒が再生されず、シークバーも最後まで行かなかった(40 秒の曲が 37.0 秒で切れていた)
- いまは、同じ出力(同じレートで、alt のビット幅が足りる)ならバッファを捨てずに続け、位置は `checkpoints`(書き込んだフレーム番号 → その位置)で切り替える(DefaultAudioSink と同じ考え方)。別のレートが要るときは、`pendingConfig` に溜めて、`handleBuffer` で `false` を返して待たせ、溜まっている分を再生し終えてから、出力を開き直す。DAC を閉じて付け直し、開き直すので、約 0.35 秒の無音が入る(ギャップレスではない)。同じレートでも、停止やデコーダ側の切り替えで数 ms の無音(アンダーラン 1〜2 件)が出ることがある

#### DAC の抜き差し
- `PlaybackService` が `USB_DEVICE_ATTACHED` / `DETACHED` を受ける。挿されたら(直接出力が ON なら)権限を要求し(アプリの再起動は不要)、`UsbDirectAudioSink.onUsbAttached()` を呼ぶ。シンクは次の `handleBuffer` で直結に切り替える(OS 経由で再生中でも切り替わる。OS 側に溜まっていた分は捨てるので少し飛ぶ)
- 抜かれたら、転送が `ENODEV` で止まり、シンクが OS 経由に戻る(再生は `BECOMING_NOISY` で一時停止になる)。5 秒以内に続けて失われたときは、挿し直されるまで直結を試さない

#### 確認状況(実機)
- 確認済み:
  - Pixel 6a + UA3 / ZH3、Galaxy + UA3 / ZH3: 44.1k / 48kHz の直結(数十秒、アンダーラン 0 件)、クロックの設定と検証、停止後に OS 経由の再生が戻る(ALSA の `pcmC1D0p` / `controlC1`)
  - 音量: 初期音量・音量を保持の反映と保存、停止 / `kill -9` / `force-stop` のあとに 0dB へ戻る
  - 曲の切り替え: 同じレートで最後まで再生してから次の曲へ。レートが変わる切り替え(44.1k → 48k)
  - 抜き差し: 再生中に抜く(`PAUSED`、落ちない)→ 挿し直す → 権限ダイアログ → 再生で直結に復帰。別の DAC(UA3 ↔ ZH3)への差し替え。再生中に設定で ON / OFF
- 未確認: [未確認・未実装] を参照

### DLNA(jUPnP)
- 自宅 LAN の検出結果: `Music Server`(音楽あり。動作確認はこちら)、`rec`(HDD レコーダー。音楽は入っていない)。`rec` は音楽の確認対象外
- 検出には `WifiManager.MulticastLock` が必要(`DlnaRepository.start()` で取得。タグ `rinplay-dlna`)
- 標準の `AndroidUpnpServiceConfiguration` は Jetty に依存し、Android 上で `NoClassDefFoundError: org/eclipse/jetty/server/Server` で落ちる。`MyUpnpService`/`MyUpnpConfiguration`(`dlna/`)で、HTTP クライアントを `HttpURLConnection` 版(`HttpUrlStreamClient`)に差し替え、サーバー(`createStreamServer`)は `null`。このため SUBSCRIBE(イベント購読)は未対応
- slf4j は 2.x 系の `uk.uuid.slf4j:slf4j-android` を使う。`org.slf4j:slf4j-android` 1.7.x だと NOP になり、jUPnP のログが全部消える
- Android 17 の `android.permission.ACCESS_LOCAL_NETWORK`(NEARBY_DEVICES グループ、実行時権限)が必要。許可した直後は OS がプロセスを終了することがある(再起動で復帰)
- 通信が止まる場合は `dumpsys netpolicy` を確認する。`UID=<アプリ> policy=262144 (REJECT_ALL)` と `Restricted networking mode: true` のとき、マルチキャスト送信が `sendto failed: EPERM` で全部失敗する。アプリ側では直らない(targetSdk を下げても変わらない)。端末の設定側で通信制限を外す
- PC から SSDP を確認するときは、VPN や複数 NIC の影響を避けるため、端末と同じサブネットの NIC に bind して送る(bind しないと VPN 側へ出て「見えない」と誤判定する)
- Browse は 1 回 `DLNA_PAGE_SIZE`(500)件ずつ取得し、一覧の末尾の 20 件手前に来たら次のページを自動で読む(`DlnaScreen`)。曲をタップしたときは、残りのページを裏で読んで再生キューに足す(`DlnaRepository.appendRemainingToQueue`)。ローカル曲の再生を始めるときと新しい DLNA 再生の前に `cancelQueueLoading()` で止める
- `TotalMatches` は信用しない。`Music Server` は 2 ページ目以降で「開始位置以降の残り件数」を返す(例: 全 50 件を 20 件ずつ読むと total が 50 → 30 → 10)。「全件数だけで打ち切る」と最後のページが落ちるため、`DlnaPage.hasMore` は「ページが要求件数でいっぱい、または全件数が先の件数を示す」で判断する。表示する全件数は 1 ページ目の値を使う
- ページ送りの動作確認は、`DLNA_PAGE_SIZE` を一時的に 20 にして行う(手元の最大フォルダが 50 件のため)。戻し忘れに注意
- 最後に開いたサーバー(UDN)とフォルダ階層(ID+タイトル)は `DlnaPrefs`(SharedPreferences `dlna`)に保存する。DLNA タブを開いて該当サーバーが検出されたとき 1 度だけ復元する。復元の判断が済むまでは保存しない(起動直後の空状態で上書きしないため)。サーバー一覧に戻っても最後のサーバーは残る(階層は空になる)
- 下部ナビのセクション(ライブラリ / DLNA)は `UiPrefs`(SharedPreferences `ui`、キー `last_tab`、0=ライブラリ 1=DLNA)に保存し、起動時にそのセクションで開く。切り替えのたびに保存する。初回はライブラリ。設定タブは保存しない
- ローカルの下位タブ(曲 / アルバム / アーティスト)と検索語は保存しない(`rememberSaveable` なので画面回転では残るが、アプリを終了すると消える)

### ライブラリ(一覧・ジャケット)
- ローカル: `MusicRepository.load()` が MediaStore を 1 回読み、`LocalLibrary`(曲・アルバム・アーティスト)を作る。画面は `LocalLibraryScreen`(曲 / アルバム / アーティストの切り替え。アルバム・アーティストを開くと階層移動し、戻るキーで 1 つ戻る)
- アーティストは「アルバムアーティスト(API 30+)、なければ曲のアーティスト」でまとめる。値が `<unknown>` や空なら `不明なアーティスト` / `不明なアルバム`。アルバム内はトラック番号順
- ローカルのジャケットは `content://media/external/audio/albumart/<ALBUM_ID>`(Android 17 でも Coil で読めることを実機で確認)。同じ URI を `MediaMetadata.artworkUri` に入れ、再生バーと通知で使う
- DLNA のジャケットは DIDL の `upnp:albumArtURI`(`DlnaEntry.artworkUri`)。`Music Server` は返す。サーバーによっては返さないので、無ければプレースホルダー
- DLNA の音楽ライブラリ: サーバーを選ぶと `DlnaRepository.findMusicHome()` が、ルート直下の `Music` と、その中の曲(`All Music`)・アルバム(`Album`)・アーティスト(`Artist`)を**名前で**探す(英語・日本語の候補。サーバーごとに名前が違うため)。見つかれば `Music` を起点にして「ホーム / 曲 / アルバム / アーティスト」のタブを出す。見つからなければ従来どおりルートからのフォルダ閲覧。結果はセッション中キャッシュする
- DLNA の行の種類は UPnP クラスで判定する(`object.container.album.musicAlbum` → アルバム、`object.container.person.musicArtist` → アーティスト、それ以外のコンテナ → フォルダ)。アルバム行は `アーティスト · N曲`(`upnp:artist`/`dc:creator` と `childCount`)、曲は `アーティスト — アルバム`(曲がすべて同じアルバムのときはアーティストのみ)
- アーティスト行に件数は出さない。`Music Server` のアーティスト直下は `- All Albums -`(全曲まとめ)という疑似フォルダを含み、`childCount` がアルバム数と合わないため
- パスは `Music` を起点とした相対(ホームのパスは空)。保存済みの古い絶対パス(`Music` から始まるもの)も、ID が絶対なのでそのまま使える
- `Music Server` の構成(実測): ルート 0 → `Browse Folders` / `Music`(1)/ `Pictures` / `Video`。`Music` → `Album`(1$7)/ `All Music`(1$4)/ `Artist`(1$6)/ `Folders` / `Genre` / `Playlists` / `Recently Added`。メタデータのない曲は、タイトルだけで補足が空になる
- 画像の読み込みは Coil 3。http(s) には `coil-network-okhttp` が必要。平文 http は `usesCleartextTraffic=true` で許可
- 一覧の行は `LibraryRow`、アルバムのカードは `AlbumCard`、画像は `Artwork`(`LibraryRow.kt`)でローカルと DLNA を共通化。ミニプレイヤーと全画面「再生中」は埋め込みジャケット(`artworkData`)を優先し、なければ `artworkUri`

### UI の構成(Material 3)
- `MainActivity` → `RinPlayTheme`(`Theme.kt`)→ `AppRoot`(`AppRoot.kt`)。エッジ・ツー・エッジ。ダイナミックカラー(Android 12+)とライト/ダークは端末設定に従う(起動直後の背景は `values/themes.xml` と `values-night/themes.xml`)
- `AppRoot`: 権限の要求、`MediaController` の接続、ライブラリ読み込み(ローカルは `Dispatchers.IO`)、下部の「ミニプレイヤー + ナビ(ライブラリ / DLNA / 設定)」、ミニプレイヤーをタップで開く全画面「再生中」(`AnimatedVisibility`。開いている間は戻るキーでまず閉じる)
- 再生状態は `PlaybackUiState`(`rememberPlaybackUiState`)で 1 か所の `Player.Listener` から受け、ミニプレイヤーと全画面で共有する(再生中・曲・位置・シャッフル・リピート)。位置は再生中だけ 500ms ごとに更新
- `LocalLibraryScreen`: 上部バー(タイトル + 検索アイコン。検索は `SearchTopBar` で、バーの中に入力欄が開く)+ タブ(曲 / アルバム / アーティスト)。アルバムは大きなジャケットのグリッド(`LazyVerticalGrid`)。再生中の曲は、タイトルをテーマ色にして右端にアイコンを出す
- `DlnaScreen`: 上部バー(戻る・検索・サーバー再検索)+ タブ(ホーム / 曲 / アルバム / アーティスト)。フォルダの中身がすべてアルバムのときはグリッド、それ以外はリスト。戻るキーは「フォルダの奥 → 検索を閉じる → サーバー一覧」の順
- 全画面「再生中」: 大きなジャケット(残りの高さに収まる正方形)、曲名、アーティスト — アルバム、シーク、シャッフル/前/再生/次/リピート(なし → 全曲 → 1 曲)。右上の再生情報アイコン(`PlaybackInfoButton`)は、出力経路 `OutputPath` で状態が変わる(USB 直結 = USB アイコン + 背景色 / OS のビットパーフェクト = 高品質アイコン + 背景色 / 標準出力 = 波形アイコン(背景なし)/ 出力情報なし = 薄い波形)。タップで `PlaybackInfoDialog` に、状態・再生中のファイルの形式・出力形式・ビットパーフェクトの可否を出す。USB 直結中は、下部に DAC の音量スライダーが出る
- 設定(`SettingsScreen`): USB DAC への直接出力まわり(上記)。文章は短くする
- アイコンは `material-icons-extended`(絵文字や文字記号は使わない)。アプリアイコンはアダプティブ(`mipmap-anydpi-v26/ic_launcher.xml`。背景は単色、前景は音符のベクター、モノクロ版つき)
- Compose の落とし穴(実機で踏んだ): 一覧(`LazyColumn`)の各行のタップ処理は一度作られると使い回され、`controller`(再生サービスへの接続。起動直後は null)を値のまま握ると、タブを切り替えて一覧を作り直すまで再生が始まらない(起動 53 秒後でも `NONE` のまま)。タップ処理の中では `rememberUpdatedState(controller)` で常に最新の値を読む(`LocalLibraryScreen` / `DlnaScreen`)。再発したら「起動直後 4 秒でタップして再生が始まるか」で確認する

### 検索
- ローカル: `LocalLibrary.search()`。曲名・アーティスト・アルバム名で絞り込み、空白区切りの語はすべて含む(AND)。NFKC で正規化し小文字にするので、全角/半角・大文字/小文字は区別しない。結果は「アーティスト / アルバム / 曲」の見出しつき。曲をタップすると、結果の曲がキューになる
- DLNA: サーバーの ContentDirectory `Search` を使う(`DlnaRepository.searchLibrary`)。`GetSearchCapabilities` が返すプロパティだけを使い、プロパティごとに単純な検索を並列に投げて id で重複を除いて統合する(曲: `dc:title` / `upnp:artist` / `upnp:album`、アルバム: `dc:title` / `upnp:artist`、アーティスト: `dc:title`。クラスは `derivedfrom` で絞る)。`or` や括弧は `Music Server` では通るが、他のサーバーで通る保証がないので使わない
- 検索式の文字列は `"` と `\` をバックスラッシュでエスケープする。曲は 200 件、アルバム/アーティストは 100 件まで(曲が上限を超えたら見出しに `+` を付ける)。入力が止まって 400ms で検索する
- 検索結果は「パスが空のとき」に出す。検索語を入れたら、開いていたフォルダから抜ける(`path.clear()`)。結果のアルバム/アーティストを開いて戻れば、検索語が残っているので結果に戻る。サーバーを変えると検索語は消える
- フォルダの中で検索ボタンを押したときは、いまの場所を `pathBeforeSearch` に覚えて `path` を空にしてから検索バーを開き、検索を閉じたら元の場所に戻す(入力欄と結果は `path` が空のときだけ出す作りのため)。サーバーを変えると覚えた場所は捨てる
- 検索に非対応のサーバー(`GetSearchCapabilities` が無い・空)では「このサーバーは検索に対応していません」を出す。クライアント側での代替検索は未実装
- `Music Server` の `SearchCaps`: `dc:creator, dc:date, dc:title, upnp:album, upnp:actor, upnp:artist, upnp:class, upnp:genre, @id, @parentID, @refID`

## 未確認・未実装
- 音: 聴感の確認(ノイズ・音飛び・ピッチ。ログと再生位置の確認だけで、音は聞いていない)
- USB 直結:
  - 88.2k / 96k / 176.4k の再生、長時間のフィードバックの安定性
  - 一時停止・シーク後に古い音が最大 16ms 残る件
  - OS 経由で再生中に DAC を挿して(`ATTACHED`)直結に切り替わること(設定の ON では確認済み)、抜いた直後(5 秒以内)の挿し直し
  - レートが変わる曲の切り替えのギャップレス化(いまは約 0.35 秒の無音)
  - 実装されていない: DSD、ステレオ以外、フィードバック EP のない DAC(implicit feedback)
- DLNA: タブの場所を名前で探す方式は `Music Server` でしか確認していない(他のサーバーで `Music` 等が見つからない場合はフォルダ閲覧にフォールバックする想定だが未確認)。トラック番号の表示・ジャンル/プレイリストの専用表示は未実装
- DLNA: 実際に 500 件を超えるフォルダ(ページ送りはページサイズ 20 で 50 件を 3 ページに分けて確認済み)、保存したフォルダが消えている場合・保存済みサーバーが見つからない場合の挙動、サーバーが 1 回の返却件数を絞り、かつ全件数が残り件数のとき(`hasMore` の取りこぼし)、手動でサーバーを選んだときに復元が割り込まないこと(コード上のみ)
- 検索: 検索に非対応の DLNA サーバー(エラー表示になる想定。未確認)、曲が 200 件を超えるときの続き読み込み(未実装)、ローカルの大規模ライブラリでの速度、全角入力・ひらがな/カタカナの同一視(未確認/未対応)
- ライブラリ: 並べ替え、フォルダ別表示は未実装。ローカルは起動時に全曲を一括で読む(数千曲規模での速度は未確認)。通知のジャケットは単色画像での確認のみ(実画像では未確認)
- ロック画面からの操作、通知のシークバー操作
- UI: アプリアイコンの見た目(ランチャー上の表示)、横向き・タブレットなど大きい画面、フォントを大きくしたとき、再生キューの表示・並べ替え、ミニプレイヤーのスワイプ操作は未確認/未実装
