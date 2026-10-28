package com.pulse.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pulse.player.databinding.ItemSongBinding
import java.util.Locale

class SongAdapter(private val onSelected: (Song) -> Unit) : RecyclerView.Adapter<SongAdapter.Holder>() {
    private var songs: List<Song> = emptyList()
    private var activeId: String? = null

    fun submit(items: List<Song>, playingId: String?) {
        songs = items
        activeId = playingId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
    override fun getItemCount() = songs.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(songs[position])

    inner class Holder(private val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(song: Song) {
            ArtworkLoader.load(binding.root.context, song.uri, binding.songArtwork)
            binding.songTitle.text = song.title
            binding.songDetails.text = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · ")
            binding.songDuration.text = if (song.durationMs > 0) formatDuration(song.durationMs) else "Folder"
            binding.songTitle.setTextColor(binding.root.context.getColor(
                if (song.id == activeId) R.color.pulse_lilac else R.color.pulse_ink
            ))
            binding.songRow.setOnClickListener { onSelected(song) }
        }
    }

    private fun formatDuration(milliseconds: Long): String {
        val seconds = milliseconds / 1_000
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
    }
}
