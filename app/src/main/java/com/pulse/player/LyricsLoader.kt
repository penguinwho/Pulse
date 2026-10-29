package com.pulse.player

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Reads a same-name .lrc file from one of the music folders the user added to Pulse. */
object LyricsLoader {
    private val memoryCache = mutableMapOf<String, String?>()

    fun load(context: Context, title: String, artist: String, album: String, durationMs: Long): String? {
        localLyrics(context, title)?.let { return it }
        val cacheKey = "$title\u0000$artist\u0000$album\u0000$durationMs"
        synchronized(memoryCache) {
            if (memoryCache.containsKey(cacheKey)) return memoryCache[cacheKey]
        }
        val online = fetchFromLrcLib(title, artist, album, durationMs)
            ?: searchLrcLib(title, artist)
        synchronized(memoryCache) { memoryCache[cacheKey] = online }
        return online
    }

    private fun localLyrics(context: Context, title: String): String? {
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

    /** Uses LRCLIB only after the user requests lyrics; no library-wide background scanning. */
    private fun fetchFromLrcLib(title: String, artist: String, album: String, durationMs: Long): String? = runCatching {
        if (title.isBlank() || artist.isBlank()) return null
        val request = Uri.Builder()
            .scheme("https")
            .authority("lrclib.net")
            .appendPath("api")
            .appendPath("get")
            .appendQueryParameter("track_name", title)
            .appendQueryParameter("artist_name", artist)
            .apply {
                if (album.isNotBlank()) appendQueryParameter("album_name", album)
                val seconds = durationMs / 1_000
                if (seconds in 1..3_600) appendQueryParameter("duration", seconds.toString())
            }
            .build()
        val connection = (URL(request.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "Pulse/1.2 (https://github.com/penguinwho/Pulse)")
        }
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
        val response = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        response.optString("syncedLyrics").takeIf { it.isNotBlank() }
            ?: response.optString("plainLyrics").takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Exact metadata can differ in local files, so use LRCLIB search as a second, user-requested lookup. */
    private fun searchLrcLib(title: String, artist: String): String? = runCatching {
        val request = Uri.Builder().scheme("https").authority("lrclib.net")
            .appendPath("api").appendPath("search")
            .appendQueryParameter("track_name", title)
            .appendQueryParameter("artist_name", artist)
            .build()
        val connection = (URL(request.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 10_000
            setRequestProperty("User-Agent", "Pulse/1.2 (https://github.com/penguinwho/Pulse)")
        }
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
        val results = org.json.JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        if (results.length() == 0) return null
        val match = results.getJSONObject(0)
        match.optString("syncedLyrics").takeIf { it.isNotBlank() }
            ?: match.optString("plainLyrics").takeIf { it.isNotBlank() }
    }.getOrNull()
}
