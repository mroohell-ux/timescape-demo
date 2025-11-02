package com.example.timescapedemo

import android.graphics.*
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Shader.TileMode
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.min

data class CardItem(
    val title: String,
    val snippet: String,
    val time: String,
    val imageRes: Int? = null
)

class CardsAdapter(
    private val items: List<CardItem>,
    private val onItemClick: (index: Int) -> Unit
) : RecyclerView.Adapter<CardsAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.time)
        val title: TextView = v.findViewById(R.id.title)
        val snippet: TextView = v.findViewById(R.id.snippet)
        val bg: ImageView = v.findViewById(R.id.bgImage)
        val textScrim: View = v.findViewById(R.id.textScrim)
    }

    private val luminanceCache = mutableMapOf<Int, Float>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        // Text
        holder.time.text = item.time
        holder.title.text = item.title
        holder.snippet.text = item.snippet

        // Background
        if (item.imageRes != null) holder.bg.setImageResource(item.imageRes) else holder.bg.setImageDrawable(null)

        // 1) Blur the image (keeps card transparent; no overlays)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            holder.bg.setRenderEffect(RenderEffect.createBlurEffect(26f, 26f, TileMode.CLAMP))
        }

        // 2) Mild vibrance compression on the IMAGE ITSELF (no white tint)
        //    - slightly desaturate
        //    - tiny contrast/brightness trim
        val cm = ColorMatrix().apply {
            setSaturation(0.78f) // keep color, just reduce vibrance a touch
        }
        val contrast = 0.96f   // 1 = same; <1 lowers contrast slightly
        val translate = 0.02f  // small lift to avoid looking muddy after blur (0..1)
        val adjust = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, 255f * translate,
                0f, contrast, 0f, 0f, 255f * translate,
                0f, 0f, contrast, 0f, 255f * translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(adjust)
        holder.bg.colorFilter = ColorMatrixColorFilter(cm)

        // Optional: a very small multiply wash to calm hotspots (no "glass")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            holder.bg.colorFilter = BlendModeColorFilter(Color.argb(18, 0, 0, 0), BlendMode.MULTIPLY)
            // chain both: blur via RenderEffect, tone via blend; looks natural and transparent
        }

        // 3) Adaptive readability from background luminance
        val lum = item.imageRes?.let { id -> luminanceCache.getOrPut(id) { computeAvgLuminance(holder.itemView, id) } } ?: 0.5f
        val isBright = lum >= 0.55f

        // Local scrim only under text: increase on bright bg, keep tiny on dark bg
        holder.textScrim.alpha = if (isBright) 0.42f else 0.12f

        // Switch text colors
        if (isBright) {
            // Bright image -> dark text
            holder.title.setTextColor(0xFF111111.toInt())
            holder.snippet.setTextColor(0xE0000000.toInt())
            holder.time.setTextColor(0x99000000.toInt())
            clearShadow(holder.title, holder.snippet)
        } else {
            // Dark image -> light text + soft shadow
            holder.title.setTextColor(0xFFFFFFFF.toInt())
            holder.snippet.setTextColor(0xF2FFFFFF.toInt())
            holder.time.setTextColor(0xCCFFFFFF.toInt())
            addShadow(holder.title, holder.snippet)
        }

        holder.itemView.setOnClickListener {
            val idx = holder.bindingAdapterPosition
            if (idx != RecyclerView.NO_POSITION) onItemClick(idx)
        }
    }

    override fun getItemCount(): Int = items.size

    // --- helpers ---

    private fun addShadow(vararg tv: TextView) { tv.forEach { it.setShadowLayer(4f, 0f, 1f, 0x66000000) } }
    private fun clearShadow(vararg tv: TextView) { tv.forEach { it.setShadowLayer(0f, 0f, 0f, 0) } }

    /** Average luminance (0..1) from a heavily downsampled decode (fast). */
    private fun computeAvgLuminance(root: View, resId: Int): Float {
        val ctx = root.context
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = 32
        }
        val bmp = BitmapFactory.decodeResource(ctx.resources, resId, opts) ?: return 0.5f

        val w = bmp.width
        val h = bmp.height
        val stepX = max(1, w / 24)
        val stepY = max(1, h / 24)

        var sum = 0f
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bmp.getPixel(x, y)
                val r = Color.red(c) / 255f
                val g = Color.green(c) / 255f
                val b = Color.blue(c) / 255f
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                sum += l; n++
                x += stepX
            }
            y += stepY
        }
        bmp.recycle()
        return min(1f, max(0f, if (n > 0) sum / n else 0.5f))
    }
}
