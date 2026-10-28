package com.pulse.player

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

class MediaStoreRepository(private val context: Context) {
    fun songs(): List<Song> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        return context.contentResolver.query(collection, projection, selection, arrayOf("1000"), sort)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            buildList {
                while (cursor.moveToNext()) {
                    val audioId = cursor.getLong(id)
                    add(Song(
                        id = audioId,
                        title = cursor.getString(title).orEmpty().ifBlank { "Untitled" },
                        artist = cursor.getString(artist).orEmpty().replace("<unknown>", "Unknown artist"),
                        album = cursor.getString(album).orEmpty(),
                        durationMs = cursor.getLong(duration),
                        uri = ContentUris.withAppendedId(collection, audioId)
                    ))
                }
            }
        } ?: emptyList()
    }
}
