package com.example.myplayer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController

@Composable
fun DlnaScreen(repository: DlnaRepository, controller: MediaController?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { DlnaPrefs(context.applicationContext) }
    val servers by repository.servers.collectAsState()
    var server by remember { mutableStateOf<DlnaServer?>(null) }
    val path = remember { mutableStateListOf<Crumb>() }
    var entries by remember { mutableStateOf<List<DlnaEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 前回開いていた場所は、サーバーが検出されたら 1 度だけ復元する。
    // 手動で選んだあとは復元しない。保存も復元の判断が済んでから行う(null で上書きしないため)。
    var restoreDone by remember { mutableStateOf(false) }
    LaunchedEffect(servers) {
        if (restoreDone) return@LaunchedEffect
        val saved = prefs.load()
        if (saved == null) {
            restoreDone = true
            return@LaunchedEffect
        }
        val match = servers.firstOrNull { it.udn == saved.serverUdn } ?: return@LaunchedEffect
        server = match
        path.clear()
        path.addAll(saved.path)
        restoreDone = true
    }
    LaunchedEffect(server?.udn, path.toList(), restoreDone) {
        val s = server
        if (restoreDone && s != null) prefs.save(DlnaPlace(s.udn, path.toList()))
    }

    // サーバーが一覧から消えたら選択を解除する。
    LaunchedEffect(servers) {
        val current = server
        if (current != null && servers.none { it.udn == current.udn }) {
            server = null
            path.clear()
        }
    }

    val currentId = path.lastOrNull()?.id
    LaunchedEffect(server?.udn, currentId) {
        val s = server ?: return@LaunchedEffect
        loading = true
        error = null
        repository.browse(s, currentId ?: "0")
            .onSuccess { entries = it }
            .onFailure { error = it.message; entries = emptyList() }
        loading = false
    }

    BackHandler(enabled = server != null) {
        if (path.isNotEmpty()) path.removeAt(path.lastIndex) else server = null
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = server?.let { s -> (listOf(s.name) + path.map { it.title }).joinToString(" / ") }
                    ?: "DLNA サーバー(${servers.size})",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { repository.search() }) { Text("再検索") }
        }

        error?.let { Text("エラー: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (loading) Text("読み込み中…", modifier = Modifier.padding(16.dp))

        if (server == null) {
            if (servers.isEmpty()) {
                Text("サーバーが見つかりません", modifier = Modifier.padding(16.dp))
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(servers, key = { it.udn }) { s ->
                    Text(
                        s.name,
                        modifier = Modifier.fillMaxWidth().clickable {
                            restoreDone = true
                            server = s
                        }.padding(16.dp),
                    )
                }
            }
        } else {
            val playable = entries.filter { !it.isContainer }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.id }) { entry ->
                    Text(
                        text = if (entry.isContainer) "📁 ${entry.title}" else entry.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (entry.isContainer) {
                                    path.add(Crumb(entry.id, entry.title))
                                } else {
                                    controller?.run {
                                        setMediaItems(playable.mapNotNull { it.mediaItem }, playable.indexOf(entry), 0L)
                                        prepare()
                                        play()
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
