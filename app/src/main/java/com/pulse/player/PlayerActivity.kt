package com.pulse.player

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.MotionEvent
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPreferences.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.close.setOnClickListener { finish() }
        binding.previous.setOnClickListener { controller?.seekToPreviousMediaItem() }
        binding.next.setOnClickListener { controller?.seekToNextMediaItem() }
        binding.playPause.setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        binding.upscaleCover.setOnClickListener { upscaleCover() }
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
        ArtworkLoader.load(this, current.currentMediaItem?.localConfiguration?.uri, binding.artwork)
    }

    private fun clock(ms: Long) = DateUtils.formatElapsedTime(ms / 1_000)

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

    override fun onDestroy() {
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}
