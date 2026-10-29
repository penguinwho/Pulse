package com.pulse.player

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Color
import android.view.GestureDetector
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.ClickableSpan
import android.text.method.LinkMovementMethod
import android.text.TextPaint
import android.graphics.Typeface
import android.text.format.DateUtils
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.pulse.player.databinding.ActivityPlayerBinding

class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var dragging = false
    private var lyricsForTitle: String? = null
    private var timedLyrics: List<TimedLyric> = emptyList()
    private var plainLyrics: String? = null
    private var visibleLyricIndex = -1
    private val playerGestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true

            override fun onFling(
                start: MotionEvent?, end: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                val first = start ?: return false
                val deltaX = end.x - first.x
                val deltaY = end.y - first.y
                val threshold = 64 * resources.displayMetrics.density
                when {
                    deltaY > threshold && kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX) -> {
                        closePlayer()
                        return true
                    }
                    kotlin.math.abs(deltaX) > threshold && kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) -> {
                        if (deltaX < 0) controller?.seekToNextMediaItem() else controller?.seekToPreviousMediaItem()
                        return true
                    }
                }
                return false
            }
        })
    }
    private val ticker = object : Runnable {
        override fun run() { updateUi(); handler.postDelayed(this, 500) }
    }
    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = updateUi()
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateUi()
        override fun onPlaybackStateChanged(playbackState: Int) = updateUi()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = updateUi()
        override fun onRepeatModeChanged(repeatMode: Int) = updateUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPreferences.applyTheme(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.backgroundArtwork.setRenderEffect(RenderEffect.createBlurEffect(38f, 38f, Shader.TileMode.CLAMP))
        }
        PulseAppearance.applyWindow(this)
        PulseAppearance.applyPlayerScrim(this, binding.playerScrim, binding.lyrics)
        PulseAppearance.enter(binding.playerContent, distanceDp = 12f)
        applySystemBarInsets()
        binding.close.setOnClickListener { closePlayer() }
        binding.previous.setOnClickListener { controller?.seekToPreviousMediaItem() }
        binding.next.setOnClickListener { controller?.seekToNextMediaItem() }
        binding.playPause.setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        binding.shuffle.setOnClickListener { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
        binding.repeat.setOnClickListener { cycleRepeatMode() }
        binding.lyrics.setOnClickListener { showLyrics() }
        binding.shareSong.setOnClickListener { shareCurrentSong() }
        binding.inlineLyrics.movementMethod = LinkMovementMethod.getInstance()
        binding.inlineLyrics.highlightColor = Color.TRANSPARENT
        binding.artworkContainer.setOnTouchListener { _, event ->
            playerGestures.onTouchEvent(event)
            true
        }
        binding.progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.currentTime.text = clock(progress.toLong())
            }
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                controller?.seekTo(bar.progress.toLong())
                dragging = false
            }
        })
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({
                if (isFinishing || isDestroyed) return@addListener
                controller = future.get().also { it.addListener(listener) }
                updateUi()
            }, ContextCompat.getMainExecutor(this))
        }
    }

    override fun onStart() { super.onStart(); handler.post(ticker) }
    override fun onStop() { handler.removeCallbacks(ticker); super.onStop() }

    private fun updateUi() {
        val current = controller ?: return
        val metadata = current.currentMediaItem?.mediaMetadata
        binding.songTitle.text = metadata?.title?.toString() ?: "Nothing playing"
        binding.songArtist.text = metadata?.artist?.toString() ?: "Choose a song from your library"
        val duration = current.duration.coerceAtLeast(0)
        binding.totalTime.text = clock(duration)
        if (!dragging) {
            binding.progress.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            binding.progress.progress = current.currentPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            binding.currentTime.text = clock(current.currentPosition)
        }
        binding.playPause.setImageResource(if (current.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        binding.shuffle.isSelected = current.shuffleModeEnabled
        binding.shuffle.contentDescription = if (current.shuffleModeEnabled) "Shuffle on" else "Shuffle off"
        binding.repeat.isSelected = current.repeatMode != Player.REPEAT_MODE_OFF
        binding.repeat.setImageResource(
            if (current.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat
        )
        binding.repeat.contentDescription = when (current.repeatMode) {
            Player.REPEAT_MODE_ONE -> "Repeat one"
            Player.REPEAT_MODE_ALL -> "Repeat all"
            else -> "Repeat off"
        }
        ArtworkLoader.load(this, current.currentMediaItem?.localConfiguration?.uri, binding.artwork)
        ArtworkLoader.load(this, current.currentMediaItem?.localConfiguration?.uri, binding.backgroundArtwork)
        val activeTitle = metadata?.title?.toString()
        if (lyricsForTitle != null && lyricsForTitle != activeTitle) {
            lyricsForTitle = null
            timedLyrics = emptyList()
            plainLyrics = null
            visibleLyricIndex = -1
            binding.inlineLyrics.text = ""
            binding.lyricsPanel.visibility = View.GONE
            binding.artworkContainer.visibility = View.VISIBLE
        }
        if (binding.lyricsPanel.visibility == View.VISIBLE && lyricsForTitle == activeTitle) {
            renderLyrics(current.currentPosition)
        }
    }

    private fun clock(ms: Long) = DateUtils.formatElapsedTime(ms / 1_000)

    private fun cycleRepeatMode() {
        controller?.let { player ->
            player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    private fun closePlayer() {
        finish()
        overridePendingTransition(R.anim.hold, R.anim.slide_down)
    }

    private fun applySystemBarInsets() {
        val padding = resources.getDimensionPixelSize(R.dimen.screen_horizontal_padding)
        ViewCompat.setOnApplyWindowInsetsListener(binding.playerContent) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.playerRoot)
    }

    private fun showQueue() {
        val player = controller ?: return
        if (player.mediaItemCount == 0) {
            Toast.makeText(this, "Your queue is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val entries = List(player.mediaItemCount) { index ->
            val item = player.getMediaItemAt(index)
            val prefix = if (index == player.currentMediaItemIndex) "Now playing: " else ""
            "$prefix${item.mediaMetadata.title ?: "Untitled"}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Up next")
            .setItems(entries) { _, index -> player.seekToDefaultPosition(index); player.play() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun addCurrentSongToPlaylist() {
        val item = controller?.currentMediaItem ?: return
        val existing = AppPreferences.playlists(this).keys.sorted()
        val choices = existing + "New playlist"
        AlertDialog.Builder(this)
            .setTitle("Add to playlist")
            .setItems(choices.toTypedArray()) { _, index ->
                if (index == existing.size) promptForPlaylist(item.mediaId)
                else {
                    AppPreferences.addToPlaylist(this, existing[index], item.mediaId)
                    Toast.makeText(this, "Added to ${existing[index]}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptForPlaylist(songId: String) {
        val input = EditText(this).apply { hint = "Playlist name"; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("New playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                AppPreferences.addToPlaylist(this, name, songId)
                Toast.makeText(this, "Added to $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSleepTimer() {
        val options = arrayOf("Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes")
        val minutes = longArrayOf(0, 15, 30, 45, 60)
        AlertDialog.Builder(this)
            .setTitle("Sleep timer")
            .setItems(options) { _, index ->
                val end = if (minutes[index] == 0L) 0L else System.currentTimeMillis() + minutes[index] * 60_000L
                AppPreferences.setSleepTimerEnd(this, end)
                startService(Intent(this, PlaybackService::class.java).apply {
                    action = PlaybackService.ACTION_SET_SLEEP_TIMER
                    putExtra(PlaybackService.EXTRA_SLEEP_TIMER_END, end)
                })
                Toast.makeText(this, if (end == 0L) "Sleep timer off" else "Playback stops in ${minutes[index]} minutes", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun shareCurrentSong() {
        val metadata = controller?.currentMediaItem?.mediaMetadata ?: return
        val title = metadata.title?.toString().orEmpty()
        val artist = metadata.artist?.toString().orEmpty()
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Listening to $title${artist.takeIf { it.isNotBlank() }?.let { " by $it" }.orEmpty()} on Pulse")
        }, "Share song"))
    }

    private fun showLyrics() {
        val player = controller ?: return
        val metadata = player.currentMediaItem?.mediaMetadata
        val title = metadata?.title?.toString().orEmpty()
        if (title.isBlank()) return
        if (binding.lyricsPanel.visibility == View.VISIBLE && lyricsForTitle == title) {
            binding.lyricsPanel.visibility = View.GONE
            binding.artworkContainer.visibility = View.VISIBLE
            return
        }
        binding.lyrics.isEnabled = false
        binding.inlineLyrics.text = "Loading lyrics…"
        binding.artworkContainer.visibility = View.GONE
        binding.lyricsPanel.visibility = View.VISIBLE
        val artist = metadata?.artist?.toString().orEmpty()
        val album = metadata?.albumTitle?.toString().orEmpty()
        val duration = player.duration.coerceAtLeast(0)
        Thread {
            val text = runCatching {
                LyricsLoader.load(applicationContext, title, artist, album, duration)
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.lyrics.isEnabled = true
                lyricsForTitle = title
                timedLyrics = text?.let(::parseTimedLyrics).orEmpty()
                plainLyrics = if (timedLyrics.isEmpty()) text?.let(::displayLyrics) else null
                visibleLyricIndex = -1
                renderLyrics(player.currentPosition)
            }
        }.start()
    }

    private fun renderLyrics(positionMs: Long) {
        val lines = timedLyrics
        if (lines.isEmpty()) {
            binding.inlineLyrics.text = plainLyrics ?: "No local .lrc file or LRCLIB lyrics were found for this song."
            return
        }
        val current = lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)
        val styled = SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            val start = styled.length
            styled.append(line.text)
            val end = styled.length
            val distance = kotlin.math.abs(index - current)
            val active = index == current
            val color = when {
                active -> 0xFFFFFFFF.toInt()
                distance == 1 -> 0xA6FFFFFF.toInt()
                distance == 2 -> 0x78FFFFFF.toInt()
                else -> 0x42FFFFFF.toInt()
            }
            styled.setSpan(
                ForegroundColorSpan(color),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            styled.setSpan(RelativeSizeSpan(if (active) 1.13f else 0.91f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (active) styled.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            styled.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    controller?.seekTo(line.timeMs)
                    visibleLyricIndex = -1
                    renderLyrics(line.timeMs)
                }

                override fun updateDrawState(drawState: TextPaint) {
                    drawState.isUnderlineText = false
                    drawState.color = color
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            styled.append("\n\n")
        }
        binding.inlineLyrics.text = styled
        if (current != visibleLyricIndex) {
            visibleLyricIndex = current
            binding.lyricsPanel.post {
                val lineHeight = binding.inlineLyrics.lineHeight.coerceAtLeast(1)
                val target = (lineHeight * current * 3 - binding.lyricsPanel.height / 2).coerceAtLeast(0)
                binding.lyricsPanel.smoothScrollTo(0, target)
            }
        }
    }

    private fun parseTimedLyrics(rawLyrics: String): List<TimedLyric> {
        val timestamp = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?]")
        return rawLyrics.lineSequence().flatMap { line ->
            val text = line.replace(timestamp, "").trim()
            timestamp.findAll(line).mapNotNull { match ->
                if (text.isBlank()) null else {
                    val minutes = match.groupValues[1].toLong()
                    val seconds = match.groupValues[2].toLong()
                    val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                    TimedLyric((minutes * 60 + seconds) * 1_000 + fraction, text)
                }
            }
        }.sortedBy { it.timeMs }.toList()
    }

    private fun displayLyrics(rawLyrics: String): String = rawLyrics
        .lineSequence()
        .map { line -> line.replace(Regex("\\[\\d{1,2}:\\d{2}(?:\\.\\d{1,3})?]"), "").trim() }
        .filter(String::isNotBlank)
        .joinToString("\n\n")

    private data class TimedLyric(val timeMs: Long, val text: String)

    private fun showEqualizer() {
        val presets = arrayOf("Normal", "Bass boost", "Vocal")
        AlertDialog.Builder(this)
            .setTitle("Sound preset")
            .setSingleChoiceItems(presets, presets.indexOf(AppPreferences.equalizerPreset(this))) { dialog, index ->
                val preset = presets[index]
                AppPreferences.setEqualizerPreset(this, preset)
                startService(Intent(this, PlaybackService::class.java).apply {
                    action = PlaybackService.ACTION_SET_EQUALIZER
                    putExtra(PlaybackService.EXTRA_EQUALIZER_PRESET, preset)
                })
                Toast.makeText(this, "$preset enabled", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .show()
    }

    override fun onDestroy() {
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}
