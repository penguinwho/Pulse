package com.pulse.player

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.MotionEvent
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
        applySystemBarInsets()
        binding.close.setOnClickListener { finish() }
        binding.previous.setOnClickListener { controller?.seekToPreviousMediaItem() }
        binding.next.setOnClickListener { controller?.seekToNextMediaItem() }
        binding.playPause.setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        binding.shuffle.setOnClickListener { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
        binding.repeat.setOnClickListener { cycleRepeatMode() }
        binding.upscaleCover.setOnClickListener { upscaleCover() }
        binding.queue.setOnClickListener { showQueue() }
        binding.addToPlaylist.setOnClickListener { addCurrentSongToPlaylist() }
        binding.sleepTimer.setOnClickListener { showSleepTimer() }
        binding.shareSong.setOnClickListener { shareCurrentSong() }
        binding.lyrics.setOnClickListener { showLyrics() }
        binding.equalizer.setOnClickListener { showEqualizer() }
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

    private fun applySystemBarInsets() {
        val padding = resources.getDimensionPixelSize(R.dimen.screen_horizontal_padding)
        ViewCompat.setOnApplyWindowInsetsListener(binding.playerRoot) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.playerRoot)
    }

    private fun upscaleCover() {
        val uri = controller?.currentMediaItem?.localConfiguration?.uri
        if (uri == null) {
            Toast.makeText(this, "Play a song first", Toast.LENGTH_SHORT).show()
            return
        }
        binding.upscaleCover.isEnabled = false
        binding.upscaleCover.text = "Upscaling cover…"
        ArtworkLoader.upscale(this, uri, binding.artwork) { success ->
            binding.upscaleCover.isEnabled = true
            binding.upscaleCover.text = if (success) "Cover upscaled" else "No embedded cover to upscale"
        }
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
        val title = controller?.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
        if (title.isBlank()) return
        binding.lyrics.isEnabled = false
        Thread {
            val text = LyricsLoader.load(applicationContext, title)
            runOnUiThread {
                binding.lyrics.isEnabled = true
                AlertDialog.Builder(this)
                    .setTitle("Lyrics")
                    .setMessage(text ?: "No .lrc file named \"$title.lrc\" was found in your added music folders.")
                    .setPositiveButton("Close", null)
                    .show()
            }
        }.start()
    }

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
