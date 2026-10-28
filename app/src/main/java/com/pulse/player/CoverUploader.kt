package com.pulse.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Uploads only embedded artwork, never audio files, to the user's configured Cloudflare Worker. */
object CoverUploader {
    private const val tag = "CoverUploader"
    private const val workerEndpoint = "https://pulse-cover-upload.penguinvok.workers.dev/covers"
    private const val maxCoverBytes = 4 * 1024 * 1024
    private const val cacheName = "uploaded_cover_urls"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun upload(context: Context, uri: Uri?, onComplete: (String?) -> Unit) {
        val session = AppPreferences.cloudflareCoverSession(context)
        if (uri == null || session.isBlank()) {
            deliver(onComplete, null)
            return
        }
        ArtworkLoader.embeddedArtwork(context, uri) { bytes, mimeType ->
            if (bytes == null || mimeType == null || bytes.size > maxCoverBytes) {
                deliver(onComplete, null)
                return@embeddedArtwork
            }
            val cacheKey = sha256(bytes)
            val cached = context.getSharedPreferences(cacheName, Context.MODE_PRIVATE)
                .getString(cacheKey, null)
            if (cached != null) {
                deliver(onComplete, cached)
                return@embeddedArtwork
            }
            executor.execute {
                val uploaded = uploadBytes(bytes, mimeType, session)
                if (uploaded != null) {
                    context.getSharedPreferences(cacheName, Context.MODE_PRIVATE)
                        .edit().putString(cacheKey, uploaded).apply()
                }
                deliver(onComplete, uploaded)
            }
        }
    }

    private fun uploadBytes(bytes: ByteArray, mimeType: String, session: String): String? = runCatching {
        val connection = (URL(workerEndpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $session")
            setRequestProperty("Content-Type", mimeType)
            setFixedLengthStreamingMode(bytes.size)
        }
        BufferedOutputStream(connection.outputStream).use { it.write(bytes) }
        if (connection.responseCode !in 200..299) {
            Log.w(tag, "Cover upload failed with HTTP ${connection.responseCode}")
            null
        } else {
            connection.inputStream.bufferedReader().use { reader ->
                JSONObject(reader.readText()).optString("url").takeIf { it.startsWith("https://") }
            }
        }
    }.onFailure { Log.w(tag, "Cover upload failed", it) }.getOrNull()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun deliver(onComplete: (String?) -> Unit, url: String?) {
        mainHandler.post { onComplete(url) }
    }
}
