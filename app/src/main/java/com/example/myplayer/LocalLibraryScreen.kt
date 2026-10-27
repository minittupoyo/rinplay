@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.myplayer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController

/** 一覧から開いた階層。アルバムの曲一覧と、アーティストのアルバム一覧。 */
private sealed interface LocalView {
    data class AlbumTracks(val album: LocalAlbum) : LocalView
    data class ArtistAlbums(val artist: LocalArtist) : LocalView
}

private val TAB_LABELS = listOf("曲", "アルバム", "アーティスト")

/** 端末内の音楽のライブラリ。上部バー(タイトルと検索)+ タブ(曲 / アルバム / アーティスト)。 */
@Composable
fun LocalLibraryScreen(
    library: LocalLibrary,
    loaded: Boolean,
    granted: Boolean,
    onRequestPermission: () -> Unit,
    controller: MediaController?,
    currentMediaId: String?,
    onPlayStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val stack = remember { mutableStateListOf<LocalView>() }
    // 検索結果。アルバムやアーティストを開いて戻っても、検索語が残っていれば結果に戻る。
    val results = remember(library, query) { library.search(query) }

    fun closeSearch() {
        searchActive = false
        query = ""
    }
    BackHandler(enabled = stack.isNotEmpty() || searchActive) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else closeSearch()
    }

    // 一覧の各行のタップ処理は、一度作られると使い回される。起動直後(再生サービスにつながる前)の
    // controller = null を握ったままにならないよう、常に最新の値を読む。
    val latestController by rememberUpdatedState(controller)
    val latestOnPlayStarted by rememberUpdatedState(onPlayStarted)

    fun play(tracks: List<LocalTrack>, index: Int) {
        val ctrl = latestController ?: return
        latestOnPlayStarted()
        ctrl.setMediaItems(tracks.map { it.item }, index, 0L)
        ctrl.prepare()
        ctrl.play()
    }

    val view = stack.lastOrNull()
    Column(modifier = modifier.fillMaxSize()) {
        when {
            view != null -> TopAppBar(
                title = {
                    Text(
                        when (view) {
                            is LocalView.AlbumTracks -> view.album.title
                            is LocalView.ArtistAlbums -> view.artist.name
                        },
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { stack.removeAt(stack.lastIndex) }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
            searchActive -> SearchTopBar(query, onQueryChange = { query = it }, onClose = ::closeSearch)
            else -> TopAppBar(
                title = { Text("ライブラリ") },
                actions = {
                    if (granted && library.tracks.isNotEmpty()) {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Rounded.Search, contentDescription = "検索")
                        }
                    }
                },
            )
        }

        if (view == null && !searchActive && granted && library.tracks.isNotEmpty()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                TAB_LABELS.forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                }
            }
        }

        when {
            !granted -> EmptyState(
                icon = Icons.Rounded.LibraryMusic,
                message = "端末内の音楽を表示するには、音楽へのアクセスを許可してください",
                actionLabel = "許可する",
                onAction = onRequestPermission,
            )
            !loaded -> LoadingBox()
            library.tracks.isEmpty() -> EmptyState(Icons.Rounded.LibraryMusic, "端末内に音楽が見つかりません")
            view is LocalView.AlbumTracks ->
                TrackList(library.tracksOfAlbum(view.album.id), showAlbum = false, currentMediaId, onPlay = ::play)
            view is LocalView.ArtistAlbums ->
                AlbumGrid(library.albumsOfArtist(view.artist.name), onOpen = { stack.add(LocalView.AlbumTracks(it)) })
            searchActive && query.isNotBlank() -> SearchResultsList(
                results,
                currentMediaId,
                onOpenArtist = { stack.add(LocalView.ArtistAlbums(it)) },
                onOpenAlbum = { stack.add(LocalView.AlbumTracks(it)) },
                onPlay = ::play,
            )
            searchActive -> Unit
            tab == 0 -> TrackList(library.tracks, showAlbum = true, currentMediaId, onPlay = ::play)
            tab == 1 -> AlbumGrid(library.albums, onOpen = { stack.add(LocalView.AlbumTracks(it)) })
            else -> ArtistList(library, onOpen = { stack.add(LocalView.ArtistAlbums(it)) })
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<LocalTrack>,
    showAlbum: Boolean,
    currentMediaId: String?,
    onPlay: (List<LocalTrack>, Int) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        itemsIndexed(tracks, key = { _, track -> track.item.mediaId }) { index, track ->
            val metadata = track.item.mediaMetadata
            LibraryRow(
                title = metadata.title?.toString().orEmpty(),
                subtitle = if (showAlbum) "${track.artist} — ${track.album}" else track.artist,
                artwork = metadata.artworkUri,
                placeholder = Icons.Rounded.MusicNote,
                isCurrent = track.item.mediaId == currentMediaId,
                onClick = { onPlay(tracks, index) },
            )
        }
    }
}

/** アルバムは、ジャケットを大きく見せるグリッドで出す。 */
@Composable
private fun AlbumGrid(albums: List<LocalAlbum>, onOpen: (LocalAlbum) -> Unit) {
    if (albums.isEmpty()) {
        EmptyState(Icons.Rounded.Album, "アルバムが見つかりません")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.id }) { album ->
            AlbumCard(
                title = album.title,
                subtitle = "${album.artist} · ${album.trackCount}曲",
                artwork = albumArtUri(album.id),
                placeholder = Icons.Rounded.Album,
                onClick = { onOpen(album) },
            )
        }
    }
}

@Composable
private fun ArtistList(library: LocalLibrary, onOpen: (LocalArtist) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(library.artists, key = { it.name }) { artist ->
            LibraryRow(
                title = artist.name,
                subtitle = "${artist.albumCount}枚 · ${artist.trackCount}曲",
                artwork = albumArtUri(artist.artworkAlbumId),
                placeholder = Icons.Rounded.Person,
                shape = CircleArtwork,
                onClick = { onOpen(artist) },
            )
        }
    }
}

@Composable
private fun SearchResultsList(
    results: LocalSearchResults,
    currentMediaId: String?,
    onOpenArtist: (LocalArtist) -> Unit,
    onOpenAlbum: (LocalAlbum) -> Unit,
    onPlay: (List<LocalTrack>, Int) -> Unit,
) {
    if (results.artists.isEmpty() && results.albums.isEmpty() && results.tracks.isEmpty()) {
        EmptyState(Icons.Rounded.SearchOff, "見つかりません")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
        if (results.artists.isNotEmpty()) {
            item(key = "header:artists") { SectionHeader("アーティスト") }
            items(results.artists, key = { "artist:${it.name}" }) { artist ->
                LibraryRow(
                    title = artist.name,
                    subtitle = "${artist.albumCount}枚 · ${artist.trackCount}曲",
                    artwork = albumArtUri(artist.artworkAlbumId),
                    placeholder = Icons.Rounded.Person,
                    shape = CircleArtwork,
                    onClick = { onOpenArtist(artist) },
                )
            }
        }
        if (results.albums.isNotEmpty()) {
            item(key = "header:albums") { SectionHeader("アルバム") }
            items(results.albums, key = { "album:${it.id}" }) { album ->
                LibraryRow(
                    title = album.title,
                    subtitle = "${album.artist} · ${album.trackCount}曲",
                    artwork = albumArtUri(album.id),
                    placeholder = Icons.Rounded.Album,
                    onClick = { onOpenAlbum(album) },
                )
            }
        }
        if (results.tracks.isNotEmpty()) {
            item(key = "header:tracks") { SectionHeader("曲") }
            itemsIndexed(results.tracks, key = { _, track -> "track:${track.item.mediaId}" }) { index, track ->
                LibraryRow(
                    title = track.item.mediaMetadata.title?.toString().orEmpty(),
                    subtitle = "${track.artist} — ${track.album}",
                    artwork = track.item.mediaMetadata.artworkUri,
                    placeholder = Icons.Rounded.MusicNote,
                    isCurrent = track.item.mediaId == currentMediaId,
                    onClick = { onPlay(results.tracks, index) },
                )
            }
        }
    }
}
