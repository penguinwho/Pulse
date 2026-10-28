package com.pulse.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

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
        })
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = session?.player
        if (player != null && !player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
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
}
