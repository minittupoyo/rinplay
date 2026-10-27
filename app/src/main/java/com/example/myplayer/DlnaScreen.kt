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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** 末尾から何件手前で次のページを読み始めるか。 */
private const val PREFETCH = 20

@Composable
fun DlnaScreen(repository: DlnaRepository, controller: MediaController?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { DlnaPrefs(context.applicationContext) }
    val servers by repository.servers.collectAsState()
    var server by remember { mutableStateOf<DlnaServer?>(null) }
    val path = remember { mutableStateListOf<Crumb>() }
    var entries by remember { mutableStateOf<List<DlnaEntry>>(emptyList()) }
    // 読み込み済みのオブジェクト数(次ページの開始位置)と、サーバーが答えた全件数(0 は不明)
    var loadedCount by remember { mutableLongStateOf(0L) }
    var totalCount by remember { mutableLongStateOf(0L) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // サーバーの音楽ライブラリの入口(Music と、曲/アルバム/アーティストの場所)。
    // 探し終わるまで(homeResolved が false の間)は一覧を読まない。ルートを読んでから読み直すのを避けるため。
    var home by remember { mutableStateOf<DlnaMusicHome?>(null) }
    var homeResolved by remember { mutableStateOf(false) }
    LaunchedEffect(server?.udn) {
        val s = server
        home = null
        homeResolved = false
        if (s != null) {
            home = repository.findMusicHome(s)
            homeResolved = true
        }
    }

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

    // パスが空のときの起点。音楽ライブラリが見つかれば Music、なければサーバーのルート。
    val currentId = path.lastOrNull()?.id ?: home?.rootId
    val listState = rememberLazyListState()

    // フォルダを開いたら、状態を初期化して 1 ページ目を読む。
    LaunchedEffect(server?.udn, currentId, homeResolved) {
        val s = server ?: return@LaunchedEffect
        if (!homeResolved) return@LaunchedEffect
        loading = true
        loadingMore = false
        error = null
        entries = emptyList()
        loadedCount = 0L
        totalCount = 0L
        hasMore = false
        listState.scrollToItem(0)
        repository.browse(s, currentId ?: "0", 0L)
            .onSuccess { page ->
                entries = page.entries
                loadedCount = page.objectCount
                totalCount = page.totalMatches
                hasMore = page.hasMore(0L, DLNA_PAGE_SIZE)
            }
            .onFailure { error = it.message }
        loading = false
    }

    // 末尾に近づいたら次のページを読む。
    LaunchedEffect(server?.udn, currentId, homeResolved) {
        val s = server ?: return@LaunchedEffect
        if (!homeResolved) return@LaunchedEffect
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            // 読み込むたびに entries.size が変わるので、まだ末尾付近なら続けて発火する。
            if (hasMore && !loading && last >= entries.size - PREFETCH) entries.size else -1
        }.distinctUntilChanged().filter { it >= 0 }.collect {
            loadingMore = true
            val start = loadedCount
            repository.browse(s, currentId ?: "0", start)
                .onSuccess { page ->
                    entries = entries + page.entries
                    loadedCount = start + page.objectCount
                    // 全件数は 1 ページ目の値を使う(2 ページ目以降は残り件数を返すサーバーがある)。
                    hasMore = page.hasMore(start, DLNA_PAGE_SIZE)
                }
                .onFailure {
                    error = it.message
                    hasMore = false
                }
            loadingMore = false
        }
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

        // 音楽ライブラリの入口が見つかったサーバーでは、ローカルと同じ「曲 / アルバム / アーティスト」を出す。
        val library = home
        if (server != null && library != null) {
            val tab = when (path.firstOrNull()?.id) {
                null -> 0
                library.songs?.id -> 1
                library.albums?.id -> 2
                library.artists?.id -> 3
                else -> 0
            }
            Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                DlnaTab("ホーム", tab == 0) { path.clear() }
                library.songs?.let { c -> DlnaTab("曲", tab == 1) { path.clear(); path.add(c) } }
                library.albums?.let { c -> DlnaTab("アルバム", tab == 2) { path.clear(); path.add(c) } }
                library.artists?.let { c -> DlnaTab("アーティスト", tab == 3) { path.clear(); path.add(c) } }
            }
        }

        if (server != null && hasMore && totalCount > 0) {
            Text(
                "$loadedCount / $totalCount 件を読み込み済み",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
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
            // 曲がすべて同じアルバムなら、補足にアルバム名は出さない(アルバムを開いた画面)。
            val singleAlbum = remember(entries) {
                entries.filter { !it.isContainer }.mapNotNull { it.albumTitle?.takeIf(String::isNotEmpty) }
                    .distinct().size <= 1
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.id }) { entry ->
                    LibraryRow(
                        title = entry.title,
                        subtitle = entrySubtitle(entry, singleAlbum),
                        artwork = entry.artworkUri,
                        placeholder = when (entry.kind) {
                            DlnaKind.ALBUM -> "💿"
                            DlnaKind.ARTIST -> "👤"
                            DlnaKind.FOLDER -> "📁"
                            DlnaKind.TRACK -> "♪"
                        },
                        onClick = {
                            val ctrl = controller
                            if (entry.isContainer) {
                                path.add(Crumb(entry.id, entry.title))
                            } else if (ctrl != null) {
                                val queue = entries.filter { !it.isContainer }
                                repository.cancelQueueLoading()
                                ctrl.setMediaItems(queue.mapNotNull { it.mediaItem }, queue.indexOf(entry), 0L)
                                ctrl.prepare()
                                ctrl.play()
                                // 続きのページがあれば、裏で読み込んでキューに足す。
                                if (hasMore) {
                                    server?.let {
                                        repository.appendRemainingToQueue(it, currentId ?: "0", loadedCount, ctrl)
                                    }
                                }
                            }
                        },
                    )
                }
                if (loadingMore) {
                    item { Text("読み込み中…", modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DlnaTab(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(if (selected) "● $label" else label) }
}

/** 一覧の行の補足。アルバムは「アーティスト · N曲」、フォルダは件数、曲はアーティスト(と、必要ならアルバム)。 */
private fun entrySubtitle(entry: DlnaEntry, singleAlbum: Boolean): String? = when {
    entry.kind == DlnaKind.ALBUM ->
        listOfNotNull(entry.artistName, entry.childCount?.let { "${it}曲" }).joinToString(" · ").ifEmpty { null }
    // アーティスト直下は「- All Albums -」のような疑似フォルダを含み、件数が実態と合わないので出さない。
    entry.kind == DlnaKind.ARTIST -> null
    entry.isContainer -> entry.childCount?.let { "${it}件" }
    singleAlbum || entry.albumTitle.isNullOrEmpty() -> entry.artistName
    else -> listOfNotNull(entry.artistName, entry.albumTitle).joinToString(" — ")
}
