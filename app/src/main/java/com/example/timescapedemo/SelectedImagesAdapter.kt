package com.example.timescapedemo

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

class SelectedImagesAdapter(
    private val onDelete: (BgImage) -> Unit
) : RecyclerView.Adapter<SelectedImagesAdapter.VH>() {

    private val data = mutableListOf<BgImage>()

    fun submit(list: List<BgImage>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_selected_image, parent, false)
        return VH(v as ViewGroup)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = data[position]
        holder.bind(item, onDelete)
    }

    override fun getItemCount(): Int = data.size

    class VH(root: ViewGroup) : RecyclerView.ViewHolder(root) {
        private val thumb: ImageView = root.findViewById(R.id.thumb)
        private val btnDelete: ImageButton = root.findViewById(R.id.btnDelete)

        fun bind(img: BgImage, onDelete: (BgImage) -> Unit) {
            when (img) {
                is BgImage.Res -> thumb.setImageResource(img.id)
                is BgImage.UriRef -> {
                    // basic decode (sufficient for thumbs)
                    val ctx = thumb.context
                    val input = ctx.contentResolver.openInputStream(img.uri)
                    val bmp = input?.use { BitmapFactory.decodeStream(it) }
                    if (bmp != null) thumb.setImageBitmap(bmp) else thumb.setImageDrawable(null)
                }
            }
            btnDelete.setOnClickListener { onDelete(img) }
        }
    }
}
