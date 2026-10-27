@file:OptIn(ExperimentalMaterial3Api::class)

package net.minittu.rinplay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** 末尾から何件手前で次のページを読み始めるか。 */
private const val PREFETCH = 20

/** 入力が止まってから検索を始めるまでの待ち時間。 */
private const val SEARCH_DEBOUNCE_MS = 400L

/** 覚えておくフォルダの中身の数。超えたら、使われていないものから捨てる。 */
private const val FOLDER_CACHE_SIZE = 8

/** 一度読んだフォルダの中身。戻ったときに読み直さず、すぐ元の位置で出すために持っておく。 */
private class FolderContent(
    val entries: List<DlnaEntry>,
    val loadedCount: Long,
    val totalCount: Long,
    val hasMore: Boolean,
)

/** 場所ごとの key。サーバーと、いま見ているオブジェクトの組。 */
private fun folderKey(udn: String?, objectId: String?) = "${udn.orEmpty()}#${objectId ?: "0"}"

/** 覚えておく数を超えたら、使われていないものから捨てる(key は残す)。 */
private fun trimFolders(folders: LinkedHashMap<String, FolderContent>, keep: String) {
    while (folders.size > FOLDER_CACHE_SIZE) {
        val oldest = folders.keys.firstOrNull { it != keep } ?: return
        folders.remove(oldest)
    }
}

/** 一覧がアルバムだけなら、ジャケットを大きく見せるグリッドで出す。 */
private fun isAlbumGrid(entries: List<DlnaEntry>) = entries.isNotEmpty() && entries.all { it.kind == DlnaKind.ALBUM }

/** DLNA サーバー上の音楽。上部バー(戻る・検索・再検索)+ タブ + 一覧。 */
@Composable
fun DlnaScreen(
    repository: DlnaRepository,
    controller: MediaController?,
    currentMediaId: String?,
    modifier: Modifier = Modifier,
) {
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
    // フォルダごとのスクロール位置と、一度読んだ中身。戻ったときに読み直さず、元の位置で出すために持っておく。
    // 中身は使った順に持ち(FOLDER_CACHE_SIZE を超えたら古いものから捨てる)、位置は単純に貯めておく。
    val listPositions = remember { mutableMapOf<String, Pair<Int, Int>>() }
    val gridPositions = remember { mutableMapOf<String, Pair<Int, Int>>() }
    val folders = remember { LinkedHashMap<String, FolderContent>(16, 0.75f, true) }
    // いま一覧に出している場所の key。スクロール位置を、その場所のものとして覚えるために使う。
    var shownKey by remember { mutableStateOf<String?>(null) }

    // 検索。入力が止まってからサーバーの Search を呼ぶ。サーバーを変えたら検索語は消す。
    var searchActive by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<DlnaSearchResults?>(null) }
    var searching by remember { mutableStateOf(false) }
    // フォルダの中で検索を開いたときの、元の場所。検索を閉じたらここに戻る(検索中は path を空にして結果を出すため)。
    var pathBeforeSearch by remember { mutableStateOf<List<Crumb>?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }
    // 検索結果のスクロール位置。結果からアルバムなどを開いて戻っても、元の位置に戻る。
    val searchState = rememberLazyListState()
    LaunchedEffect(query) { searchState.requestScrollToItem(0) }
    LaunchedEffect(server?.udn) {
        query = ""
        searchActive = false
        pathBeforeSearch = null
        // サーバーを変えたら、覚えている中身は捨てる(key も変わるが、古い分を残しておく理由がない)。
        folders.clear()
    }
    LaunchedEffect(server?.udn, query) {
        val s = server
        if (s == null || query.isBlank()) {
            searchResults = null
            searchError = null
            searching = false
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        searching = true
        searchError = null
        repository.searchLibrary(s, query)
            .onSuccess { searchResults = it }
            .onFailure {
                searchResults = null
                searchError = it.message
            }
        searching = false
    }
    val showingSearch = server != null && searchActive && path.isEmpty() && query.isNotBlank()

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
    val gridState = rememberLazyGridState()

    /** いま一覧に出している場所のスクロール位置を覚える。フォルダを開く・戻るときに呼ぶ。 */
    fun rememberScroll() {
        val key = shownKey ?: return
        if (entries.isEmpty()) return
        if (isAlbumGrid(entries)) {
            gridPositions[key] = gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        } else {
            listPositions[key] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
    }

    /** 覚えているスクロール位置へ戻す(無ければ先頭)。一覧が出来てから呼ぶ。 */
    fun restoreScroll(key: String) {
        val grid = isAlbumGrid(entries)
        val position = (if (grid) gridPositions[key] else listPositions[key]) ?: (0 to 0)
        if (grid) {
            gridState.requestScrollToItem(position.first, position.second)
        } else {
            listState.requestScrollToItem(position.first, position.second)
        }
    }

    // フォルダを開いたら 1 ページ目を読む。一度読んだ場所なら、覚えている中身を出して、スクロール位置も戻す。
    LaunchedEffect(server?.udn, currentId, homeResolved) {
        val s = server ?: return@LaunchedEffect
        if (!homeResolved) return@LaunchedEffect
        val key = folderKey(s.udn, currentId)
        shownKey = key
        // scrollToItem は一覧が最初に描画されるまで待つので、読み込み中(一覧がまだ無い)では止まってしまう。
        // 待たずに、次の描画で先頭へ戻すよう予約する(覚えている位置があれば、下で上書きする)。
        listState.requestScrollToItem(0)
        gridState.requestScrollToItem(0)
        val cached = folders[key]
        if (cached != null) {
            entries = cached.entries
            loadedCount = cached.loadedCount
            totalCount = cached.totalCount
            hasMore = cached.hasMore
            loading = false
            loadingMore = false
            error = null
            restoreScroll(key)
            return@LaunchedEffect
        }
        loading = true
        loadingMore = false
        error = null
        entries = emptyList()
        loadedCount = 0L
        totalCount = 0L
        hasMore = false
        repository.browse(s, currentId ?: "0", 0L)
            .onSuccess { page ->
                entries = page.entries
                loadedCount = page.objectCount
                totalCount = page.totalMatches
                hasMore = page.hasMore(0L, DLNA_PAGE_SIZE)
                folders[key] = FolderContent(page.entries, loadedCount, totalCount, hasMore)
                trimFolders(folders, key)
                restoreScroll(key)
            }
            .onFailure { error = it.message }
        loading = false
    }

    // 末尾に近づいたら次のページを読む。
    LaunchedEffect(server?.udn, currentId, homeResolved) {
        val s = server ?: return@LaunchedEffect
        if (!homeResolved) return@LaunchedEffect
        val key = folderKey(s.udn, currentId)
        snapshotFlow {
            val last = lastVisibleIndex(entries, listState, gridState)
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
            // 続きも覚えておく(戻ったときに、読み直さず同じ一覧を出すため)。
            folders[key] = FolderContent(entries, loadedCount, totalCount, hasMore)
            trimFolders(folders, key)
            loadingMore = false
        }
    }

    fun closeSearch() {
        searchActive = false
        query = ""
        // フォルダの中で検索を開いていたなら、その場所に戻す。
        pathBeforeSearch?.let {
            path.clear()
            path.addAll(it)
        }
        pathBeforeSearch = null
    }

    fun openSearch() {
        // 入力欄は、フォルダの外(path が空)のときだけ出す作りなので、いまの場所を覚えてからフォルダを出る。
        if (path.isNotEmpty()) {
            rememberScroll()
            pathBeforeSearch = path.toList()
            path.clear()
        }
        searchActive = true
    }

    /** 戻る。フォルダの奥 → 検索を閉じる → サーバー一覧、の順。 */
    fun goBack() {
        when {
            path.isNotEmpty() -> {
                rememberScroll()
                path.removeAt(path.lastIndex)
            }
            searchActive -> closeSearch()
            else -> server = null
        }
    }
    BackHandler(enabled = server != null) { goBack() }

    // 一覧の各行のタップ処理は使い回されるので、起動直後の controller = null を握ったままにならないよう、
    // 常に最新の値を読む。
    val latestController by rememberUpdatedState(controller)

    /** 一覧の項目を開く。フォルダなら中へ、曲なら再生(続きのページはキューに足す)。 */
    fun open(entry: DlnaEntry) {
        if (entry.isContainer) {
            rememberScroll()
            path.add(Crumb(entry.id, entry.title))
            return
        }
        val ctrl = latestController ?: return
        val queue = entries.filter { !it.isContainer }
        repository.cancelQueueLoading()
        ctrl.setMediaItems(queue.mapNotNull { it.mediaItem }, queue.indexOf(entry), 0L)
        ctrl.prepare()
        ctrl.play()
        // 続きのページがあれば、裏で読み込んでキューに足す。
        if (hasMore) {
            server?.let { repository.appendRemainingToQueue(it, currentId ?: "0", loadedCount, ctrl) }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        val currentServer = server
        if (currentServer != null && searchActive && path.isEmpty()) {
            SearchTopBar(
                query = query,
                onQueryChange = {
                    query = it
                    // 検索語を入れたら、いま開いているフォルダから抜けて検索結果を出す。
                    if (it.isNotBlank()) path.clear()
                },
                onClose = ::closeSearch,
                placeholder = "${currentServer.name} を検索",
            )
        } else {
            TopAppBar(
                title = {
                    Text(
                        text = when {
                            currentServer == null -> "DLNA"
                            path.isNotEmpty() -> path.last().title
                            else -> currentServer.name
                        },
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    if (currentServer != null) {
                        IconButton(onClick = ::goBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "戻る")
                        }
                    }
                },
                actions = {
                    if (currentServer != null) {
                        IconButton(onClick = ::openSearch) {
                            Icon(Icons.Rounded.Search, contentDescription = "検索")
                        }
                    }
                    IconButton(onClick = { repository.search() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "サーバーを再検索")
                    }
                },
            )
        }

        // 音楽ライブラリの入口が見つかったサーバーでは、ローカルと同じ「曲 / アルバム / アーティスト」を出す。
        val library = home
        if (currentServer != null && library != null && !(searchActive && path.isEmpty())) {
            val sections = buildList {
                add("ホーム" to null)
                library.songs?.let { add("曲" to it) }
                library.albums?.let { add("アルバム" to it) }
                library.artists?.let { add("アーティスト" to it) }
            }
            val selected = if (path.isEmpty()) 0 else {
                sections.indexOfFirst { it.second?.id == path.first().id }.takeIf { it >= 0 } ?: 0
            }
            PrimaryScrollableTabRow(selectedTabIndex = selected, edgePadding = 0.dp) {
                sections.forEachIndexed { index, (label, crumb) ->
                    Tab(
                        selected = selected == index,
                        onClick = {
                            rememberScroll()
                            closeSearch()
                            path.clear()
                            crumb?.let { path.add(it) }
                        },
                        text = { Text(label) },
                    )
                }
            }
        }

        when {
            currentServer == null -> ServerList(
                servers = servers,
                onSelect = {
                    restoreDone = true
                    server = it
                },
                onRefresh = { repository.search() },
            )
            showingSearch -> DlnaSearchResultsView(
                results = searchResults,
                searching = searching,
                error = searchError,
                currentMediaId = currentMediaId,
                state = searchState,
                onOpenContainer = { path.add(Crumb(it.id, it.title)) },
                onPlay = { tracks, index ->
                    val ctrl = latestController
                    if (ctrl != null) {
                        repository.cancelQueueLoading()
                        ctrl.setMediaItems(tracks.mapNotNull { it.mediaItem }, index, 0L)
                        ctrl.prepare()
                        ctrl.play()
                    }
                },
            )
            searchActive && path.isEmpty() -> Unit
            error != null && entries.isEmpty() -> EmptyState(
                icon = Icons.Rounded.ErrorOutline,
                message = "読み込めませんでした\n$error",
            )
            loading && entries.isEmpty() -> LoadingBox()
            entries.isEmpty() -> EmptyState(Icons.Rounded.SearchOff, "このフォルダには項目がありません")
            isAlbumGrid(entries) -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(entries, key = { it.id }) { entry ->
                    AlbumCard(
                        title = entry.title,
                        subtitle = entrySubtitle(entry, singleAlbum = false),
                        artwork = rowArtwork(entry),
                        placeholder = Icons.Rounded.Album,
                        onClick = { open(entry) },
                    )
                }
                if (loadingMore) item(span = { GridItemSpan(maxLineSpan) }) { LoadingFooter() }
            }
            else -> {
                // 曲がすべて同じアルバムなら、補足にアルバム名は出さない(アルバムを開いた画面)。
                val singleAlbum = remember(entries) {
                    entries.filter { !it.isContainer }.mapNotNull { it.albumTitle?.takeIf(String::isNotEmpty) }
                        .distinct().size <= 1
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        LibraryRow(
                            title = entry.title,
                            subtitle = entrySubtitle(entry, singleAlbum),
                            artwork = rowArtwork(entry),
                            placeholder = entry.kind.icon(),
                            shape = if (entry.kind == DlnaKind.ARTIST) CircleArtwork else DefaultArtworkShape,
                            isCurrent = !entry.isContainer && entry.mediaItem?.mediaId == currentMediaId,
                            onClick = { open(entry) },
                        )
                    }
                    if (loadingMore) item { LoadingFooter() }
                }
            }
        }
    }
}

/** 表示中の一覧の、いちばん下に見えている項目の位置。一覧(リスト/グリッド)に合わせて読み分ける。 */
private fun lastVisibleIndex(entries: List<DlnaEntry>, list: LazyListState, grid: LazyGridState): Int =
    (if (isAlbumGrid(entries)) grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index
    else list.layoutInfo.visibleItemsInfo.lastOrNull()?.index) ?: -1

private fun DlnaKind.icon(): ImageVector = when (this) {
    DlnaKind.ALBUM -> Icons.Rounded.Album
    DlnaKind.ARTIST -> Icons.Rounded.Person
    DlnaKind.FOLDER -> Icons.Rounded.Folder
    DlnaKind.TRACK -> Icons.Rounded.MusicNote
}

/** サーバー一覧。見つからないときは、探している旨と再検索ボタン。 */
@Composable
private fun ServerList(servers: List<DlnaServer>, onSelect: (DlnaServer) -> Unit, onRefresh: () -> Unit) {
    if (servers.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.Dns,
            message = "DLNA サーバーを探しています\n同じ Wi-Fi につながっているか確認してください",
            actionLabel = "再検索",
            onAction = onRefresh,
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.fillMaxSize()) {
        items(servers, key = { it.udn }) { s ->
            LibraryRow(
                title = s.name,
                subtitle = "DLNA サーバー",
                artwork = null,
                placeholder = Icons.Rounded.Dns,
                shape = CircleArtwork,
                onClick = { onSelect(s) },
            )
        }
    }
}

@Composable
private fun LoadingFooter() {
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.padding(4.dp))
    }
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

/** 検索結果の表示。アーティスト → アルバム → 曲の順。曲は読み込めた分をそのままキューにする。 */
@Composable
private fun DlnaSearchResultsView(
    results: DlnaSearchResults?,
    searching: Boolean,
    error: String?,
    currentMediaId: String?,
    state: LazyListState,
    onOpenContainer: (DlnaEntry) -> Unit,
    onPlay: (List<DlnaEntry>, Int) -> Unit,
) {
    if (error != null) {
        EmptyState(Icons.Rounded.ErrorOutline, error)
        return
    }
    if (results == null) {
        if (searching) LoadingBox()
        return
    }
    if (results.artists.isEmpty() && results.albums.isEmpty() && results.tracks.isEmpty()) {
        if (searching) LoadingBox() else EmptyState(Icons.Rounded.SearchOff, "見つかりません")
        return
    }
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(bottom = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (results.artists.isNotEmpty()) {
            item(key = "header:artists") { SectionHeader("アーティスト") }
            items(results.artists, key = { "artist:${it.id}" }) { entry ->
                SearchRow(entry, currentMediaId) { onOpenContainer(entry) }
            }
        }
        if (results.albums.isNotEmpty()) {
            item(key = "header:albums") { SectionHeader("アルバム") }
            items(results.albums, key = { "album:${it.id}" }) { entry ->
                SearchRow(entry, currentMediaId) { onOpenContainer(entry) }
            }
        }
        if (results.tracks.isNotEmpty()) {
            val more = if (results.tracksTruncated) "(上位 ${results.tracks.size} 件)" else ""
            item(key = "header:tracks") { SectionHeader("曲$more") }
            itemsIndexed(results.tracks, key = { _, entry -> "track:${entry.id}" }) { index, entry ->
                SearchRow(entry, currentMediaId) { onPlay(results.tracks, index) }
            }
        }
    }
}

@Composable
private fun SearchRow(entry: DlnaEntry, currentMediaId: String?, onClick: () -> Unit) {
    LibraryRow(
        title = entry.title,
        subtitle = entrySubtitle(entry, singleAlbum = false),
        artwork = rowArtwork(entry),
        placeholder = entry.kind.icon(),
        shape = if (entry.kind == DlnaKind.ARTIST) CircleArtwork else DefaultArtworkShape,
        isCurrent = !entry.isContainer && entry.mediaItem?.mediaId == currentMediaId,
        onClick = onClick,
    )
}

/**
 * 行に出すジャケット。サーバーがジャケットを出していない曲だけ、ファイルに埋め込まれたジャケットを取りに行く。
 * サーバーの 160px のサムネイルは、行の大きさ(52dp)では十分きれいなので、余計な通信はしない。
 */
@Composable
private fun rowArtwork(entry: DlnaEntry): Any? {
    val mediaItem = entry.mediaItem
    val embedded = rememberEmbeddedArtwork(
        mediaId = mediaItem?.mediaId?.takeIf { entry.artworkUri == null },
        mediaUrl = mediaItem?.localConfiguration?.uri?.toString(),
    )
    return entry.artworkUri ?: embedded
}
