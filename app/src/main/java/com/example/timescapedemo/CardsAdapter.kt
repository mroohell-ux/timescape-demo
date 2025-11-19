package com.example.timescapedemo

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.view.GestureDetectorCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.util.TypedValue
import androidx.core.view.isVisible
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
private const val DEFAULT_MAX_BG_LONG_EDGE_PX = 280
private const val DEFAULT_MIN_BG_LONG_EDGE_PX = 80
private const val DEFAULT_BG_WIDTH_FRACTION = 0.45f
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
    var image: CardImage? = null,
    var updatedAt: Long = System.currentTimeMillis(),
    var handwriting: HandwritingContent? = null,
    var imageHandwriting: HandwritingSide? = null,
    var relativeTimeText: CharSequence? = null
)

data class CardImage(
    var uri: Uri,
    var mimeType: String? = null,
    var ownedByApp: Boolean = false
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
    private val onTitleSpeakClick: ((CardItem) -> Unit)? = null,
    backgroundSizing: BackgroundSizingConfig = BackgroundSizingConfig()
) : ListAdapter<CardItem, CardsAdapter.VH>(DIFF_CALLBACK) {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.time)
        val titleContainer: View = v.findViewById(R.id.titleContainer)
        val title: TextView = v.findViewById(R.id.title)
        val titleSpeakButton: ImageButton = v.findViewById(R.id.buttonSpeakTitle)
        val snippet: TextView = v.findViewById(R.id.snippet)
        val bg: ImageView = v.findViewById(R.id.bgImage)
        val textScrim: View = v.findViewById(R.id.textScrim)
        val imageCardContainer: View = v.findViewById(R.id.imageCardContainer)
        val imageCard: ImageView = v.findViewById(R.id.imageCard)
        val cardContent: View = v.findViewById(R.id.card_content)
        val handwritingContainer: View = v.findViewById(R.id.handwritingContainer)
        val handwriting: ImageView = v.findViewById(R.id.handwritingImage)
        lateinit var gestureDetector: GestureDetectorCompat
    }

    private val blockedUris = mutableSetOf<Uri>()
    private var bodyTextSizeSp: Float = DEFAULT_BODY_TEXT_SIZE_SP
    private var backgroundSizingConfig: BackgroundSizingConfig = backgroundSizing.normalized()
    private val tintProcessor = TintProcessor(tint)
    private val handwritingFaces = mutableMapOf<Long, HandwritingFace>()
    private enum class CardMode { TEXT, IMAGE, HANDWRITING }
    private val blurEffect: RenderEffect? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RenderEffect.createBlurEffect(BG_BLUR_RADIUS, BG_BLUR_RADIUS, Shader.TileMode.CLAMP)
        } else {
            null
        }

    init {
        setHasStableIds(true)
    }

    override fun submitList(list: List<CardItem>?) {
        super.submitList(prepareList(list))
    }

    override fun submitList(list: List<CardItem>?, commitCallback: Runnable?) {
        super.submitList(prepareList(list), commitCallback)
    }

    private fun prepareList(list: List<CardItem>?): List<CardItem>? {
        if (list == null) {
            blockedUris.clear()
            return null
        }
        val now = System.currentTimeMillis()
        val copies = list.map { item ->
            val copy = item.deepCopy()
            copy.relativeTimeText = DateUtils.getRelativeTimeSpanString(
                copy.updatedAt,
                now,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            ).toString()
            copy
        }
        val activeUris = copies.flatMap { card ->
            buildList {
                (card.bg as? BgImage.UriRef)?.uri?.let { add(it) }
                card.image?.uri?.let { add(it) }
            }
        }.toSet()
        blockedUris.retainAll(activeUris)
        val flippableIds = copies.filter {
            it.handwriting != null || it.imageHandwriting != null
        }.map { it.id }.toSet()
        handwritingFaces.keys.retainAll(flippableIds)
        return copies
    }

    fun getItemAt(index: Int): CardItem? = currentList.getOrNull(index)

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
        val item = getItem(position)

        // ---- Bind text ----
        holder.time.text = item.relativeTimeText ?: DateUtils.getRelativeTimeSpanString(
            item.updatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString().also { item.relativeTimeText = it }
        val handwritingContent = item.handwriting
        val imageContent = item.image
        val fallbackText = when {
            handwritingContent != null -> {
                if (item.snippet.isNotBlank()) item.snippet
                else holder.itemView.context.getString(R.string.handwriting_card_missing)
            }
            imageContent != null -> {
                if (item.snippet.isNotBlank()) item.snippet
                else holder.itemView.context.getString(R.string.image_card_missing)
            }
            else -> item.snippet
        }
        holder.itemView.setTag(R.id.tag_card_id, item.id)
        holder.handwritingContainer.cameraDistance =
            holder.itemView.resources.displayMetrics.density * HANDWRITING_CAMERA_DISTANCE
        val face = currentCardFace(item.id)
        if (handwritingContent != null) {
            bindHandwriting(holder, item, handwritingContent, face, fallbackText, position)
            clearTitle(holder)
        } else if (imageContent != null) {
            bindImageCard(holder, item, face, fallbackText, position)
            clearTitle(holder)
        } else {
            HandwritingBitmapLoader.clear(holder.handwriting)
            BackgroundImageLoader.clear(holder.imageCard)
            holder.imageCard.setImageDrawable(null)
            setCardMode(holder, CardMode.TEXT, fallbackText)
            bindTitle(holder, item)
        }
        holder.snippet.setTextSize(TypedValue.COMPLEX_UNIT_SP, bodyTextSizeSp)
        val timeSize = (bodyTextSizeSp - TIME_SIZE_DELTA).coerceAtLeast(MIN_TIME_TEXT_SIZE_SP)
        holder.time.setTextSize(TypedValue.COMPLEX_UNIT_SP, timeSize)

        // ---- Bind background image (drawable or Uri) ----
        val shouldDisplayBackground = handwritingContent == null && imageContent == null
        BackgroundImageLoader.clear(holder.bg)
        val hasBackground = if (shouldDisplayBackground) {
            when (val b = item.bg) {
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
        } else {
            holder.bg.setImageDrawable(null)
            false
        }

        // Base blur (keeps transparency)
        val baseEffect = if (hasBackground) blurEffect else null

        // Apply NON-GLASS tint directly to image
        tintProcessor.apply(holder.bg, baseEffect)

        // ---- Consistent readability styling ----
        holder.textScrim.alpha = 0.45f
        holder.snippet.setTextColor(Color.WHITE)
        holder.time.setTextColor(0xF2FFFFFF.toInt())
        addShadow(holder.time, holder.title, holder.snippet)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onViewRecycled(holder: VH) {
        HandwritingBitmapLoader.clear(holder.handwriting)
        BackgroundImageLoader.clear(holder.bg)
        BackgroundImageLoader.clear(holder.imageCard)
        holder.imageCard.setImageDrawable(null)
        holder.imageCard.contentDescription = null
        holder.handwriting.contentDescription = null
        holder.itemView.setTag(R.id.tag_card_id, null)
        setCardMode(holder, CardMode.TEXT, holder.snippet.text ?: "")
        super.onViewRecycled(holder)
    }

    private fun bindHandwriting(
        holder: VH,
        item: CardItem,
        content: HandwritingContent,
        face: HandwritingFace,
        fallbackText: CharSequence,
        position: Int,
        onReady: (() -> Unit)? = null
    ) {
        val handwritingContent = content
        val density = holder.itemView.resources.displayMetrics.density
        if (face == HandwritingFace.BACK && handwritingContent.back == null) {
            HandwritingBitmapLoader.clear(holder.handwriting)
            val (targetWidth, targetHeight) = estimateTargetSize(holder, handwritingContent.options)
            val placeholder = HandwritingPaperRenderer.renderPlaceholder(
                options = handwritingContent.options,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                density = density
            )
            holder.handwritingContainer.setBackgroundColor(handwritingContent.options.backgroundColor)
            holder.handwriting.setImageBitmap(placeholder)
            holder.handwriting.contentDescription = holder.itemView.context.getString(
                R.string.handwriting_card_content_desc
            )
            setCardMode(holder, CardMode.HANDWRITING, fallbackText)
            onReady?.invoke()
            prefetchNeighbors(holder, position, handwritingContent.options)
            return
        }
        val side = handwritingContent.side(face)
        val (targetWidth, targetHeight) = estimateTargetSize(holder, side.options)
        holder.handwritingContainer.setBackgroundColor(side.options.backgroundColor)
        HandwritingBitmapLoader.clear(holder.handwriting)
        setCardMode(holder, CardMode.HANDWRITING, fallbackText)
        HandwritingBitmapLoader.load(
            context = holder.itemView.context,
            path = side.path,
            imageView = holder.handwriting,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        ) { bitmap ->
            val isSameCard = holder.itemView.getTag(R.id.tag_card_id) == item.id
            if (!isSameCard) return@load
            val expectedFace = currentCardFace(item.id)
            if (expectedFace != face) return@load
            val toDisplay = bitmap ?: HandwritingPaperRenderer.renderPlaceholder(
                options = side.options,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                density = density
            )
            holder.handwriting.setImageBitmap(toDisplay)
            holder.handwriting.contentDescription = holder.itemView.context.getString(
                R.string.handwriting_card_content_desc
            )
            onReady?.invoke()
        }
        handwritingContent.back?.let { backSide ->
            val (width, height) = estimateTargetSize(holder, backSide.options)
            HandwritingBitmapLoader.prefetch(
                context = holder.itemView.context.applicationContext,
                path = backSide.path,
                targetWidth = width,
                targetHeight = height
            )
        }
        prefetchNeighbors(holder, position, side.options)
    }

    private fun bindImageCard(
        holder: VH,
        item: CardItem,
        face: HandwritingFace,
        fallbackText: CharSequence,
        position: Int
    ) {
        val backSide = item.imageHandwriting
        if (face == HandwritingFace.BACK) {
            if (backSide != null) {
                bindImageBack(holder, item, backSide, fallbackText, position)
                return
            } else {
                handwritingFaces.remove(item.id)
            }
        }
        HandwritingBitmapLoader.clear(holder.handwriting)
        holder.handwriting.contentDescription = null
        BackgroundImageLoader.clear(holder.imageCard)
        setCardMode(holder, CardMode.IMAGE, fallbackText)
        val snippet = item.snippet.takeIf { it.isNotBlank() }
        holder.imageCard.contentDescription = snippet
            ?: holder.itemView.context.getString(R.string.image_card_content_desc)
        val image = item.image
        val uri = image?.uri
        if (uri == null) {
            holder.imageCard.setImageResource(PLACEHOLDER_RES_ID)
            return
        }
        if (blockedUris.contains(uri)) {
            holder.imageCard.setImageResource(PLACEHOLDER_RES_ID)
            return
        }
        val (targetWidth, targetHeight) = estimateImageTargetSize(holder)
        BackgroundImageLoader.load(
            context = holder.itemView.context,
            imageView = holder.imageCard,
            uri = uri,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        ) { result ->
            val isSameCard = holder.itemView.getTag(R.id.tag_card_id) == item.id
            if (!isSameCard) return@load
            when (result) {
                is BackgroundImageLoader.Result.Success -> {
                    blockedUris.remove(uri)
                    holder.imageCard.setImageBitmap(result.bitmap)
                }
                is BackgroundImageLoader.Result.PermissionDenied -> {
                    blockedUris.add(uri)
                    holder.imageCard.setImageResource(PLACEHOLDER_RES_ID)
                }
                is BackgroundImageLoader.Result.NotFound -> {
                    holder.imageCard.setImageResource(PLACEHOLDER_RES_ID)
                }
                is BackgroundImageLoader.Result.Error -> {
                    holder.imageCard.setImageResource(PLACEHOLDER_RES_ID)
                }
            }
        }
    }

    private fun bindImageBack(
        holder: VH,
        item: CardItem,
        side: HandwritingSide,
        fallbackText: CharSequence,
        position: Int
    ) {
        val density = holder.itemView.resources.displayMetrics.density
        val (targetWidth, targetHeight) = estimateTargetSize(holder, side.options)
        holder.handwritingContainer.setBackgroundColor(side.options.backgroundColor)
        HandwritingBitmapLoader.clear(holder.handwriting)
        setCardMode(holder, CardMode.HANDWRITING, fallbackText)
        HandwritingBitmapLoader.load(
            context = holder.itemView.context,
            path = side.path,
            imageView = holder.handwriting,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        ) { bitmap ->
            val isSameCard = holder.itemView.getTag(R.id.tag_card_id) == item.id
            if (!isSameCard) return@load
            val expectedFace = currentCardFace(item.id)
            if (expectedFace != HandwritingFace.BACK) return@load
            val toDisplay = bitmap ?: HandwritingPaperRenderer.renderPlaceholder(
                options = side.options,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                density = density
            )
            holder.handwriting.setImageBitmap(toDisplay)
            holder.handwriting.contentDescription = holder.itemView.context.getString(
                R.string.handwriting_card_content_desc
            )
        }
        prefetchNeighbors(holder, position, side.options)
    }

    fun toggleCardFace(holder: VH): HandwritingFace? {
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return null
        val item = getItem(position)
        val handwritingContent = item.handwriting
        val hasImageBack = item.imageHandwriting != null
        val hasHandwriting = handwritingContent != null
        if (!hasHandwriting && (!hasImageBack || item.image == null)) return null
        val current = currentCardFace(item.id)
        val next = if (current == HandwritingFace.FRONT) HandwritingFace.BACK else HandwritingFace.FRONT
        handwritingFaces[item.id] = next
        val fallbackText = when {
            handwritingContent != null -> if (item.snippet.isNotBlank()) item.snippet else holder.itemView.context.getString(R.string.handwriting_card_missing)
            item.image != null -> if (item.snippet.isNotBlank()) item.snippet else holder.itemView.context.getString(R.string.image_card_missing)
            else -> item.snippet
        }
        if (handwritingContent != null) {
            animateHandwritingFlip(holder, item, next, fallbackText, position)
        } else {
            bindImageCard(holder, item, next, fallbackText, position)
        }
        return next
    }

    fun currentFaceFor(index: Int): HandwritingFace {
        val item = getItemAt(index) ?: return HandwritingFace.FRONT
        return currentCardFace(item.id)
    }

    fun canFlipCardAt(index: Int): Boolean {
        val item = getItemAt(index) ?: return false
        if (item.handwriting != null) return true
        return item.imageHandwriting != null && item.image != null
    }

    private fun currentCardFace(itemId: Long): HandwritingFace = handwritingFaces[itemId] ?: HandwritingFace.FRONT

    private fun animateHandwritingFlip(
        holder: VH,
        item: CardItem,
        toFace: HandwritingFace,
        fallbackText: CharSequence,
        position: Int
    ) {
        val container = holder.handwritingContainer
        cancelOngoingFlip(container)
        val fold = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(container, View.ROTATION_Y, 0f, 90f).apply {
                    duration = HANDWRITING_FLIP_HALF_DURATION
                    interpolator = AccelerateInterpolator()
                },
                ObjectAnimator.ofFloat(container, View.SCALE_X, 1f, 0.88f).apply {
                    duration = HANDWRITING_FLIP_HALF_DURATION
                    interpolator = AccelerateInterpolator()
                },
                ObjectAnimator.ofFloat(container, View.SCALE_Y, 1f, 0.94f).apply {
                    duration = HANDWRITING_FLIP_HALF_DURATION
                    interpolator = AccelerateInterpolator()
                },
                ObjectAnimator.ofFloat(container, View.ALPHA, 1f, 0.75f).apply {
                    duration = HANDWRITING_FLIP_HALF_DURATION
                    interpolator = AccelerateInterpolator()
                }
            )
        }
        val unfold = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(container, View.ROTATION_Y, -90f, 0f).apply {
                    duration = HANDWRITING_FLIP_HALF_DURATION
                    interpolator = DecelerateInterpolator()
                },
                ObjectAnimator.ofFloat(container, View.SCALE_X, 0.88f, 1.04f, 1f).apply {
                    duration = HANDWRITING_FLIP_HALF_DURATION
                    interpolator = OvershootInterpolator(1.1f)
                },
                ObjectAnimator.ofFloat(container, View.SCALE_Y, 0.94f, 1f).apply {
                    duration = HANDWRITING_FLIP_HALF_DURATION
                    interpolator = OvershootInterpolator(0.9f)
                },
                ObjectAnimator.ofFloat(container, View.ALPHA, 0.75f, 1f).apply {
                    duration = HANDWRITING_FLIP_HALF_DURATION
                    interpolator = DecelerateInterpolator()
                }
            )
        }
        val animationPair = fold to unfold
        fold.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                container.rotationY = -90f
                val handwritingContent = item.handwriting ?: return
                bindHandwriting(holder, item, handwritingContent, toFace, fallbackText, position) {
                    if (container.getTag(R.id.tag_handwriting_flip_animator) == animationPair) {
                        unfold.start()
                    }
                }
            }
        })
        unfold.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                container.rotationY = 0f
                container.scaleX = 1f
                container.scaleY = 1f
                container.alpha = 1f
                container.setTag(R.id.tag_handwriting_flip_animator, null)
            }
        })
        container.setTag(R.id.tag_handwriting_flip_animator, animationPair)
        fold.start()
    }

    private fun prefetchNeighbors(holder: VH, position: Int, referenceOptions: HandwritingOptions) {
        val context = holder.itemView.context.applicationContext
        for (offset in 1..HANDWRITING_PREFETCH_DISTANCE) {
            prefetchForIndex(position + offset, context, referenceOptions)
            prefetchForIndex(position - offset, context, referenceOptions)
        }
    }

    private fun prefetchForIndex(index: Int, context: Context, referenceOptions: HandwritingOptions) {
        val snapshot = currentList
        if (index < 0 || index >= snapshot.size) return
        val handwriting = snapshot[index].handwriting ?: return
        val sides = listOfNotNull(
            HandwritingSide(handwriting.path, handwriting.options, handwriting.recognizedText),
            handwriting.back
        )
        for (side in sides) {
            val width = min(referenceOptions.canvasWidth, side.options.canvasWidth)
            val height = min(referenceOptions.canvasHeight, side.options.canvasHeight)
            HandwritingBitmapLoader.prefetch(
                context = context,
                path = side.path,
                targetWidth = width,
                targetHeight = height
            )
        }
    }

    private fun estimateTargetSize(holder: VH, options: HandwritingOptions): Pair<Int, Int> {
        val imageView = holder.handwriting
        val fallbackWidth = options.canvasWidth
        val fallbackHeight = options.canvasHeight
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

    private fun estimateImageTargetSize(holder: VH): Pair<Int, Int> {
        val imageView = holder.imageCard
        val metrics = holder.itemView.resources.displayMetrics
        val fallbackWidth = listOf(
            imageView.width,
            imageView.measuredWidth,
            holder.itemView.width,
            holder.itemView.measuredWidth,
            metrics.widthPixels
        ).firstOrNull { it > 0 }?.coerceAtLeast(1) ?: metrics.widthPixels
        val fallbackHeight = listOf(
            imageView.height,
            imageView.measuredHeight,
            holder.itemView.height,
            holder.itemView.measuredHeight,
            (metrics.heightPixels * 0.6f).roundToInt()
        ).firstOrNull { it > 0 }?.coerceAtLeast(1) ?: (metrics.heightPixels / 2).coerceAtLeast(1)
        return fallbackWidth to fallbackHeight
    }

    private fun setCardMode(holder: VH, mode: CardMode, fallbackText: CharSequence) {
        when (mode) {
            CardMode.TEXT -> {
                holder.cardContent.isVisible = true
                holder.textScrim.isVisible = true
                holder.time.isVisible = true
                holder.snippet.isVisible = true
                holder.snippet.text = fallbackText
                holder.titleContainer.isVisible = false
                holder.titleSpeakButton.isVisible = false
                holder.handwritingContainer.isVisible = false
                holder.handwriting.isVisible = false
                holder.imageCardContainer.isVisible = false
                holder.imageCard.isVisible = false
                holder.bg.isVisible = true
            }
            CardMode.IMAGE -> {
                holder.cardContent.isVisible = false
                holder.textScrim.isVisible = false
                holder.time.isVisible = false
                holder.snippet.isVisible = false
                holder.titleContainer.isVisible = false
                holder.titleSpeakButton.isVisible = false
                holder.handwritingContainer.isVisible = false
                holder.handwriting.isVisible = false
                holder.imageCardContainer.isVisible = true
                holder.imageCard.isVisible = true
                holder.bg.isVisible = false
            }
            CardMode.HANDWRITING -> {
                holder.cardContent.isVisible = false
                holder.textScrim.isVisible = false
                holder.time.isVisible = false
                holder.snippet.isVisible = false
                holder.titleContainer.isVisible = false
                holder.titleSpeakButton.isVisible = false
                holder.handwritingContainer.isVisible = true
                holder.handwriting.isVisible = true
                holder.imageCardContainer.isVisible = false
                holder.imageCard.isVisible = false
                holder.bg.isVisible = false
            }
        }
    }

    private fun bindTitle(holder: VH, item: CardItem) {
        val titleText = item.title.trim()
        if (titleText.isEmpty()) {
            clearTitle(holder)
            return
        }
        holder.titleContainer.isVisible = true
        holder.title.text = titleText
        val speakButton = holder.titleSpeakButton
        val speakCallback = onTitleSpeakClick
        if (speakCallback != null) {
            speakButton.isVisible = true
            speakButton.isEnabled = true
            speakButton.contentDescription = holder.itemView.context.getString(
                R.string.card_title_speak_content_desc,
                titleText
            )
            speakButton.setOnClickListener { speakCallback(item) }
        } else {
            speakButton.isVisible = false
            speakButton.setOnClickListener(null)
            speakButton.contentDescription = null
        }
    }

    private fun clearTitle(holder: VH) {
        holder.titleContainer.isVisible = false
        holder.title.text = ""
        holder.titleSpeakButton.isVisible = false
        holder.titleSpeakButton.setOnClickListener(null)
        holder.titleSpeakButton.contentDescription = null
    }

    private fun cancelOngoingFlip(container: View) {
        val running = container.getTag(R.id.tag_handwriting_flip_animator) as? Pair<AnimatorSet, AnimatorSet>
        running?.first?.cancel()
        running?.second?.cancel()
        container.setTag(R.id.tag_handwriting_flip_animator, null)
        container.rotationY = 0f
        container.scaleX = 1f
        container.scaleY = 1f
        container.alpha = 1f
    }

    // ---------- Tint implementations ----------
    private inner class TintProcessor(private val style: TintStyle) {
        private val multiplyFilter: android.graphics.ColorFilter? =
            (style as? TintStyle.MultiplyDark)?.let { tint ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    BlendModeColorFilter(withAlpha(tint.color, tint.alpha), BlendMode.MULTIPLY)
                } else {
                    ColorMatrixColorFilter(ColorMatrix())
                }
            }

        private val screenFilter: android.graphics.ColorFilter? =
            (style as? TintStyle.ScreenLight)?.let { tint ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    BlendModeColorFilter(withAlpha(tint.color, tint.alpha), BlendMode.SCREEN)
                } else {
                    ColorMatrixColorFilter(ColorMatrix())
                }
            }

        private val colorizeFilter: android.graphics.ColorFilter? =
            (style as? TintStyle.Colorize)?.let { tint ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    BlendModeColorFilter(
                        withAlpha(tint.color, tint.amount.coerceIn(0f, 1f)),
                        BlendMode.COLOR
                    )
                } else {
                    ColorMatrixColorFilter(colorizeMatrix(tint.color, tint.amount))
                }
            }

        private val sepiaFilter: android.graphics.ColorFilter? =
            (style as? TintStyle.Sepia)?.let { tint ->
                ColorMatrixColorFilter(sepiaMatrix(tint.amount))
            }

        private val duotoneEffect: RenderEffect? =
            (style as? TintStyle.Duotone)?.let { tint ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    RuntimeShader(DUOTONE_SHADER).apply { setDuotoneUniforms(this, tint) }
                        .let { shader -> RenderEffect.createRuntimeShaderEffect(shader, "content") }
                } else {
                    null
                }
            }

        private val duotoneFallbackFilter: android.graphics.ColorFilter? =
            (style as? TintStyle.Duotone)?.let { tint ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    ColorMatrixColorFilter(colorizeMatrix(tint.light, tint.amount * 0.6f))
                } else {
                    null
                }
            }

        private val duotoneWithBlurCache = mutableMapOf<RenderEffect, RenderEffect>()

        fun apply(iv: ImageView, baseEffect: RenderEffect?) {
            when (style) {
                is TintStyle.None -> {
                    iv.colorFilter = null
                    iv.setEffect(baseEffect)
                }
                is TintStyle.MultiplyDark -> {
                    iv.colorFilter = multiplyFilter
                    iv.setEffect(baseEffect)
                }
                is TintStyle.ScreenLight -> {
                    iv.colorFilter = screenFilter
                    iv.setEffect(baseEffect)
                }
                is TintStyle.Colorize -> {
                    iv.colorFilter = colorizeFilter
                    iv.setEffect(baseEffect)
                }
                is TintStyle.Sepia -> {
                    iv.colorFilter = sepiaFilter
                    iv.setEffect(baseEffect)
                }
                is TintStyle.Duotone -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && duotoneEffect != null) {
                        val effect = if (baseEffect != null) {
                            duotoneWithBlurCache.getOrPut(baseEffect) {
                                RenderEffect.createChainEffect(duotoneEffect, baseEffect)
                            }
                        } else {
                            duotoneEffect
                        }
                        iv.setEffect(effect)
                        iv.colorFilter = null
                    } else {
                        iv.colorFilter = duotoneFallbackFilter
                        iv.setEffect(baseEffect)
                    }
                }
            }
        }

        private fun ImageView.setEffect(effect: RenderEffect?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(effect)
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
        private const val HANDWRITING_FLIP_HALF_DURATION = 140L
        private const val HANDWRITING_CAMERA_DISTANCE = 8000f

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

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<CardItem>() {
            override fun areItemsTheSame(oldItem: CardItem, newItem: CardItem): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: CardItem, newItem: CardItem): Boolean =
                oldItem == newItem
        }
    }
}

private const val HANDWRITING_PREFETCH_DISTANCE = 2

private fun CardItem.deepCopy(): CardItem = copy(
    bg = when (val background = bg) {
        is BgImage.Res -> background.copy()
        is BgImage.UriRef -> background.copy()
        null -> null
    },
    image = image?.copy(),
    handwriting = handwriting?.let { content ->
        content.copy(
            options = content.options.copy(),
            back = content.back?.let { back ->
                HandwritingSide(back.path, back.options.copy(), back.recognizedText)
            }
        )
    },
    imageHandwriting = imageHandwriting?.let { HandwritingSide(it.path, it.options.copy(), it.recognizedText) },
    relativeTimeText = relativeTimeText
)
