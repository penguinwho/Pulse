package com.pulse.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.media.audiofx.Equalizer

class PlaybackService : MediaSessionService() {
    companion object {
        const val ACTION_SET_SLEEP_TIMER = "com.pulse.player.SET_SLEEP_TIMER"
        const val EXTRA_SLEEP_TIMER_END = "sleep_timer_end"
        const val ACTION_SET_EQUALIZER = "com.pulse.player.SET_EQUALIZER"
        const val EXTRA_EQUALIZER_PRESET = "equalizer_preset"
    }

    private var session: MediaSession? = null
    private val sleepTimerHandler = Handler(Looper.getMainLooper())
    private var equalizer: Equalizer? = null
    private var exoPlayer: ExoPlayer? = null
    private val stopForSleepTimer = Runnable {
        session?.player?.pause()
        AppPreferences.setSleepTimerEnd(this, 0L)
    }

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                updateDiscordPresence(player)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateDiscordPresence(player)
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                applyEqualizer(audioSessionId, AppPreferences.equalizerPreset(this@PlaybackService))
            }
        })
        exoPlayer = player
        session = MediaSession.Builder(this, player).build()
        scheduleSleepTimer(AppPreferences.sleepTimerEnd(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SET_SLEEP_TIMER) {
            scheduleSleepTimer(intent.getLongExtra(EXTRA_SLEEP_TIMER_END, 0L))
        }
        if (intent?.action == ACTION_SET_EQUALIZER) {
            val preset = intent.getStringExtra(EXTRA_EQUALIZER_PRESET) ?: "Normal"
            AppPreferences.setEqualizerPreset(this, preset)
            applyEqualizer(exoPlayer?.audioSessionId ?: 0, preset)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = session?.player
        if (player != null && !player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        sleepTimerHandler.removeCallbacks(stopForSleepTimer)
        equalizer?.release()
        equalizer = null
        DiscordPresence.clear(this)
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    private fun updateDiscordPresence(player: Player) {
        val metadata = player.currentMediaItem?.mediaMetadata
        val mediaId = player.currentMediaItem?.mediaId
        DiscordPresence.update(
            context = this,
            title = metadata?.title?.toString(),
            artist = metadata?.artist?.toString(),
            playing = player.isPlaying
        )
        mediaId?.let { AppPreferences.rememberPlayed(this, it) }
        if (player.isPlaying && mediaId != null) {
            CoverUploader.upload(this, player.currentMediaItem?.localConfiguration?.uri) { coverUrl ->
                if (coverUrl != null && player.isPlaying && player.currentMediaItem?.mediaId == mediaId) {
                    DiscordPresence.update(
                        context = this,
                        title = player.currentMediaItem?.mediaMetadata?.title?.toString(),
                        artist = player.currentMediaItem?.mediaMetadata?.artist?.toString(),
                        playing = true,
                        coverUrl = coverUrl
                    )
                }
            }
        }
    }

    private fun scheduleSleepTimer(endTimeMs: Long) {
        sleepTimerHandler.removeCallbacks(stopForSleepTimer)
        if (endTimeMs <= System.currentTimeMillis()) {
            if (endTimeMs > 0L) stopForSleepTimer.run()
            return
        }
        sleepTimerHandler.postDelayed(stopForSleepTimer, endTimeMs - System.currentTimeMillis())
    }

    private fun applyEqualizer(audioSessionId: Int, preset: String) {
        if (audioSessionId <= 0) return
        runCatching {
            equalizer?.release()
            equalizer = null
            if (preset == "Normal") return
            Equalizer(0, audioSessionId).also { effect ->
                effect.enabled = true
                val range = effect.bandLevelRange
                val low = range[1]
                when (preset) {
                    "Bass boost" -> for (band in 0 until effect.numberOfBands) {
                        val frequency = effect.getCenterFreq(band.toShort())
                        effect.setBandLevel(band.toShort(), if (frequency < 250_000) low else 0)
                    }
                    "Vocal" -> for (band in 0 until effect.numberOfBands) {
                        val frequency = effect.getCenterFreq(band.toShort())
                        effect.setBandLevel(band.toShort(), if (frequency in 500_000..4_000_000) low else 0)
                    }
                }
                equalizer = effect
            }
        }
    }
}
