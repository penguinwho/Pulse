package com.pulse.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.ImageView
import androidx.collection.LruCache
import java.util.concurrent.Executors

/** Loads embedded artwork locally; no network request or media upload is involved. */
object ArtworkLoader {
    private val cache = LruCache<String, Bitmap>(40)
    private val executor = Executors.newFixedThreadPool(2)

    fun load(context: Context, uri: Uri?, target: ImageView) {
        if (uri == null) {
            target.setImageResource(R.drawable.ic_album)
            return
        }
        val key = uri.toString()
        target.tag = key
        cache.get(key)?.let { target.setImageBitmap(it); return }
        target.setImageResource(R.drawable.ic_album)
        executor.execute {
            val bitmap = runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(context, uri)
                    retriever.embeddedPicture?.let { bytes ->
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
            }.getOrNull()
            if (bitmap != null) cache.put(key, bitmap)
            target.post {
                if (target.tag == key && bitmap != null) target.setImageBitmap(bitmap)
            }
        }
    }

    /** Creates a high-quality local 4× bitmap, capped at 2048px to avoid excess memory use. */
    fun upscale(context: Context, uri: Uri?, target: ImageView, onComplete: (Boolean) -> Unit) {
        if (uri == null) { onComplete(false); return }
        val key = uri.toString()
        target.tag = key
        executor.execute {
            val source = cache.get(key) ?: artwork(context, uri)
            val enhanced = source?.let(::scaleUp)
            if (enhanced != null) cache.put(key, enhanced)
            target.post {
                if (target.tag == key && enhanced != null) target.setImageBitmap(enhanced)
                onComplete(enhanced != null)
            }
        }
    }

    private fun artwork(context: Context, uri: Uri): Bitmap? = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
    }.getOrNull()

    private fun scaleUp(source: Bitmap): Bitmap {
        val scale = minOf(4f, 2048f / maxOf(source.width, source.height).toFloat())
        val width = (source.width * scale).toInt().coerceAtLeast(source.width)
        val height = (source.height * scale).toInt().coerceAtLeast(source.height)
        if (width == source.width && height == source.height) return source
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { output ->
            Canvas(output).drawBitmap(source, null, android.graphics.Rect(0, 0, width, height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        }
    }
}
