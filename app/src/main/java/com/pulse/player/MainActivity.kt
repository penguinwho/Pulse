package com.pulse.player

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.common.util.concurrent.ListenableFuture
import com.pulse.player.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SongAdapter
    private var allSongs: List<Song> = emptyList()
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadLibrary() else showPermissionState()
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updatePlaybackUi()
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlaybackUi()
        override fun onPlaybackStateChanged(playbackState: Int) = updatePlaybackUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = SongAdapter(::playSong)
        binding.songs.layoutManager = LinearLayoutManager(this)
        binding.songs.adapter = adapter
        binding.search.addTextChangedListener { filter(it?.toString().orEmpty()) }
        binding.grantAccess.setOnClickListener { requestLibraryPermission() }
        binding.playPause.setOnClickListener { togglePlayback() }
        binding.next.setOnClickListener { controller?.seekToNextMediaItem() }
        binding.previous.setOnClickListener { controller?.seekToPreviousMediaItem() }

        connectPlayer()
        if (hasLibraryPermission()) loadLibrary() else showPermissionState()
    }

    private fun connectPlayer() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({
                if (isFinishing || isDestroyed) return@addListener
                controller = future.get().also { it.addListener(playerListener) }
                updatePlaybackUi()
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun hasLibraryPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestLibraryPermission() {
        permissionLauncher.launch(
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }

    private fun loadLibrary() {
        binding.emptyState.visibility = View.GONE
        Thread {
            val result = MediaStoreRepository(applicationContext).songs()
            runOnUiThread {
                allSongs = result
                filter(binding.search.text?.toString().orEmpty())
                if (result.isEmpty()) showEmptyLibraryState()
            }
        }.start()
    }

    private fun filter(query: String) {
        val needle = query.trim()
        val visible = if (needle.isBlank()) allSongs else allSongs.filter { song ->
            song.title.contains(needle, true) || song.artist.contains(needle, true) || song.album.contains(needle, true)
        }
        val currentId = controller?.currentMediaItem?.mediaId?.toLongOrNull()
        adapter.submit(visible, currentId)
        binding.songsLabel.text = if (needle.isBlank()) "SONGS · ${allSongs.size}" else "RESULTS · ${visible.size}"
        binding.emptyState.visibility = if (allSongs.isNotEmpty() && visible.isEmpty()) View.VISIBLE else View.GONE
        if (allSongs.isNotEmpty() && visible.isEmpty()) {
            binding.emptyTitle.text = "No matches"
            binding.emptyText.text = "Try a title, artist, or album."
            binding.grantAccess.visibility = View.GONE
        }
    }

    private fun showPermissionState() {
        binding.emptyState.visibility = View.VISIBLE
        binding.emptyTitle.text = "Your library stays yours"
        binding.emptyText.text = "Allow audio access so Pulse can find music already stored on this device."
        binding.grantAccess.visibility = View.VISIBLE
        binding.songsLabel.text = "SONGS"
    }

    private fun showEmptyLibraryState() {
        binding.emptyState.visibility = View.VISIBLE
        binding.emptyTitle.text = "No music on this device"
        binding.emptyText.text = "Add MP3s or other audio files, then reopen Pulse."
        binding.grantAccess.visibility = View.GONE
    }

    private fun playSong(song: Song) {
        val activeController = controller
        if (activeController == null) {
            Toast.makeText(this, "Player is starting…", Toast.LENGTH_SHORT).show()
            return
        }
        val index = allSongs.indexOfFirst { it.id == song.id }
        if (index < 0) return
        activeController.setMediaItems(allSongs.map(Song::asMediaItem), index, 0)
        activeController.prepare()
        activeController.play()
        updatePlaybackUi()
    }

    private fun togglePlayback() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    private fun updatePlaybackUi() {
        val activeController = controller ?: return
        val media = activeController.currentMediaItem?.mediaMetadata
        binding.nowTitle.text = media?.title?.toString() ?: "Nothing playing"
        binding.nowArtist.text = media?.artist?.toString() ?: "Choose a song from your library"
        binding.playPause.setImageResource(
            if (activeController.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        binding.playPause.contentDescription = if (activeController.isPlaying) "Pause" else "Play"
        adapter.submit(
            if (binding.search.text.isNullOrBlank()) allSongs else allSongs.filter { song ->
                val q = binding.search.text.toString()
                song.title.contains(q, true) || song.artist.contains(q, true) || song.album.contains(q, true)
            },
            activeController.currentMediaItem?.mediaId?.toLongOrNull()
        )
    }

    override fun onDestroy() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }
}
