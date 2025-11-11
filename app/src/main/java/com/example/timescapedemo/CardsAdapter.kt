package com.example.timescapedemo

import android.content.Context
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.view.GestureDetectorCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import android.util.TypedValue
import androidx.core.view.isVisible
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Default sizing limits used when callers do not provide their own
// `BackgroundSizingConfig`. The adapter will never decode a background bitmap with a
// long edge greater than `DEFAULT_MAX_BG_LONG_EDGE_PX`, but it will also avoid shrinking
// extremely small cards below `DEFAULT_MIN_BG_LONG_EDGE_PX`. When the on-screen size
// is still unknown, it estimates the desired width as a fraction of the device width;
// `DEFAULT_BG_WIDTH_FRACTION` represents that fallback fraction. To prevent impossible
// values, `MIN_BG_WIDTH_FRACTION` defines the lowest fraction the config will accept.
private const val DEFAULT_MAX_BG_LONG_EDGE_PX = 720
private const val DEFAULT_MIN_BG_LONG_EDGE_PX = 320
private const val DEFAULT_BG_WIDTH_FRACTION = 0.65f
private const val MIN_BG_WIDTH_FRACTION = 0.1f

data class BackgroundSizingConfig(
    val maxLongEdgePx: Int = DEFAULT_MAX_BG_LONG_EDGE_PX,
    val minLongEdgePx: Int = DEFAULT_MIN_BG_LONG_EDGE_PX,
    val widthFraction: Float = DEFAULT_BG_WIDTH_FRACTION
) {
    fun normalized(): BackgroundSizingConfig {
        val minEdge = minLongEdgePx.coerceAtLeast(1)
        val maxEdge = max(maxLongEdgePx, minEdge)
        val fraction = widthFraction.coerceIn(MIN_BG_WIDTH_FRACTION, 1f)
        return if (minEdge == minLongEdgePx && maxEdge == maxLongEdgePx && fraction == widthFraction) {
            this
        } else {
            copy(maxLongEdgePx = maxEdge, minLongEdgePx = minEdge, widthFraction = fraction)
        }
    }
}

data class CardItem(
    val id: Long,
    var title: String,
    var snippet: String,
    var bg: BgImage? = null,
    var updatedAt: Long = System.currentTimeMillis(),
    var handwriting: HandwritingContent? = null
)

/** Non-“glass” tint options that keep the card transparent. */
sealed class TintStyle {
    data object None : TintStyle()
    data class MultiplyDark(@ColorInt val color: Int, val alpha: Float = 0.18f) : TintStyle()
    data class ScreenLight(@ColorInt val color: Int, val alpha: Float = 0.18f) : TintStyle()
    data class Colorize(@ColorInt val color: Int, val amount: Float = 0.35f) : TintStyle()
    data class Sepia(val amount: Float = 1f) : TintStyle()
    data class Duotone(@ColorInt val dark: Int, @ColorInt val light: Int, val amount: Float = 1f) : TintStyle()
}

class CardsAdapter(
    private val tint: TintStyle,
    private val onItemClick: (index: Int) -> Unit,
    private val onItemDoubleClick: (index: Int) -> Unit,
    backgroundSizing: BackgroundSizingConfig = BackgroundSizingConfig()
) : RecyclerView.Adapter<CardsAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.time)
        val snippet: TextView = v.findViewById(R.id.snippet)
        val bg: ImageView = v.findViewById(R.id.bgImage)
        val textScrim: View = v.findViewById(R.id.textScrim)
        val cardContent: View = v.findViewById(R.id.card_content)
        val handwritingContainer: View = v.findViewById(R.id.handwritingContainer)
        val handwriting: ImageView = v.findViewById(R.id.handwritingImage)
        lateinit var gestureDetector: GestureDetectorCompat
    }

    private val items = mutableListOf<CardItem>()
    private val blockedUris = mutableSetOf<Uri>()
    private var bodyTextSizeSp: Float = DEFAULT_BODY_TEXT_SIZE_SP
    private var backgroundSizingConfig: BackgroundSizingConfig = backgroundSizing.normalized()

    init {
        setHasStableIds(true)
    }

    fun submitList(newItems: List<CardItem>) {
        val oldItems = snapshotItems(items)
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition].id == newItems[newItemPosition].id

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition] == newItems[newItemPosition]
        })
        items.clear()
        items.addAll(newItems.map { it.deepCopy() })
        val activeUris = newItems.mapNotNull { (it.bg as? BgImage.UriRef)?.uri }.toSet()
        blockedUris.retainAll(activeUris)
        diff.dispatchUpdatesTo(this)
    }

    fun getItem(index: Int): CardItem? = items.getOrNull(index)

    fun setBackgroundSizing(config: BackgroundSizingConfig) {
        val normalized = config.normalized()
        if (normalized == backgroundSizingConfig) return
        backgroundSizingConfig = normalized
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
        val item = items[position]

        // ---- Bind text ----
        holder.time.text = DateUtils.getRelativeTimeSpanString(
            item.updatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
        val handwritingContent = item.handwriting
        val fallbackText = if (handwritingContent != null) {
            if (item.snippet.isNotBlank()) item.snippet
            else holder.itemView.context.getString(R.string.handwriting_card_missing)
        } else item.snippet
        holder.itemView.setTag(R.id.tag_card_id, item.id)
        showHandwriting(holder, isVisible = false, fallbackText = fallbackText)
        handwritingContent?.path?.let { path ->
            val (targetWidth, targetHeight) = estimateTargetSize(holder, handwritingContent)
            HandwritingBitmapLoader.load(
                context = holder.itemView.context,
                path = path,
                imageView = holder.handwriting,
                targetWidth = targetWidth,
                targetHeight = targetHeight
            ) { bitmap ->
                val isSameCard = holder.itemView.getTag(R.id.tag_card_id) == item.id
                if (!isSameCard) return@load
                if (bitmap != null) {
                    holder.handwriting.setImageBitmap(bitmap)
                    showHandwriting(holder, isVisible = true, fallbackText = fallbackText)
                    holder.handwriting.contentDescription = holder.itemView.context.getString(
                        R.string.handwriting_card_content_desc
                    )
                } else {
                    holder.handwriting.setImageDrawable(null)
                    showHandwriting(holder, isVisible = false, fallbackText = fallbackText)
                    holder.handwriting.contentDescription = null
                }
            }
            prefetchNeighbors(holder, position, targetWidth, targetHeight)
        } ?: run {
            HandwritingBitmapLoader.clear(holder.handwriting)
            holder.handwriting.contentDescription = null
        }
        holder.snippet.setTextSize(TypedValue.COMPLEX_UNIT_SP, bodyTextSizeSp)
        val timeSize = (bodyTextSizeSp - TIME_SIZE_DELTA).coerceAtLeast(MIN_TIME_TEXT_SIZE_SP)
        holder.time.setTextSize(TypedValue.COMPLEX_UNIT_SP, timeSize)

        // ---- Bind background image (drawable or Uri) ----
        BackgroundImageLoader.clear(holder.bg)
        val hasBackground = when (val b = item.bg) {
            is BgImage.Res -> {
                holder.bg.setImageResource(b.id)
                true
            }
            is BgImage.UriRef -> {
                if (blockedUris.contains(b.uri)) {
                    holder.bg.setImageResource(PLACEHOLDER_RES_ID)
                    false
                } else {
                    holder.bg.setImageResource(PLACEHOLDER_RES_ID)
                    val (targetWidth, targetHeight) = estimateBackgroundSize(holder)
                    BackgroundImageLoader.load(
                        context = holder.itemView.context,
                        imageView = holder.bg,
                        uri = b.uri,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight
                    ) { result ->
                        val isSameCard = holder.itemView.getTag(R.id.tag_card_id) == item.id
                        if (!isSameCard) return@load
                        when (result) {
                            is BackgroundImageLoader.Result.Success -> {
                                blockedUris.remove(b.uri)
                                holder.bg.setImageBitmap(result.bitmap)
                            }
                            is BackgroundImageLoader.Result.PermissionDenied -> {
                                blockedUris.add(b.uri)
                                holder.bg.setImageResource(PLACEHOLDER_RES_ID)
                            }
                            is BackgroundImageLoader.Result.NotFound -> {
                                holder.bg.setImageResource(PLACEHOLDER_RES_ID)
                            }
                            is BackgroundImageLoader.Result.Error -> {
                                holder.bg.setImageResource(PLACEHOLDER_RES_ID)
                            }
                        }
                    }
                    true
                }
            }
            null -> {
                holder.bg.setImageDrawable(null)
                false
            }
        }

        // Base blur (keeps transparency)
        var baseEffect: RenderEffect? = null
        if (hasBackground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            baseEffect = RenderEffect.createBlurEffect(BG_BLUR_RADIUS, BG_BLUR_RADIUS, Shader.TileMode.CLAMP)
        }

        // Apply NON-GLASS tint directly to image
        applyTintToImage(holder.bg, tint, baseEffect)

        // ---- Consistent readability styling ----
        holder.textScrim.alpha = 0.45f
        holder.snippet.setTextColor(Color.WHITE)
        holder.time.setTextColor(0xF2FFFFFF.toInt())
        addShadow(holder.time, holder.snippet)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].id

    override fun onViewRecycled(holder: VH) {
        HandwritingBitmapLoader.clear(holder.handwriting)
        BackgroundImageLoader.clear(holder.bg)
        holder.handwriting.contentDescription = null
        holder.itemView.setTag(R.id.tag_card_id, null)
        showHandwriting(holder, isVisible = false, fallbackText = holder.snippet.text ?: "")
        super.onViewRecycled(holder)
    }

    private fun prefetchNeighbors(holder: VH, position: Int, targetWidth: Int, targetHeight: Int) {
        val context = holder.itemView.context
        val appContext = context.applicationContext
        for (offset in 1..HANDWRITING_PREFETCH_DISTANCE) {
            prefetchForIndex(position + offset, appContext, targetWidth, targetHeight)
            prefetchForIndex(position - offset, appContext, targetWidth, targetHeight)
        }
    }

    private fun prefetchForIndex(index: Int, context: Context, baseWidth: Int, baseHeight: Int) {
        if (index < 0 || index >= items.size) return
        val handwriting = items[index].handwriting ?: return
        val width = min(baseWidth, handwriting.options.canvasWidth)
        val height = min(baseHeight, handwriting.options.canvasHeight)
        HandwritingBitmapLoader.prefetch(
            context = context,
            path = handwriting.path,
            targetWidth = width,
            targetHeight = height
        )
    }

    private fun estimateTargetSize(holder: VH, content: HandwritingContent): Pair<Int, Int> {
        val imageView = holder.handwriting
        val fallbackWidth = content.options.canvasWidth
        val fallbackHeight = content.options.canvasHeight
        val width = listOf(
            imageView.width,
            imageView.measuredWidth,
            imageView.layoutParams?.width ?: 0,
            holder.itemView.width
        ).firstOrNull { it > 0 }?.coerceAtMost(fallbackWidth)?.coerceAtLeast(1)
            ?: fallbackWidth
        val height = listOf(
            imageView.height,
            imageView.measuredHeight,
            imageView.layoutParams?.height ?: 0,
            holder.itemView.height
        ).firstOrNull { it > 0 }?.coerceAtMost(fallbackHeight)?.coerceAtLeast(1)
            ?: fallbackHeight
        return width to height
    }

    private fun estimateBackgroundSize(holder: VH): Pair<Int, Int> {
        val imageView = holder.bg
        val metrics = holder.itemView.resources.displayMetrics
        val fallbackWidth = (metrics.widthPixels * backgroundSizingConfig.widthFraction).toInt().coerceAtLeast(1)
        val fallbackHeight = (metrics.heightPixels * 0.5f).toInt().coerceAtLeast(1)
        val width = listOf(
            imageView.width,
            imageView.measuredWidth,
            imageView.layoutParams?.width ?: 0,
            holder.itemView.width,
            holder.itemView.measuredWidth
        ).firstOrNull { it > 0 } ?: fallbackWidth
        val height = listOf(
            imageView.height,
            imageView.measuredHeight,
            imageView.layoutParams?.height ?: 0,
            holder.itemView.height,
            holder.itemView.measuredHeight
        ).firstOrNull { it > 0 } ?: fallbackHeight
        return clampBackgroundDimensions(width, height, metrics)
    }

    private fun clampBackgroundDimensions(width: Int, height: Int, metrics: android.util.DisplayMetrics): Pair<Int, Int> {
        var w = width.coerceAtLeast(1)
        var h = height.coerceAtLeast(1)
        val config = backgroundSizingConfig
        val maxLongEdge = min(
            config.maxLongEdgePx,
            max((metrics.widthPixels * config.widthFraction).roundToInt(), config.minLongEdgePx)
        )
        val currentLong = max(w, h)
        if (currentLong <= maxLongEdge) return w to h
        val scale = maxLongEdge.toFloat() / currentLong.toFloat()
        w = max(1, (w * scale).roundToInt())
        h = max(1, (h * scale).roundToInt())
        return w to h
    }

    private fun showHandwriting(holder: VH, isVisible: Boolean, fallbackText: CharSequence) {
        holder.handwritingContainer.isVisible = isVisible
        holder.handwriting.isVisible = isVisible
        holder.cardContent.isVisible = !isVisible
        holder.textScrim.isVisible = !isVisible
        holder.time.isVisible = !isVisible
        holder.snippet.isVisible = !isVisible
        if (isVisible) {
            holder.snippet.text = ""
        } else {
            holder.handwriting.setImageDrawable(null)
            holder.snippet.text = fallbackText
        }
    }

    // ---------- Tint implementations ----------
    private fun applyTintToImage(iv: ImageView, style: TintStyle, baseEffect: RenderEffect?) {
        when (style) {
            is TintStyle.None -> {
                iv.colorFilter = null
                if (baseEffect != null) iv.setRenderEffect(baseEffect)
            }
            is TintStyle.MultiplyDark -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    iv.colorFilter = BlendModeColorFilter(withAlpha(style.color, style.alpha), BlendMode.MULTIPLY)
                } else iv.colorFilter = ColorMatrixColorFilter(ColorMatrix())
                if (baseEffect != null) iv.setRenderEffect(baseEffect)
            }
            is TintStyle.ScreenLight -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    iv.colorFilter = BlendModeColorFilter(withAlpha(style.color, style.alpha), BlendMode.SCREEN)
                } else iv.colorFilter = ColorMatrixColorFilter(ColorMatrix())
                if (baseEffect != null) iv.setRenderEffect(baseEffect)
            }
            is TintStyle.Colorize -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    iv.colorFilter = BlendModeColorFilter(withAlpha(style.color, style.amount.coerceIn(0f,1f)), BlendMode.COLOR)
                } else iv.colorFilter = ColorMatrixColorFilter(colorizeMatrix(style.color, style.amount))
                if (baseEffect != null) iv.setRenderEffect(baseEffect)
            }
            is TintStyle.Sepia -> {
                iv.colorFilter = ColorMatrixColorFilter(sepiaMatrix(style.amount))
                if (baseEffect != null) iv.setRenderEffect(baseEffect)
            }
            is TintStyle.Duotone -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val shader = RuntimeShader(DUOTONE_SHADER)
                    setDuotoneUniforms(shader, style)
                    val duo = RenderEffect.createRuntimeShaderEffect(shader, "content")
                    val final = if (baseEffect != null) RenderEffect.createChainEffect(duo, baseEffect) else duo
                    iv.setRenderEffect(final)
                    iv.colorFilter = null
                } else {
                    applyTintToImage(iv, TintStyle.Colorize(style.light, style.amount * 0.6f), baseEffect)
                }
            }
        }
    }

    private fun withAlpha(@ColorInt color: Int, a: Float): Int {
        val aa = (a.coerceIn(0f, 1f) * 255).toInt()
        return (color and 0x00FFFFFF) or (aa shl 24)
    }

    // --- Text helpers ---
    private fun addShadow(vararg tv: TextView) { tv.forEach { it.setShadowLayer(4f, 0f, 1f, 0x66000000) } }

    fun setBodyTextSize(sizeSp: Float) {
        val clamped = sizeSp.coerceIn(MIN_BODY_TEXT_SIZE_SP, MAX_BODY_TEXT_SIZE_SP)
        if (abs(bodyTextSizeSp - clamped) < 0.01f) return
        bodyTextSizeSp = clamped
        notifyDataSetChanged()
    }

    // --- Color matrices ---
    private fun colorizeMatrix(@ColorInt color: Int, amount: Float): ColorMatrix {
        val t = amount.coerceIn(0f, 1f)
        val sat = 1f - t
        val cm = ColorMatrix().apply { setSaturation(sat) }
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val bias = 0.12f * t
        val push = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, 255f * bias * r,
                0f, 1f, 0f, 0f, 255f * bias * g,
                0f, 0f, 1f, 0f, 255f * bias * b,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(push)
        return cm
    }
    private fun sepiaMatrix(tIn: Float): ColorMatrix {
        val t = tIn.coerceIn(0f, 1f)
        val id = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        val sep = floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f,     0f,     0f,     1f, 0f
        )
        val out = FloatArray(20)
        for (i in 0 until 20) out[i] = id[i] + (sep[i] - id[i]) * t
        return ColorMatrix(out)
    }

    // --- Duotone shader (Android 13+) ---
    private fun setDuotoneUniforms(shader: RuntimeShader, style: TintStyle.Duotone) {
        val dark = style.dark
        val light = style.light
        shader.setFloatUniform("amount", style.amount.coerceIn(0f, 1f))
        shader.setFloatUniform("dark", Color.red(dark)/255f, Color.green(dark)/255f, Color.blue(dark)/255f, 1f)
        shader.setFloatUniform("light", Color.red(light)/255f, Color.green(light)/255f, Color.blue(light)/255f, 1f)
    }
    @Suppress("ConstPropertyName")
    private companion object {
        private const val DEFAULT_BODY_TEXT_SIZE_SP = 18f
        private const val MIN_BODY_TEXT_SIZE_SP = 12f
        private const val MAX_BODY_TEXT_SIZE_SP = 32f
        private const val TIME_SIZE_DELTA = 3f
        private const val MIN_TIME_TEXT_SIZE_SP = 10f
        private val PLACEHOLDER_RES_ID = R.drawable.bg_placeholder
        private const val BG_BLUR_RADIUS = 12f

        private const val DUOTONE_SHADER = """
            uniform shader content;
            uniform float amount;
            uniform vec4 dark;
            uniform vec4 light;
            half4 main(float2 p) {
                half4 c = content.eval(p);
                float l = max(max(c.r, c.g), c.b);
                vec3 tone = mix(dark.rgb, light.rgb, l);
                vec3 outRGB = mix(c.rgb, tone, amount);
                return half4(outRGB, c.a);
            }
        """
    }
}

private const val HANDWRITING_PREFETCH_DISTANCE = 2

private fun snapshotItems(source: List<CardItem>): List<CardItem> = source.map { it.deepCopy() }

private fun CardItem.deepCopy(): CardItem = copy(
    bg = when (val background = bg) {
        is BgImage.Res -> background.copy()
        is BgImage.UriRef -> background.copy()
        null -> null
    },
    handwriting = handwriting?.let { content ->
        content.copy(options = content.options.copy())
    }
)
