package com.example.myplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

private val audioPermission: String
    get() = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private fun Context.isGranted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * アプリ全体の骨組み。下部にミニプレイヤーとナビゲーション(ライブラリ / DLNA)、
 * ミニプレイヤーをタップすると全画面の「再生中」が上に開く。
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current

    // --- 権限(音楽、通知、Android 17 以降は LAN 上の機器との通信) ---
    var granted by remember { mutableStateOf(context.isGranted(audioPermission)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = context.isGranted(audioPermission)
    }
    fun requestPermissions() {
        val wanted = buildList {
            if (!context.isGranted(audioPermission)) add(audioPermission)
            if (Build.VERSION.SDK_INT >= 33 && !context.isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= 37 && !context.isGranted(LOCAL_NETWORK_PERMISSION)) {
                add(LOCAL_NETWORK_PERMISSION)
            }
        }
        if (wanted.isNotEmpty()) launcher.launch(wanted.toTypedArray())
    }
    LaunchedEffect(Unit) { requestPermissions() }

    // --- 再生サービスにつなぐ ---
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ controller = future.get() }, ContextCompat.getMainExecutor(context))
        onDispose {
            MediaController.releaseFuture(future)
            controller = null
        }
    }
    val playback = rememberPlaybackUiState(controller)
    val format by OutputFormatState.format.collectAsState()
    // 再生中の曲の、元ファイルの形式。先読み中の次の曲の分と取り違えないよう、mediaId で引く。
    val sources by SourceFormatState.formats.collectAsState()
    val source = playback?.mediaItem?.mediaId?.let { sources[it] }

    // --- ライブラリ ---
    val dlna = remember { DlnaRepository(context.applicationContext) }
    DisposableEffect(dlna) {
        dlna.start()
        onDispose { dlna.stop() }
    }
    var library by remember { mutableStateOf(LocalLibrary.EMPTY) }
    var libraryLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(granted) {
        if (granted) {
            // 曲数が多いと時間がかかるので、画面の描画を止めないよう別のスレッドで読む。
            library = withContext(Dispatchers.IO) { MusicRepository(context).load() }
            libraryLoaded = true
        }
    }

    // --- 画面の状態 ---
    // 最後に開いていたセクション(ライブラリ / DLNA)を、次回の起動でも開く。
    val uiPrefs = remember { UiPrefs(context.applicationContext) }
    var section by remember { mutableIntStateOf(uiPrefs.lastTab) }
    fun selectSection(value: Int) {
        section = value
        uiPrefs.lastTab = value
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val currentMediaId = playback?.mediaItem?.mediaId

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            // 上端のステータスバー分の余白は、各画面の上部バーが取る。
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                Column {
                    if (playback != null && playback.hasMedia) {
                        MiniPlayer(playback, source, onExpand = { expanded = true })
                    }
                    NavigationBar {
                        NavigationBarItem(
                            selected = section == UiPrefs.TAB_LOCAL,
                            onClick = { selectSection(UiPrefs.TAB_LOCAL) },
                            icon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
                            label = { Text("ライブラリ") },
                        )
                        NavigationBarItem(
                            selected = section == UiPrefs.TAB_DLNA,
                            onClick = { selectSection(UiPrefs.TAB_DLNA) },
                            icon = { Icon(Icons.Rounded.Dns, contentDescription = null) },
                            label = { Text("DLNA") },
                        )
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (section) {
                    UiPrefs.TAB_DLNA -> DlnaScreen(dlna, controller, currentMediaId)
                    else -> LocalLibraryScreen(
                        library = library,
                        loaded = libraryLoaded,
                        granted = granted,
                        onRequestPermission = ::requestPermissions,
                        controller = controller,
                        currentMediaId = currentMediaId,
                        // DLNA の続きページをキューに足している最中なら止める(ローカル曲に混ざらないように)。
                        onPlayStarted = { dlna.cancelQueueLoading() },
                    )
                }
            }
        }

        if (playback != null) {
            AnimatedVisibility(
                visible = expanded && playback.hasMedia,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
            ) {
                // 開いている間は、戻るキーでまず閉じる。ここで登録するので、下の画面より優先される。
                BackHandler { expanded = false }
                NowPlayingScreen(playback, source, format, onCollapse = { expanded = false })
            }
        }
    }
}
