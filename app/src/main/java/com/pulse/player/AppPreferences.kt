package com.pulse.player

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONArray
import org.json.JSONObject

object AppPreferences {
    private const val FILE = "pulse_settings"
    private const val FOLDERS = "music_folders"
    private const val FAVORITES = "favorite_songs"
    private const val CLOUDFLARE_COVER_SESSION = "cloudflare_cover_session"
    private const val THEME = "theme"
    private const val HISTORY = "listening_history"
    private const val PLAYLISTS = "playlists"
    private const val SLEEP_TIMER_END = "sleep_timer_end"
    private const val DISCORD_ENABLED = "discord_enabled"
    private const val EQUALIZER_PRESET = "equalizer_preset"
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun folders(context: Context): Set<String> = prefs(context).getStringSet(FOLDERS, emptySet()).orEmpty()
    fun folderUris(context: Context): List<Uri> = folders(context).map(Uri::parse)

    fun addFolder(context: Context, uri: Uri) {
        prefs(context).edit().putStringSet(FOLDERS, folders(context) + uri.toString()).apply()
    }

    fun removeFolder(context: Context, uri: Uri) {
        prefs(context).edit().putStringSet(FOLDERS, folders(context) - uri.toString()).apply()
    }

    fun isFavorite(context: Context, songId: String): Boolean =
        prefs(context).getStringSet(FAVORITES, emptySet()).orEmpty().contains(songId)

    fun setFavorite(context: Context, songId: String, favorite: Boolean) {
        val updated = prefs(context).getStringSet(FAVORITES, emptySet()).orEmpty().toMutableSet()
        if (favorite) updated.add(songId) else updated.remove(songId)
        prefs(context).edit().putStringSet(FAVORITES, updated).apply()
    }

    fun cloudflareCoverSession(context: Context): String =
        prefs(context).getString(CLOUDFLARE_COVER_SESSION, "").orEmpty()

    fun setCloudflareCoverSession(context: Context, session: String) {
        prefs(context).edit().putString(CLOUDFLARE_COVER_SESSION, session.trim()).apply()
    }

    fun recentSongIds(context: Context): List<String> = runCatching {
        val stored = JSONArray(prefs(context).getString(HISTORY, "[]"))
        List(stored.length()) { stored.getString(it) }
    }.getOrDefault(emptyList())

    fun rememberPlayed(context: Context, songId: String) {
        val recent = recentSongIds(context).filterNot { it == songId }.toMutableList()
        recent.add(0, songId)
        prefs(context).edit().putString(HISTORY, JSONArray(recent.take(100)).toString()).apply()
    }

    fun playlists(context: Context): Map<String, List<String>> = runCatching {
        val root = JSONObject(prefs(context).getString(PLAYLISTS, "{}") ?: "{}")
        root.keys().asSequence().associateWith { name ->
            val songs = root.getJSONArray(name)
            List(songs.length()) { songs.getString(it) }
        }
    }.getOrDefault(emptyMap())

    fun addToPlaylist(context: Context, name: String, songId: String) {
        val cleaned = name.trim().take(48)
        if (cleaned.isBlank()) return
        val updated = playlists(context).toMutableMap()
        updated[cleaned] = (updated[cleaned].orEmpty() + songId).distinct()
        savePlaylists(context, updated)
    }

    fun deletePlaylist(context: Context, name: String) {
        savePlaylists(context, playlists(context).toMutableMap().apply { remove(name) })
    }

    private fun savePlaylists(context: Context, values: Map<String, List<String>>) {
        val root = JSONObject()
        values.forEach { (name, ids) -> root.put(name, JSONArray(ids)) }
        prefs(context).edit().putString(PLAYLISTS, root.toString()).apply()
    }

    fun sleepTimerEnd(context: Context): Long = prefs(context).getLong(SLEEP_TIMER_END, 0L)
    fun setSleepTimerEnd(context: Context, endTimeMs: Long) = prefs(context).edit().putLong(SLEEP_TIMER_END, endTimeMs).apply()

    fun discordEnabled(context: Context): Boolean = prefs(context).getBoolean(DISCORD_ENABLED, true)
    fun setDiscordEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(DISCORD_ENABLED, enabled).apply()
    fun equalizerPreset(context: Context): String = prefs(context).getString(EQUALIZER_PRESET, "Normal").orEmpty()
    fun setEqualizerPreset(context: Context, preset: String) = prefs(context).edit().putString(EQUALIZER_PRESET, preset).apply()

    fun theme(context: Context) = prefs(context).getString(THEME, SYSTEM) ?: SYSTEM
    fun setTheme(context: Context, value: String) {
        prefs(context).edit().putString(THEME, value).apply()
        applyTheme(context)
    }

    fun applyTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(when (theme(context)) {
            LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
    }
}
