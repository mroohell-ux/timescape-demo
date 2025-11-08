package com.example.timescapedemo

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.format.DateUtils
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.GestureDetectorCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import eightbitlab.com.blurview.BlurView
import eightbitlab.com.blurview.RenderEffectBlur

data class CardItem(
    val id: Long,
    var title: String,
    var snippet: String,
    var bg: BgImage? = null,
    var updatedAt: Long = System.currentTimeMillis()
)

class CardsAdapter(
    private val blurRootProvider: () -> ViewGroup?,
    private val onItemClick: (index: Int) -> Unit,
    private val onItemDoubleClick: (index: Int) -> Unit
) : RecyclerView.Adapter<CardsAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView = v.findViewById(R.id.card)
        val blurLayer: BlurView = v.findViewById(R.id.blurLayer)
        val time: TextView = v.findViewById(R.id.time)
        val title: TextView = v.findViewById(R.id.title)
        val snippet: TextView = v.findViewById(R.id.snippet)
        val textScrim: View = v.findViewById(R.id.textScrim)
        var blurConfigured: Boolean = false
        lateinit var gestureDetector: GestureDetectorCompat
    }

    private val items = mutableListOf<CardItem>()
    private val blurAlgorithm by lazy { RenderEffectBlur() }

    fun submitList(newItems: List<CardItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItem(index: Int): CardItem? = items.getOrNull(index)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        val vh = VH(v)
        vh.gestureDetector = GestureDetectorCompat(v.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val idx = vh.bindingAdapterPosition
                if (idx != RecyclerView.NO_POSITION) onItemClick(idx)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val idx = vh.bindingAdapterPosition
                if (idx != RecyclerView.NO_POSITION) onItemDoubleClick(idx)
                return true
            }
        })
        v.isClickable = true
        v.setOnTouchListener { view, event ->
            val handled = vh.gestureDetector.onTouchEvent(event)
            if (!handled && event.action == MotionEvent.ACTION_UP) {
                view.performClick()
            }
            true
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        configureBlur(holder)
        val item = items[position]

        holder.time.text = DateUtils.getRelativeTimeSpanString(
            item.updatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
        holder.title.text = item.title
        holder.snippet.text = item.snippet

        styleText(holder)
    }

    override fun getItemCount(): Int = items.size

    private fun configureBlur(holder: VH) {
        if (holder.blurConfigured) return
        val root = blurRootProvider() ?: return
        val frameDrawable = root.background ?: resolveWindowBackground(holder.itemView)
        holder.blurLayer.setupWith(root)
            .setFrameClearDrawable(frameDrawable)
            .setBlurAlgorithm(blurAlgorithm)
            .setBlurRadius(BLUR_RADIUS)
            .setBlurAutoUpdate(true)
            .setHasFixedTransformationMatrix(true)
            .setOverlayColor(OVERLAY_COLOR)
        holder.blurConfigured = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            holder.card.setBackgroundBlurRadius(BACKGROUND_BLUR_RADIUS)
        }
    }

    private fun styleText(holder: VH) {
        holder.textScrim.alpha = 0.18f
        holder.title.setTextColor(HEADLINE_COLOR)
        holder.snippet.setTextColor(BODY_COLOR)
        holder.time.setTextColor(META_COLOR)
        clearShadow(holder.title, holder.snippet)
    }

    private fun clearShadow(vararg tv: TextView) {
        tv.forEach { it.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT) }
    }

    private fun resolveWindowBackground(view: View): Drawable? {
        val attrs = view.context.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
        return attrs.getDrawable(0)?.also { attrs.recycle() } ?: run {
            attrs.recycle()
            null
        }
    }

    private companion object {
        private const val BLUR_RADIUS = 28f
        private const val BACKGROUND_BLUR_RADIUS = 50
        private val OVERLAY_COLOR = ColorUtils.setAlphaComponent(Color.WHITE, (0.35f * 255).toInt())
        private val HEADLINE_COLOR = ColorUtils.setAlphaComponent(Color.BLACK, (0.87f * 255).toInt())
        private val BODY_COLOR = ColorUtils.setAlphaComponent(Color.BLACK, (0.75f * 255).toInt())
        private val META_COLOR = ColorUtils.setAlphaComponent(Color.BLACK, (0.6f * 255).toInt())
    }
}
