package com.example.myplayer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

@Composable
fun LocalLibraryScreen(
    library: LocalLibrary,
    controller: MediaController?,
    onPlayStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val stack = remember { mutableStateListOf<LocalView>() }
    // 検索結果。アルバムやアーティストを開いて戻っても、検索語が残っていれば結果に戻る。
    val results = remember(library, query) { library.search(query) }
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }

    fun play(tracks: List<LocalTrack>, index: Int) {
        val ctrl = controller ?: return
        onPlayStarted()
        ctrl.setMediaItems(tracks.map { it.item }, index, 0L)
        ctrl.prepare()
        ctrl.play()
    }

    Column(modifier = modifier.fillMaxSize()) {
        SearchField(query, onQueryChange = { query = it })
        Row(modifier = Modifier.padding(horizontal = 8.dp)) {
            TAB_LABELS.forEachIndexed { index, label ->
                TextButton(onClick = {
                    tab = index
                    stack.clear()
                    query = ""
                }) { Text(if (tab == index && query.isBlank()) "● $label" else label) }
            }
        }

        when (val view = stack.lastOrNull()) {
            null -> if (query.isNotBlank()) {
                SearchResultsList(
                    results,
                    onOpenArtist = { stack.add(LocalView.ArtistAlbums(it)) },
                    onOpenAlbum = { stack.add(LocalView.AlbumTracks(it)) },
                    onPlay = ::play,
                )
            } else when (tab) {
                0 -> TrackList(library.tracks, showAlbum = true, onPlay = ::play)
                1 -> AlbumList(library.albums, onOpen = { stack.add(LocalView.AlbumTracks(it)) })
                else -> ArtistList(library, onOpen = { stack.add(LocalView.ArtistAlbums(it)) })
            }
            is LocalView.AlbumTracks -> {
                Breadcrumb("アルバム / ${view.album.title}")
                TrackList(library.tracksOfAlbum(view.album.id), showAlbum = false, onPlay = ::play)
            }
            is LocalView.ArtistAlbums -> {
                Breadcrumb("アーティスト / ${view.artist.name}")
                AlbumList(
                    library.albumsOfArtist(view.artist.name),
                    onOpen = { stack.add(LocalView.AlbumTracks(it)) },
                )
            }
        }
    }
}

@Composable
private fun Breadcrumb(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun TrackList(tracks: List<LocalTrack>, showAlbum: Boolean, onPlay: (List<LocalTrack>, Int) -> Unit) {
    if (tracks.isEmpty()) {
        Text("曲が見つかりません", modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(tracks, key = { _, track -> track.item.mediaId }) { index, track ->
            val metadata = track.item.mediaMetadata
            LibraryRow(
                title = metadata.title?.toString().orEmpty(),
                subtitle = if (showAlbum) "${track.artist} — ${track.album}" else track.artist,
                artwork = metadata.artworkUri,
                placeholder = "♪",
                onClick = { onPlay(tracks, index) },
            )
        }
    }
}

@Composable
private fun AlbumList(albums: List<LocalAlbum>, onOpen: (LocalAlbum) -> Unit) {
    if (albums.isEmpty()) {
        Text("アルバムが見つかりません", modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(albums, key = { it.id }) { album ->
            LibraryRow(
                title = album.title,
                subtitle = "${album.artist} · ${album.trackCount}曲",
                artwork = albumArtUri(album.id),
                placeholder = "💿",
                onClick = { onOpen(album) },
            )
        }
    }
}

@Composable
private fun ArtistList(library: LocalLibrary, onOpen: (LocalArtist) -> Unit) {
    if (library.artists.isEmpty()) {
        Text("アーティストが見つかりません", modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(library.artists, key = { it.name }) { artist ->
            LibraryRow(
                title = artist.name,
                subtitle = "${artist.albumCount}枚 · ${artist.trackCount}曲",
                artwork = albumArtUri(artist.artworkAlbumId),
                placeholder = "👤",
                onClick = { onOpen(artist) },
            )
        }
    }
}

@Composable
private fun SearchResultsList(
    results: LocalSearchResults,
    onOpenArtist: (LocalArtist) -> Unit,
    onOpenAlbum: (LocalAlbum) -> Unit,
    onPlay: (List<LocalTrack>, Int) -> Unit,
) {
    if (results.artists.isEmpty() && results.albums.isEmpty() && results.tracks.isEmpty()) {
        Text("見つかりません", modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (results.artists.isNotEmpty()) {
            item(key = "header:artists") { SectionHeader("アーティスト(${results.artists.size})") }
            items(results.artists, key = { "artist:${it.name}" }) { artist ->
                LibraryRow(
                    title = artist.name,
                    subtitle = "${artist.albumCount}枚 · ${artist.trackCount}曲",
                    artwork = albumArtUri(artist.artworkAlbumId),
                    placeholder = "👤",
                    onClick = { onOpenArtist(artist) },
                )
            }
        }
        if (results.albums.isNotEmpty()) {
            item(key = "header:albums") { SectionHeader("アルバム(${results.albums.size})") }
            items(results.albums, key = { "album:${it.id}" }) { album ->
                LibraryRow(
                    title = album.title,
                    subtitle = "${album.artist} · ${album.trackCount}曲",
                    artwork = albumArtUri(album.id),
                    placeholder = "💿",
                    onClick = { onOpenAlbum(album) },
                )
            }
        }
        if (results.tracks.isNotEmpty()) {
            item(key = "header:tracks") { SectionHeader("曲(${results.tracks.size})") }
            itemsIndexed(results.tracks, key = { _, track -> "track:${track.item.mediaId}" }) { index, track ->
                LibraryRow(
                    title = track.item.mediaMetadata.title?.toString().orEmpty(),
                    subtitle = "${track.artist} — ${track.album}",
                    artwork = track.item.mediaMetadata.artworkUri,
                    placeholder = "♪",
                    onClick = { onPlay(results.tracks, index) },
                )
            }
        }
    }
}
