package com.example.myplayer

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

class MusicRepository(private val context: Context) {

    fun loadAll(): List<MediaItem> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
        )
        val items = mutableListOf<MediaItem>()
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
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                items += MediaItem.Builder()
                    .setMediaId(id.toString())
                    .setUri(ContentUris.withAppendedId(collection, id))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(cursor.getString(titleCol))
                            .setArtist(cursor.getString(artistCol))
                            .setAlbumTitle(cursor.getString(albumCol))
                            .build(),
                    )
                    .build()
            }
        }
        return items
    }
}
