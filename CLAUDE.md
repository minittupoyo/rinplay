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
- テスト音源: `/sdcard/Music/` に FLAC 4 つ(44.1k/16, 48k/16, 88.2k/24, 96k/24)。ffmpeg で生成した正弦波(タグなし。アルバム名はフォルダ名の `Music`、アーティストは `Generated` になる)
- 形式表示の確認用: `/sdcard/Music/TestFormats/` に、アルバム `Formats` / Fmt Test の 4 曲(`MP3 320k`、`MP3 VBR V2`、`AAC 256k`、`FLAC 24bit 48k`)と、タグなしの WAV 24bit 1 つ(`fmt_wav_24`)。ffmpeg で生成(WAV はタグなしだと MediaStore に音楽として載らず、アルバムは `TestFormats`、アーティストは不明になる)
- ライブラリ表示の確認用: `/sdcard/Music/TestAlbums/` に、ジャケット埋め込み・タグ付きの FLAC 5 つ(`Blue Album` / Artist A が 3 曲、`Red Album` / Artist B が 2 曲)。ffmpeg の `-disposition:v attached_pic` で画像を埋めた

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
- 画面の主な表示は、再生中のファイル自体の形式(`SourceFormat`。ミニプレイヤーの補足、全画面「再生中」のチップ。例: `FLAC 24bit 48kHz` / `MP3 320kbps 44.1kHz`)。全画面の小さい文字の「出力 48 kHz · 32-bit float」は、AudioTrack に渡した形式(`onAudioTrackInitialized`)で、両者は別物(FLAC 24bit の出力が 32-bit float になる等)。以前は出力側だけを出していたため、DLNA の MP3 320kbps が「16-bit」と見えていた
- 元ファイルの形式は `PlaybackService.onAudioInputFormatChanged` の `Format` から作る(`SourceFormat.kt`)。実機で確認した値: FLAC / WAV は `pcmEncoding` からビット深度(16/24)が取れる。AAC は公称ビットレート(`Format.bitrate`。256kbps を 256 と出す。実測平均は 251)、MP3 は平均(CBR 320kbps → 320366bps → 320、VBR は実際の平均。MediaStore の `bitrate` 列は VBR で外れる(224000 と出たが実際は 49kbps)ので使わない)。ロスレスはビットレートを出さず、ロッシーはビット深度を出さない
- ExoPlayer は次の曲を先読みして `onAudioInputFormatChanged` を通知するので、元ファイルの形式は 1 変数ではなく曲ごと(`SourceFormatState`、キーは mediaId、最大 8 件)に持ち、再生中の曲の mediaId で引く。同じ出力形式の曲が続くと AudioTrack が使い回されて `onAudioTrackInitialized` が来ないので、出力側(`OutputFormatState`)は曲ごとの情報の根拠にならない端末の実出力ではない。実出力は `dumpsys media.audio_flinger` で、アプリ(uid)のトラックが乗るスレッドを見る
- `setEnableAudioFloatOutput(true)` を維持する。false にすると 24bit FLAC が 16bit 出力になる
- AudioTrack の解放イベントは、新トラック作成後に届くことがある。出力表示は「生きているトラック数」で消す(構成の比較はしない)
- ビットパーフェクトは Android 14+ の `setPreferredMixerAttributes`。要点:
  - `MODIFY_AUDIO_SETTINGS` の宣言が必要(なしだと `Permission Denial`)
  - `getSupportedMixerAttributes` は behavior=default で返る。`MIXER_BEHAVIOR_BIT_PERFECT` を指定して組み直して渡す
  - 設定は AudioTrack 作成前に行う(`onAudioInputFormatChanged` で呼んでいる)
  - Pixel 6a + Shanling UA3 では `no compatible profile found`(Error -2)で失敗。USB 出力プロファイル(`hifi_playback`)が `AUDIO_OUTPUT_FLAG_NONE` で、ビットパーフェクトの経路が見当たらない。別の端末・DAC では未検証
- この端末の USB 出力ミキサーはレート固定で、曲に追従しない。48kHz と 96kHz のスレッドのみ。48k/96k はレート一致、44.1k は 48k に、88.2k は 96k にリサンプルされる(実測)

### USB DAC 直接出力(OS のミキサーを通さない)
- `setPreferredMixerAttributes` が通らない端末(Pixel 6a + UA3)向けに、`usb/` で UAC2 の ISO 転送を自前で行う。`PlaybackService` が `UsbDirectAudioSink`(`ForwardingAudioSink`)に差し替え、使えないとき(DAC なし・権限なし・非対応のレート/形式・ステレオ以外・接続切れ)は元の `DefaultAudioSink` に任せる
- 構成: `UsbDacDescriptor.kt`(rawDescriptors の UAC2 解析)/ `UsbDac.kt`(検出・権限・GET_RANGE / SET_CUR / GET_CUR)/ `UsbOutput.kt`(claim・クロック設定・alt 選択・スレッド)/ `cpp/usb_iso.cpp`(usbdevfs の ISO URB を 8 本、フィードバック EP の 16.16 でレート調整、不足時は無音)/ `UsbDirectAudioSink.kt`
- UA3(実測): UAC2、クロック ID 5、AS は IF1(alt1=16bit・alt2=24bit・alt3=32bit・alt4=DSD 用らしい)、出力 EP 0x01(非同期 ISO、maxPacket 776、interval 1)、フィードバック EP 0x81。対応レートは 32k/44.1k/48k/64k/88.2k/96k/128k/176.4k…(15 種の離散値)
- 制御転送(GET_RANGE など)は、`claimInterface(force=true)` でカーネルの snd-usb-audio を切り離してからでないと `-1` で失敗する。claim 中は OS の音(通知音など)がこの DAC に出ない
- Java の USB API は ISO 転送に非対応なので、`UsbDeviceConnection.fileDescriptor` を JNI に渡して ioctl(SUBMITURB / REAPURB)を直接叩く。NDK 28.2 / CMake 3.22.1、abi は arm64-v8a のみ
- USB 権限は再インストールのたびに外れ、起動時にダイアログが出る。ダイアログが出ている間にほかの場所をタップするとキャンセル扱いになり、そのセッションは OS 経由の通常出力にフォールバックする
- 音量: USB 直結ではシステム音量が効かないので、DAC 自身の音量(UAC2 Feature Unit の VOLUME。UA3 はマスター −63.5〜0dB、単位 1/256dB)を `UsbVolume` で持つ。「設定」タブ(下部ナビの 3 つ目。`SettingsScreen`)の初期音量(−60〜0dB、1dB 刻み、既定 −30dB。`SharedPreferences usb` の `initial_db256`)が、DAC を開くときの音量。全画面「再生中」下部のスライダー(USB 直結で DAC を開いている間だけ表示)で変えた値は、既定ではプロセスが動いている間だけ有効(次回の起動は初期音量から。最大音量で鳴らないため)。設定タブの「音量を保持する」(`keep_volume`、既定 OFF)を ON にすると `last_db256` に保存し、次回もその音量で始める(保存がなければ初期音量)。OFF に戻すと保存済みの音量は捨てる。古い `volume_db256` は未使用。設定タブは `UiPrefs.lastTab` には保存しない。開くとき、音量を操作できない DAC は USB 直結を使わず OS 経由にフォールバックする。ビットは変わらないのでビットパーフェクトは保たれる
- 確認済み(実機): 音量スライダーと初期音量の設定が DAC に反映される(初期音量 −39dB で開くことをログで確認)。「音量を保持する」ON でアプリ再起動後も保存した音量(−53dB)で開く。保持 OFF での動作はコード上のみ。44.1kHz 24bit スロットで DAC を開き、クロックの設定と検証が通る。再生位置が実時間で進み、アンダーラン 0 件・エラー 0 件。停止で正常終了
- 未確認: 実際の聴感、88.2k / 96k / 176.4k、ギャップレスや曲間のレート切り替え、長時間のフィードバック安定性、停止後にカーネルのドライバが戻るか(`/proc/asound` は読めない)、一時停止・シーク後に古い音が最大 32ms 残る件

### DLNA(jUPnP)
- 自宅 LAN の検出結果: `Music Server`(音楽あり。動作確認はこちら)、`rec`(HDD レコーダー。音楽は入っていない)。`rec` は音楽の確認対象外
- 検出には `WifiManager.MulticastLock` が必要(`DlnaRepository.start()` で取得)
- 標準の `AndroidUpnpServiceConfiguration` は Jetty に依存し、Android 上で `NoClassDefFoundError: org/eclipse/jetty/server/Server` で落ちる。`MyUpnpService`/`MyUpnpConfiguration`(`dlna/`)で、HTTP クライアントを `HttpURLConnection` 版(`HttpUrlStreamClient`)に差し替え、サーバー(`createStreamServer`)は `null`。このため SUBSCRIBE(イベント購読)は未対応
- slf4j は 2.x 系の `uk.uuid.slf4j:slf4j-android` を使う。`org.slf4j:slf4j-android` 1.7.x だと NOP になり、jUPnP のログが全部消える
- Android 17 の `android.permission.ACCESS_LOCAL_NETWORK`(NEARBY_DEVICES グループ、実行時権限)が必要。許可した直後は OS がプロセスを終了することがある(再起動で復帰)
- 通信が止まる場合は `dumpsys netpolicy` を確認する。`UID=<アプリ> policy=262144 (REJECT_ALL)` と `Restricted networking mode: true` のとき、マルチキャスト送信が `sendto failed: EPERM` で全部失敗する。アプリ側では直らない。端末の設定側で通信制限を外す(今回は解除済み)
- targetSdk を下げても EPERM は変わらなかった(ターゲット SDK は原因ではない)
- PC から SSDP を確認するときは、VPN や複数 NIC の影響を避けるため、端末と同じサブネットの NIC に bind して送る(bind しないと VPN 側へ出て「見えない」と誤判定する)
- Browse は 1 回 `DLNA_PAGE_SIZE`(500)件ずつ取得し、一覧の末尾の 20 件手前に来たら次のページを自動で読む(`DlnaScreen`)。曲をタップしたときは、残りのページを裏で読んで再生キューに足す(`DlnaRepository.appendRemainingToQueue`)。ローカル曲の再生を始めるときと新しい DLNA 再生の前に `cancelQueueLoading()` で止める
- `TotalMatches` は信用しない。`Music Server` は 2 ページ目以降で「開始位置以降の残り件数」を返す(例: 全 50 件を 20 件ずつ読むと total が 50 → 30 → 10)。「全件数だけで打ち切る」と最後のページが落ちるため、`DlnaPage.hasMore` は「ページが要求件数でいっぱい、または全件数が先の件数を示す」で判断する。表示する全件数は 1 ページ目の値を使う
- ページ送りの動作確認は、`DLNA_PAGE_SIZE` を一時的に 20 にして行う(手元の最大フォルダが 50 件のため)。戻し忘れに注意
- 最後に開いたサーバー(UDN)とフォルダ階層(ID+タイトル)は `DlnaPrefs`(SharedPreferences `dlna`)に保存する。DLNA タブを開いて該当サーバーが検出されたとき 1 度だけ復元する。復元の判断が済むまでは保存しない(起動直後の空状態で上書きしないため)。サーバー一覧に戻っても最後のサーバーは残る(階層は空になる)
- 保存内容の確認: `adb shell run-as com.example.myplayer cat shared_prefs/dlna.xml`(debug ビルド)
- 下部ナビのセクション(ライブラリ / DLNA)は `UiPrefs`(SharedPreferences `ui`、キー `last_tab`、0=ライブラリ 1=DLNA)に保存し、起動時にそのセクションで開く。切り替えのたびに保存する。初回は保存がないのでライブラリ。確認は `shared_prefs/ui.xml`
- UI: アプリアイコンの見た目(ランチャー上の表示)、横向き・タブレットなど大きい画面、フォントを大きくしたとき、再生キューの表示・並べ替え、ミニプレイヤーのスワイプ操作は未確認/未実装
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
- 画像の読み込みは Coil 3(`coil-compose` + `coil-network-okhttp`。http(s) には後者が必要。平文 http は `usesCleartextTraffic=true` で許可)
- 一覧の行は `LibraryRow`、アルバムのカードは `AlbumCard`、画像は `Artwork`(`LibraryRow.kt`)でローカルと DLNA を共通化。ミニプレイヤーと全画面「再生中」は埋め込みジャケット(`artworkData`)を優先し、なければ `artworkUri`

### UI の構成(Material 3)
- `MainActivity` → `MyPlayerTheme`(`Theme.kt`)→ `AppRoot`(`AppRoot.kt`)。エッジ・ツー・エッジ。ダイナミックカラー(Android 12+)とライト/ダークは端末設定に従う(起動直後の背景は `values/themes.xml` と `values-night/themes.xml`)
- `AppRoot`: 権限の要求、`MediaController` の接続、ライブラリ読み込み(ローカルは `Dispatchers.IO`)、下部の「ミニプレイヤー + ナビ(ライブラリ / DLNA)」、ミニプレイヤーをタップで開く全画面「再生中」(`AnimatedVisibility`。開いている間は戻るキーでまず閉じる)
- 再生状態は `PlaybackUiState`(`rememberPlaybackUiState`)で 1 か所の `Player.Listener` から受け、ミニプレイヤーと全画面で共有する(再生中・曲・位置・シャッフル・リピート)。位置は再生中だけ 500ms ごとに更新
- `LocalLibraryScreen`: 上部バー(タイトル + 検索アイコン。検索は `SearchTopBar` で、バーの中に入力欄が開く)+ タブ(曲 / アルバム / アーティスト)。アルバムは大きなジャケットのグリッド(`LazyVerticalGrid`)。再生中の曲は、タイトルをテーマ色にして右端にアイコンを出す
- `DlnaScreen`: 上部バー(戻る・検索・サーバー再検索)+ タブ(ホーム / 曲 / アルバム / アーティスト)。フォルダの中身がすべてアルバムのときはグリッド、それ以外はリスト。戻るキーは「フォルダの奥 → 検索を閉じる → サーバー一覧」の順
- 全画面「再生中」: 大きなジャケット(残りの高さに収まる正方形)、曲名、アーティスト — アルバム、出力フォーマットのチップとビットパーフェクトの状態、シーク、シャッフル/前/再生/次/リピート(なし → 全曲 → 1 曲)
- アイコンは `material-icons-extended`(絵文字や文字記号は使わない)。アプリアイコンはアダプティブ(`mipmap-anydpi-v26/ic_launcher.xml`。背景は単色、前景は音符のベクター、モノクロ版つき)
- Compose の落とし穴(実機で踏んだ): 一覧(`LazyColumn`)の各行のタップ処理は一度作られると使い回され、`controller`(再生サービスへの接続。起動直後は null)を値のまま握ると、タブを切り替えて一覧を作り直すまで再生が始まらない(起動 53 秒後でも `NONE` のまま)。タップ処理の中では `rememberUpdatedState(controller)` で常に最新の値を読む(`LocalLibraryScreen` / `DlnaScreen`)。再発したら「起動直後 4 秒でタップして再生が始まるか」で確認する
- 実機での注意: ミニプレイヤーの有無で画面下の位置が変わる。タップ座標は直前のスクショで確認する。`adb shell cmd uimode night no|yes` でライト/ダークを切り替えて確認できる(確認後は元に戻す)

### 検索
- ローカル: `LocalLibrary.search()`。曲名・アーティスト・アルバム名で絞り込み、空白区切りの語はすべて含む(AND)。NFKC で正規化し小文字にするので、全角/半角・大文字/小文字は区別しない。結果は「アーティスト / アルバム / 曲」の見出しつき。曲をタップすると、結果の曲がキューになる
- DLNA: サーバーの ContentDirectory `Search` を使う(`DlnaRepository.searchLibrary`)。`GetSearchCapabilities` が返すプロパティだけを使い、プロパティごとに単純な検索を並列に投げて id で重複を除いて統合する(曲: `dc:title` / `upnp:artist` / `upnp:album`、アルバム: `dc:title` / `upnp:artist`、アーティスト: `dc:title`。クラスは `derivedfrom` で絞る)。`or` や括弧は `Music Server` では通るが、他のサーバーで通る保証がないので使わない
- 検索式の文字列は `"` と `\` をバックスラッシュでエスケープする。曲は 200 件、アルバム/アーティストは 100 件まで(曲が上限を超えたら見出しに `+` を付ける)。入力が止まって 400ms で検索する
- 検索結果は「パスが空のとき」に出す。検索語を入れたら、開いていたフォルダから抜ける(`path.clear()`)。結果のアルバム/アーティストを開いて戻れば、検索語が残っているので結果に戻る。サーバーを変えると検索語は消える
- 検索に非対応のサーバー(`GetSearchCapabilities` が無い・空)では「このサーバーは検索に対応していません」を出す。クライアント側での代替検索は未実装
- `Music Server` の `SearchCaps`: `dc:creator, dc:date, dc:title, upnp:album, upnp:actor, upnp:artist, upnp:class, upnp:genre, @id, @parentID, @refID`
- 実機での入力テストの注意: 端末のキーボード(日本語入力)は、`adb shell input text` で送った英字をかな変換する(`blue` が `bぅえ` になる)。英数字モードにするキー(左下の「あA」)を押してから入力する。欄ごとにかなモードに戻ることがある。また、`KEYCODE_BACK` は、戻る先がないとアプリを閉じてしまうので、そのあとのタップがランチャーに当たる

### その他
- 依存バージョンは libs.versions.toml で一元管理

## 未確認・未実装
- DLNA ライブラリ: タブの場所を名前で探す方式は `Music Server` でしか確認していない(他のサーバーで `Music` 等が見つからない場合はフォルダ閲覧にフォールバックする想定だが未確認)。トラック番号の表示・ジャンル/プレイリストの専用表示は未実装
- 検索: 検索に非対応の DLNA サーバー(エラー表示になる想定。未確認)、曲が 200 件を超えるときの続き読み込み(未実装)、ローカルの大規模ライブラリでの速度、全角入力・ひらがな/カタカナの同一視(未確認/未対応)
- ライブラリ: 並べ替え、フォルダ別表示は未実装。ローカルは起動時に全曲を一括で読む(数千曲規模での速度は未確認)。通知のジャケットは単色画像での確認のみ(実画像では未確認)
- 音が実際に出ているかの聴感確認、ロック画面からの操作、通知のシークバー操作
- DLNA: 実際に 500 件を超えるフォルダ(ページ送りはページサイズ 20 で 50 件を 3 ページに分けて確認済み)。保存したフォルダが消えている場合・保存済みサーバーが見つからない場合の挙動
- DLNA: サーバーが 1 回の返却件数を絞り、かつ全件数が残り件数のとき(`hasMore` の取りこぼし)
- DLNA: 手動でサーバーを選んだときに復元が割り込まないこと(コード上のみ)
- 44.1kHz 系をそのまま DAC に渡す経路(OS のミキサーを通らない直接出力は未着手)
