package com.pulse.player

import android.content.Context
import androidx.documentfile.provider.DocumentFile

/** Reads a same-name .lrc file from one of the music folders the user added to Pulse. */
object LyricsLoader {
    fun load(context: Context, title: String): String? {
        val expected = "${title.trim()}.lrc"
        AppPreferences.folderUris(context).forEach { uri ->
            val root = DocumentFile.fromTreeUri(context, uri) ?: return@forEach
            val pending = ArrayDeque<DocumentFile>()
            pending += root
            while (pending.isNotEmpty()) {
                val file = pending.removeFirst()
                file.listFiles().forEach { child ->
                    when {
                        child.isDirectory -> pending += child
                        child.isFile && child.name.equals(expected, ignoreCase = true) -> {
                            return context.contentResolver.openInputStream(child.uri)
                                ?.bufferedReader()
                                ?.use { it.readText() }
                        }
                    }
                }
            }
        }
        return null
    }
}
