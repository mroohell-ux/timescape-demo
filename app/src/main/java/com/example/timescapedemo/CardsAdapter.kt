package com.example.timescapedemo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class CardItem(
    val title: String,
    val snippet: String,
    val time: String,
    val imageRes: Int? = null   // optional; not used in this layout
)

class CardsAdapter(
    private val items: List<CardItem>,
    private val onItemClick: (index: Int) -> Unit
) : RecyclerView.Adapter<CardsAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.time)
        val title: TextView = v.findViewById(R.id.title)
        val snippet: TextView = v.findViewById(R.id.snippet)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.time.text = item.time
        holder.title.text = item.title
        holder.snippet.text = item.snippet

        holder.itemView.setOnClickListener {
            val idx = holder.bindingAdapterPosition
            if (idx != RecyclerView.NO_POSITION) onItemClick(idx)
        }
    }

    override fun getItemCount(): Int = items.size
}
