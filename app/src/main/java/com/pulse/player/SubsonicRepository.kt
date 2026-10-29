package com.pulse.player

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder

/** Loads music from a Navidrome, Subsonic, Airsonic, or compatible personal server. */
class SubsonicRepository(private val context: Context) {
    private fun endpoint(method: String, extra: Map<String, String> = emptyMap()): String {
        val parameters = linkedMapOf(
            "u" to AppPreferences.musicServerUsername(context),
            "t" to AppPreferences.musicServerToken(context),
            "s" to AppPreferences.musicServerSalt(context),
            "v" to "1.16.1", "c" to "Pulse", "f" to "json"
        ) + extra
        val query = parameters.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}" }
        return "${AppPreferences.musicServerUrl(context)}/rest/$method?$query"
    }

    fun verify() {
        request("ping").getJSONObject("subsonic-response").also {
            check(it.getString("status") == "ok") { it.optString("error", "Server rejected the connection") }
        }
    }

    /** Fetches tracks from the most recently added 100 albums for fast first load. */
    fun songs(): List<Song> {
        val albums = request("getAlbumList2", mapOf("type" to "newest", "size" to "100"))
            .getJSONObject("subsonic-response").optJSONObject("albumList2")?.optJSONArray("album") ?: return emptyList()
        return buildList {
            for (index in 0 until albums.length()) {
                val album = albums.getJSONObject(index)
                val albumSongs = request("getAlbum", mapOf("id" to album.getString("id")))
                    .getJSONObject("subsonic-response").getJSONObject("album").optJSONArray("song") ?: continue
                for (songIndex in 0 until albumSongs.length()) add(songFrom(albumSongs.getJSONObject(songIndex)))
            }
        }.distinctBy { it.id }
    }

    private fun songFrom(data: JSONObject): Song {
        val id = data.getString("id")
        return Song(
            id = "server:$id",
            title = data.optString("title", "Untitled"),
            artist = data.optString("artist", "Unknown artist"),
            album = data.optString("album"),
            durationMs = data.optLong("duration") * 1_000L,
            uri = Uri.parse(endpoint("stream", mapOf("id" to id))),
            fromStreamingServer = true
        )
    }

    private fun request(method: String, extra: Map<String, String> = emptyMap()): JSONObject {
        val connection = (java.net.URL(endpoint(method, extra)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
        }
        return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    }
}
