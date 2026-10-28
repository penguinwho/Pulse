package com.pulse.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pulse.player.databinding.ItemCollectionBinding

data class MusicCollection(val title: String, val subtitle: String, val songs: List<Song>)

class CollectionAdapter(private val onSelected: (MusicCollection) -> Unit) : RecyclerView.Adapter<CollectionAdapter.Holder>() {
    private var collections: List<MusicCollection> = emptyList()

    fun submit(items: List<MusicCollection>) { collections = items; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemCollectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
    override fun getItemCount() = collections.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(collections[position])

    inner class Holder(private val binding: ItemCollectionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(collection: MusicCollection) {
            binding.collectionTitle.text = collection.title
            binding.collectionDetails.text = collection.subtitle
            ArtworkLoader.load(binding.root.context, collection.songs.firstOrNull()?.uri, binding.collectionArtwork)
            binding.collectionRow.setOnClickListener { onSelected(collection) }
        }
    }
}
