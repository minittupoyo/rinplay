package com.example.myplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.text.Normalizer

data class LocalTrack(
    val item: MediaItem,
    val albumId: Long,
    val album: String,
    /** アルバムアーティスト(なければ曲のアーティスト)。アーティスト別の表示のキー */
    val artist: String,
    val trackNo: Int,
)

data class LocalAlbum(val id: Long, val title: String, val artist: String, val trackCount: Int)

data class LocalArtist(val name: String, val albumCount: Int, val trackCount: Int, val artworkAlbumId: Long)

data class LocalLibrary(
    val tracks: List<LocalTrack>,
    val albums: List<LocalAlbum>,
    val artists: List<LocalArtist>,
) {
    /** アルバム内の曲を、ディスク番号・トラック番号順に返す。 */
    fun tracksOfAlbum(albumId: Long): List<LocalTrack> =
        tracks.filter { it.albumId == albumId }
            .sortedWith(compareBy({ it.trackNo }, { it.item.mediaMetadata.title?.toString().orEmpty() }))

    fun albumsOfArtist(name: String): List<LocalAlbum> = albums.filter { it.artist == name }

    /**
     * 曲名・アーティスト・アルバム名で絞り込む。空白で区切った語はすべて含む(AND)。
     * 全角/半角と大文字/小文字の違いは区別しない。
     */
    fun search(query: String): LocalSearchResults {
        val terms = query.foldedForSearch().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return LocalSearchResults(emptyList(), emptyList(), emptyList())

        fun matches(vararg fields: String): Boolean {
            val text = fields.joinToString(" ") { it.foldedForSearch() }
            return terms.all { it in text }
        }
        return LocalSearchResults(
            artists = artists.filter { matches(it.name) },
            albums = albums.filter { matches(it.title, it.artist) },
            tracks = tracks.filter { matches(it.item.mediaMetadata.title?.toString().orEmpty(), it.artist, it.album) },
        )
    }

    companion object {
        val EMPTY = LocalLibrary(emptyList(), emptyList(), emptyList())
    }
}

data class LocalSearchResults(
    val artists: List<LocalArtist>,
    val albums: List<LocalAlbum>,
    val tracks: List<LocalTrack>,
)

/** 検索用の正規化。全角を半角に寄せ(NFKC)、小文字にする。 */
private fun String.foldedForSearch(): String = Normalizer.normalize(this, Normalizer.Form.NFKC).lowercase()

private val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")

fun albumArtUri(albumId: Long): Uri = ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)

class MusicRepository(private val context: Context) {

    fun load(): LocalLibrary {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val hasAlbumArtist = Build.VERSION.SDK_INT >= 30
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.TRACK)
            if (hasAlbumArtist) add(MediaStore.Audio.Media.ALBUM_ARTIST)
        }.toTypedArray()

        val tracks = mutableListOf<LocalTrack>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val albumArtistCol = if (hasAlbumArtist) cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST) else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val albumId = cursor.getLong(albumIdCol)
                val title = cursor.getString(titleCol).orEmpty()
                val trackArtist = cursor.getString(artistCol).orUnknown(UNKNOWN_ARTIST)
                val albumArtist = if (albumArtistCol >= 0) cursor.getString(albumArtistCol) else null
                val album = cursor.getString(albumCol).orUnknown(UNKNOWN_ALBUM)
                // アルバムアーティストが無い・"<unknown>" のときは、曲のアーティストで代用する。
                val artist = albumArtist.validOrNull() ?: trackArtist
                val trackNo = cursor.getInt(trackCol)
                tracks += LocalTrack(
                    item = MediaItem.Builder()
                        .setMediaId(id.toString())
                        .setUri(ContentUris.withAppendedId(collection, id))
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(title)
                                .setArtist(trackArtist)
                                .setAlbumTitle(album)
                                .setArtworkUri(albumArtUri(albumId))
                                .build(),
                        )
                        .build(),
                    albumId = albumId,
                    album = album,
                    artist = artist,
                    trackNo = trackNo,
                )
            }
        }

        val albums = tracks.groupBy { it.albumId }
            .map { (id, list) -> LocalAlbum(id, list.first().album, list.first().artist, list.size) }
            .sortedBy { it.title.lowercase() }
        val artists = tracks.groupBy { it.artist }
            .map { (name, list) ->
                LocalArtist(
                    name = name,
                    albumCount = list.map { it.albumId }.distinct().size,
                    trackCount = list.size,
                    artworkAlbumId = list.first().albumId,
                )
            }
            .sortedBy { it.name.lowercase() }
        return LocalLibrary(tracks, albums, artists)
    }

    /** MediaStore は値が無いとき "<unknown>" を返す。空・不明なら [fallback] に置き換える。 */
    private fun String?.orUnknown(fallback: String): String =
        if (isNullOrBlank() || this == MediaStore.UNKNOWN_STRING) fallback else this

    /** 空・不明なら null。 */
    private fun String?.validOrNull(): String? =
        if (isNullOrBlank() || this == MediaStore.UNKNOWN_STRING) null else this

    private companion object {
        const val UNKNOWN_ARTIST = "不明なアーティスト"
        const val UNKNOWN_ALBUM = "不明なアルバム"
    }
}
