package com.example.timescapedemo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.timescapedemo.liquid.LiquidGlassLayout

/**
 * Simple model for a card item rendered with the liquid glass surface.
 */
data class CardItem(
    val title: String,
    val snippet: String,
    val time: String,
    /** Simulated environment luminance the glass reacts to (0 = dark room, 1 = bright). */
    val ambientLuminance: Float = 0.5f
)

class CardsAdapter(
    private val items: List<CardItem>,
    private val onItemClick: (index: Int) -> Unit
) : RecyclerView.Adapter<CardsAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.time)
        val title: TextView = v.findViewById(R.id.title)
        val snippet: TextView = v.findViewById(R.id.snippet)
        val liquidGlass: LiquidGlassLayout = v.findViewById(R.id.liquidGlass)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        holder.time.text = item.time
        holder.title.text = item.title
        holder.snippet.text = item.snippet

        val lum = item.ambientLuminance.coerceIn(0f, 1f)
        val isBright = lum >= 0.55f

        holder.liquidGlass.setSceneLuminance(lum)

        if (isBright) {
            holder.title.setTextColor(0xFF111111.toInt())
            holder.snippet.setTextColor(0xE0000000.toInt())
            holder.time.setTextColor(0x99000000.toInt())
            clearShadow(holder.title, holder.snippet)
        } else {
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

    private fun addShadow(vararg tv: TextView) {
        tv.forEach { it.setShadowLayer(4f, 0f, 1f, 0x66000000) }
    }

    private fun clearShadow(vararg tv: TextView) {
        tv.forEach { it.setShadowLayer(0f, 0f, 0f, 0) }
    }
}
