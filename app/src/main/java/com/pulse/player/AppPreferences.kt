package com.pulse.player

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate

object AppPreferences {
    private const val FILE = "pulse_settings"
    private const val FOLDERS = "music_folders"
    private const val FAVORITES = "favorite_songs"
    private const val CLOUDFLARE_COVER_SESSION = "cloudflare_cover_session"
    private const val THEME = "theme"
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
