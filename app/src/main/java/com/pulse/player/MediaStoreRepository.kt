package com.pulse.player

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile

class MediaStoreRepository(private val context: Context) {
    fun songs(includeMediaStore: Boolean, folders: List<android.net.Uri>): List<Song> {
        val librarySongs = if (includeMediaStore) mediaStoreSongs() else emptyList()
        return (librarySongs + folderSongs(folders)).distinctBy { it.uri.toString() }.sortedBy { it.title.lowercase() }
    }

    private fun mediaStoreSongs(): List<Song> {
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
                        id = audioId.toString(),
                        title = cursor.getString(title).orEmpty().ifBlank { "Untitled" },
                        artist = cursor.getString(artist).orEmpty().replace("<unknown>", "Unknown artist"),
                        album = cursor.getString(album).orEmpty(),
                        durationMs = cursor.getLong(duration),
                            uri = ContentUris.withAppendedId(collection, audioId),
                            fromAddedFolder = false
                    ))
                }
            }
        } ?: emptyList()
    }

    private fun folderSongs(folders: List<android.net.Uri>): List<Song> = buildList {
        folders.forEach { uri ->
            val root = DocumentFile.fromTreeUri(context, uri) ?: return@forEach
            addAll(audioFilesBelow(root))
        }
    }

    private fun audioFilesBelow(root: DocumentFile): List<Song> {
        val allowed = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")
        val found = mutableListOf<Song>()
        val pending = ArrayDeque<DocumentFile>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val item = pending.removeFirst()
            item.listFiles().forEach { child ->
                when {
                    child.isDirectory -> pending.add(child)
                    child.isFile && child.name?.substringAfterLast('.', "")?.lowercase() in allowed -> {
                        val displayName = child.name.orEmpty()
                        found += Song(
                            id = child.uri.toString(),
                            title = displayName.substringBeforeLast('.', displayName),
                            artist = "Added folder",
                            album = root.name.orEmpty(),
                            durationMs = 0,
                            uri = child.uri,
                            fromAddedFolder = true
                        )
                    }
                }
            }
        }
        return found
    }
}
