package com.pulse.player

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
    private lateinit var songAdapter: SongAdapter
    private lateinit var collectionAdapter: CollectionAdapter
    private var allSongs: List<Song> = emptyList()
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var browseMode = BrowseMode.SONGS
    private var sourceFilter = SourceFilter.ALL
    private var selectedPlaylist: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted || AppPreferences.folders(this).isNotEmpty()) loadLibrary() else showPermissionState()
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { loadLibrary() }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updatePlaybackUi()
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlaybackUi()
        override fun onPlaybackStateChanged(playbackState: Int) = updatePlaybackUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPreferences.applyTheme(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        songAdapter = SongAdapter(::playSong, ::toggleFavorite)
        collectionAdapter = CollectionAdapter(::playCollection)
        binding.songs.layoutManager = LinearLayoutManager(this)
        binding.songs.adapter = songAdapter
        binding.search.addTextChangedListener { renderLibrary() }
        binding.grantAccess.setOnClickListener { requestLibraryPermission() }
        binding.settings.setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
        binding.playPause.setOnClickListener { togglePlayback() }
        binding.nowPlayingInfo.setOnClickListener { openPlayer() }
        binding.nowArtwork.setOnClickListener { openPlayer() }
        binding.next.setOnClickListener { controller?.seekToNextMediaItem() }
        binding.previous.setOnClickListener { controller?.seekToPreviousMediaItem() }
        binding.filter.setOnClickListener { showSourceFilter() }
        binding.navSongs.setOnClickListener { selectBrowseMode(BrowseMode.SONGS) }
        binding.navAlbums.setOnClickListener { selectBrowseMode(BrowseMode.ALBUMS) }
        binding.navArtists.setOnClickListener { selectBrowseMode(BrowseMode.ARTISTS) }
        selectBrowseMode(BrowseMode.SONGS)

        connectPlayer()
        if (hasLibraryPermission() || AppPreferences.folders(this).isNotEmpty()) loadLibrary() else showPermissionState()
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

    /** Keeps controls clear of cut-outs, gesture areas, and 3-button navigation. */
    private fun applySystemBarInsets() {
        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.screen_horizontal_padding)
        val topPadding = resources.getDimensionPixelSize(R.dimen.screen_top_padding)
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                horizontalPadding + bars.left,
                topPadding + bars.top,
                horizontalPadding + bars.right,
                bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.mainRoot)
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
            val result = MediaStoreRepository(applicationContext).songs(
                includeMediaStore = hasLibraryPermission(),
                folders = AppPreferences.folderUris(this)
            )
            runOnUiThread {
                allSongs = result
                renderLibrary()
                if (result.isEmpty()) showEmptyLibraryState()
            }
        }.start()
    }

    private fun visibleSongs(): List<Song> {
        val needle = binding.search.text?.toString().orEmpty().trim()
        val playlistIds = selectedPlaylist?.let { AppPreferences.playlists(this)[it].orEmpty() }
        val recentIds = AppPreferences.recentSongIds(this)
        val filtered = allSongs.filter { song ->
            val sourceMatches = when (sourceFilter) {
                SourceFilter.ALL -> true
                SourceFilter.DEVICE -> !song.fromAddedFolder
                SourceFilter.FOLDERS -> song.fromAddedFolder
                SourceFilter.FAVORITES -> AppPreferences.isFavorite(this, song.id)
                SourceFilter.RECENT -> recentIds.contains(song.id)
                SourceFilter.PLAYLIST -> playlistIds?.contains(song.id) == true
            }
            sourceMatches && (needle.isBlank() ||
            song.title.contains(needle, true) || song.artist.contains(needle, true) || song.album.contains(needle, true) ||
                (song.fromAddedFolder && "added folder".contains(needle, true))
            )
        }
        return when (sourceFilter) {
            SourceFilter.RECENT -> filtered.sortedBy { recentIds.indexOf(it.id) }
            SourceFilter.PLAYLIST -> filtered.sortedBy { playlistIds?.indexOf(it.id) ?: Int.MAX_VALUE }
            else -> filtered
        }
    }

    private fun renderLibrary() {
        val visible = visibleSongs()
        val currentId = controller?.currentMediaItem?.mediaId
        val label = when (browseMode) {
            BrowseMode.SONGS -> "SONGS"
            BrowseMode.ALBUMS -> "ALBUMS"
            BrowseMode.ARTISTS -> "ARTISTS"
        }
        when (browseMode) {
            BrowseMode.SONGS -> {
                binding.songs.adapter = songAdapter
                songAdapter.submit(visible, currentId)
                binding.songsLabel.text = "$label · ${visible.size}"
            }
            BrowseMode.ALBUMS, BrowseMode.ARTISTS -> {
                val grouped = if (browseMode == BrowseMode.ALBUMS) {
                    visible.groupBy { it.album.ifBlank { "Unknown album" } }
                } else {
                    visible.groupBy { it.artist.ifBlank { "Unknown artist" } }
                }
                val collections = grouped.map { (name, songs) ->
                    val subtext = if (browseMode == BrowseMode.ALBUMS) {
                        "${songs.size} song${if (songs.size == 1) "" else "s"} · ${songs.first().artist}"
                    } else "${songs.size} song${if (songs.size == 1) "" else "s"}"
                    MusicCollection(name, subtext, songs)
                }.sortedBy { it.title.lowercase() }
                binding.songs.adapter = collectionAdapter
                collectionAdapter.submit(collections)
                binding.songsLabel.text = "$label · ${collections.size}"
            }
        }
        binding.emptyState.visibility = if (allSongs.isNotEmpty() && visible.isEmpty()) View.VISIBLE else View.GONE
        if (allSongs.isNotEmpty() && visible.isEmpty()) {
            binding.emptyTitle.text = "No matches"
            binding.emptyText.text = "Try another search or change the source filter."
            binding.grantAccess.visibility = View.GONE
        }
    }

    private fun showSourceFilter() {
        PopupMenu(this, binding.filter).apply {
            menu.add(0, 1, 0, "All music")
            menu.add(0, 2, 1, "Device library")
            menu.add(0, 3, 2, "Added folders")
            menu.add(0, 4, 3, "Favorites")
            menu.add(0, 5, 4, "Recently played")
            val playlists = AppPreferences.playlists(this@MainActivity)
            if (playlists.isNotEmpty()) {
                menu.add("Playlists")
                playlists.keys.sorted().forEachIndexed { index, name ->
                    menu.add(0, 100 + index, 10 + index, name)
                }
            }
            setOnMenuItemClickListener { item ->
                sourceFilter = when (item.itemId) {
                    2 -> SourceFilter.DEVICE
                    3 -> SourceFilter.FOLDERS
                    4 -> SourceFilter.FAVORITES
                    5 -> SourceFilter.RECENT
                    in 100..999 -> SourceFilter.PLAYLIST
                    else -> SourceFilter.ALL
                }
                selectedPlaylist = if (sourceFilter == SourceFilter.PLAYLIST) item.title.toString() else null
                binding.filter.contentDescription = "Filter: ${item.title}"
                renderLibrary()
                true
            }
            show()
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
        val queue = visibleSongs()
        val index = queue.indexOfFirst { it.id == song.id }
        if (index < 0) return
        activeController.setMediaItems(queue.map(Song::asMediaItem), index, 0)
        activeController.prepare()
        activeController.play()
        updatePlaybackUi()
    }

    private fun playCollection(collection: MusicCollection) {
        val activeController = controller ?: run {
            Toast.makeText(this, "Player is starting…", Toast.LENGTH_SHORT).show()
            return
        }
        activeController.setMediaItems(collection.songs.map(Song::asMediaItem), 0, 0)
        activeController.prepare()
        activeController.play()
        openPlayer()
    }

    private fun togglePlayback() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    private fun toggleFavorite(song: Song) {
        AppPreferences.setFavorite(this, song.id, !AppPreferences.isFavorite(this, song.id))
        renderLibrary()
    }

    private fun selectBrowseMode(mode: BrowseMode) {
        browseMode = mode
        binding.navSongs.isSelected = mode == BrowseMode.SONGS
        binding.navAlbums.isSelected = mode == BrowseMode.ALBUMS
        binding.navArtists.isSelected = mode == BrowseMode.ARTISTS
        renderLibrary()
    }

    private fun openPlayer() = startActivity(Intent(this, PlayerActivity::class.java))

    private fun updatePlaybackUi() {
        val activeController = controller ?: return
        val media = activeController.currentMediaItem?.mediaMetadata
        binding.nowTitle.text = media?.title?.toString() ?: "Nothing playing"
        binding.nowArtist.text = media?.artist?.toString() ?: "Choose a song from your library"
        ArtworkLoader.load(this, activeController.currentMediaItem?.localConfiguration?.uri, binding.nowArtwork)
        binding.playPause.setImageResource(
            if (activeController.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        binding.playPause.contentDescription = if (activeController.isPlaying) "Pause" else "Play"
        renderLibrary()
    }

    override fun onDestroy() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }
}

private enum class BrowseMode { SONGS, ALBUMS, ARTISTS }
private enum class SourceFilter { ALL, DEVICE, FOLDERS, FAVORITES, RECENT, PLAYLIST }
