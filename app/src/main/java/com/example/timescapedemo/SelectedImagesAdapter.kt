package com.example.timescapedemo

import android.graphics.BitmapFactory
import android.util.Log
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
                    val bmp = try {
                        ctx.contentResolver.openInputStream(img.uri)?.use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    } catch (se: SecurityException) {
                        Log.w(TAG, "Missing permission for uri thumb: ${img.uri}", se)
                        null
                    } catch (fnf: java.io.FileNotFoundException) {
                        Log.w(TAG, "Missing file for uri thumb: ${img.uri}", fnf)
                        null
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to decode uri thumb: ${img.uri}", e)
                        null
                    }
                    if (bmp != null) {
                        thumb.setImageBitmap(bmp)
                    } else {
                        thumb.setImageResource(R.drawable.bg_placeholder)
                    }
                }
            }
            btnDelete.setOnClickListener { onDelete(img) }
        }

        private companion object {
            private const val TAG = "SelectedImagesAdapter"
        }
    }
}
