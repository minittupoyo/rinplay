package com.example.myplayer

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlayerScreen()
                }
            }
        }
    }
}

private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

private val audioPermission: String
    get() = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

@Composable
private fun PlayerScreen() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        granted = ContextCompat.checkSelfPermission(context, audioPermission) ==
            PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        val wanted = buildList {
            if (!granted) add(audioPermission)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            // Android 17 以降は LAN 上の機器との通信(DLNA 検出)に必要。
            if (Build.VERSION.SDK_INT >= 37 &&
                ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(LOCAL_NETWORK_PERMISSION)
            }
        }
        if (wanted.isNotEmpty()) launcher.launch(wanted.toTypedArray())
    }

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

    val dlna = remember { DlnaRepository(context.applicationContext) }
    DisposableEffect(dlna) {
        dlna.start()
        onDispose { dlna.stop() }
    }
    var tab by remember { mutableIntStateOf(0) }

    var songs by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    LaunchedEffect(granted) {
        if (granted) songs = MusicRepository(context).loadAll()
    }

    val format by OutputFormatState.format.collectAsState()

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            text = format?.let {
                val depth = if (it.isFloat) "${it.bitDepth}bit float" else "${it.bitDepth}bit"
                "出力: ${it.sampleRateHz} Hz / $depth / ${it.channelCount}ch\nビットパーフェクト: ${it.bitPerfect}"
            } ?: "出力: -",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        Row(modifier = Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { tab = 0 }) { Text(if (tab == 0) "● ローカル" else "ローカル") }
            TextButton(onClick = { tab = 1 }) { Text(if (tab == 1) "● DLNA" else "DLNA") }
        }
        if (tab == 1) {
            DlnaScreen(dlna, controller, modifier = Modifier.weight(1f))
        } else if (!granted) {
            Text("音楽ファイルへのアクセス権限が必要です", modifier = Modifier.padding(16.dp))
        } else if (songs.isEmpty()) {
            Text("曲が見つかりません", modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(songs, key = { _, item -> item.mediaId }) { index, item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                controller?.run {
                                    setMediaItems(songs, index, 0L)
                                    prepare()
                                    play()
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(item.mediaMetadata.title?.toString().orEmpty())
                        Text(
                            item.mediaMetadata.artist?.toString().orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        controller?.let { NowPlayingBar(it) }
    }
}
