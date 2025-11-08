package com.example.timescapedemo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.format.DateUtils
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

data class CardItem(
    val id: Long,
    var title: String,
    var snippet: String,
    var bg: BgImage? = null,
    var updatedAt: Long = System.currentTimeMillis()
)

class CardsAdapter(
    private val backgroundRootProvider: () -> ViewGroup?,
    private val onItemClick: (index: Int) -> Unit,
    private val onItemDoubleClick: (index: Int) -> Unit
) : RecyclerView.Adapter<CardsAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView = v.findViewById(R.id.card)
        val backgroundImage: ImageView = v.findViewById(R.id.bgImage)
        val time: TextView = v.findViewById(R.id.time)
        val title: TextView = v.findViewById(R.id.title)
        val snippet: TextView = v.findViewById(R.id.snippet)
        val textScrim: View = v.findViewById(R.id.textScrim)
        var backgroundBitmap: Bitmap? = null
        lateinit var gestureDetector: GestureDetectorCompat
    }

    private val items = mutableListOf<CardItem>()
    private var cachedRootBitmap: Bitmap? = null
    private var cachedRootHash: Int = 0

    private val tmpCardLocation = IntArray(2)
    private val tmpRootLocation = IntArray(2)
    private val tmpSrcRect = Rect()
    private val tmpDstRect = Rect()

    private var blurEffect: RenderEffect? = null

    fun submitList(newItems: List<CardItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItem(index: Int): CardItem? = items.getOrNull(index)

    fun notifyAppBackgroundChanged() {
        cachedRootBitmap?.recycle()
        cachedRootBitmap = null
        cachedRootHash = 0
        notifyDataSetChanged()
    }

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
        bindBackground(holder)
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

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.backgroundBitmap?.recycle()
        holder.backgroundBitmap = null
        holder.backgroundImage.setImageDrawable(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            holder.backgroundImage.setRenderEffect(null)
        }
    }

    private fun bindBackground(holder: VH) {
        val root = backgroundRootProvider() ?: run {
            clearBackground(holder)
            return
        }
        if (holder.card.width == 0 || holder.card.height == 0) {
            holder.card.doOnLayout { bindBackground(holder) }
            return
        }

        val background = obtainRootBitmap(root, resolveWindowBackground(holder.itemView)) ?: run {
            clearBackground(holder)
            return
        }

        holder.card.getLocationOnScreen(tmpCardLocation)
        root.getLocationOnScreen(tmpRootLocation)

        val left = tmpCardLocation[0] - tmpRootLocation[0]
        val top = tmpCardLocation[1] - tmpRootLocation[1]
        val right = left + holder.card.width
        val bottom = top + holder.card.height

        val clampedLeft = left.coerceIn(0, background.width)
        val clampedTop = top.coerceIn(0, background.height)
        val clampedRight = right.coerceIn(clampedLeft + 1, background.width)
        val clampedBottom = bottom.coerceIn(clampedTop + 1, background.height)

        if (clampedLeft >= clampedRight || clampedTop >= clampedBottom) {
            clearBackground(holder)
            return
        }

        tmpSrcRect.set(clampedLeft, clampedTop, clampedRight, clampedBottom)
        val destBitmap = ensureBitmap(holder, tmpSrcRect.width(), tmpSrcRect.height())
        val canvas = Canvas(destBitmap)
        tmpDstRect.set(0, 0, destBitmap.width, destBitmap.height)
        canvas.drawBitmap(background, tmpSrcRect, tmpDstRect, null)
        holder.backgroundImage.setImageBitmap(destBitmap)
        applyBlur(holder)
    }

    private fun ensureBitmap(holder: VH, width: Int, height: Int): Bitmap {
        val existing = holder.backgroundBitmap
        if (existing != null && existing.width == width && existing.height == height) {
            return existing
        }
        existing?.recycle()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        holder.backgroundBitmap = bitmap
        return bitmap
    }

    private fun applyBlur(holder: VH) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val effect = blurEffect ?: RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP).also {
                blurEffect = it
            }
            holder.backgroundImage.setRenderEffect(effect)
        }
    }

    private fun clearBackground(holder: VH) {
        holder.backgroundImage.setImageDrawable(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            holder.backgroundImage.setRenderEffect(null)
        }
    }

    private fun obtainRootBitmap(root: ViewGroup, fallback: Drawable?): Bitmap? {
        val drawable = (root.background ?: fallback) ?: return null
        val width = root.width
        val height = root.height
        if (width <= 0 || height <= 0) return null

        val hash = System.identityHashCode(drawable)
        val cached = cachedRootBitmap
        if (cached != null && cached.width == width && cached.height == height && cachedRootHash == hash) {
            return cached
        }

        val copy = drawable.constantState?.newDrawable()?.mutate() ?: drawable.mutate()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        copy.setBounds(0, 0, width, height)
        copy.draw(canvas)

        cachedRootBitmap?.recycle()
        cachedRootBitmap = bitmap
        cachedRootHash = hash
        return bitmap
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
        val drawable = attrs.getDrawable(0)
        attrs.recycle()
        return drawable
    }

    private companion object {
        private const val BLUR_RADIUS = 28f
        private val HEADLINE_COLOR = ColorUtils.setAlphaComponent(Color.BLACK, (0.87f * 255).toInt())
        private val BODY_COLOR = ColorUtils.setAlphaComponent(Color.BLACK, (0.75f * 255).toInt())
        private val META_COLOR = ColorUtils.setAlphaComponent(Color.BLACK, (0.6f * 255).toInt())
    }
}
