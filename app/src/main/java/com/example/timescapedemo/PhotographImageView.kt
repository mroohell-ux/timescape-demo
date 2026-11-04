package com.example.timescapedemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.util.AttributeSet
import android.widget.ImageView
import kotlin.math.roundToInt

/** Photographic softening and transparent edges without hardware offscreen effect surfaces. */
class PhotographImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {
    private var softness = 0f
    private var fadingEdges = false
    private var source: Bitmap? = null
    private var texture: BitmapShader? = null
    private var softener: RuntimeShader? = null
    private val imageToView = Matrix()
    private val sampleMatrix = Matrix()
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fadeMatrix = Matrix()
    private val fadeMask = LinearGradient(
        0f, 0f, 1f, 0f,
        intArrayOf(Color.argb(EDGE_OPACITY, 1f, 1f, 1f), Color.WHITE, Color.WHITE,
            Color.argb(EDGE_OPACITY, 1f, 1f, 1f)),
        floatArrayOf(0f, SIDE_FADE_FRACTION, 1f - SIDE_FADE_FRACTION, 1f), Shader.TileMode.CLAMP
    )
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = fadeMask
        blendMode = BlendMode.DST_IN
    }

    fun setFadingEdges(enabled: Boolean) {
        if (fadingEdges == enabled) return
        fadingEdges = enabled
        clearShader()
        updateSofteningEffect()
        invalidate()
    }

    fun setSoftening(radiusPx: Float) {
        require(radiusPx.isFinite() && radiusPx >= 0f)
        if (softness == radiusPx) return
        softness = radiusPx
        clearShader()
        updateSofteningEffect()
        invalidate()
    }

    private fun updateSofteningEffect() {
        setRenderEffect(
            if (Build.VERSION.SDK_INT < 33 && softness > 0f && !fadingEdges) {
                RenderEffect.createBlurEffect(softness, softness, Shader.TileMode.CLAMP)
            } else null
        )
    }

    override fun onDraw(canvas: Canvas) {
        val image = drawable as? BitmapDrawable
        if ((!fadingEdges && softness == 0f) || Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated ||
            scaleType != ScaleType.CENTER_CROP || image == null || image.bounds.isEmpty
        ) {
            if (fadingEdges && scaleType == ScaleType.CENTER_CROP &&
                image != null && !image.bounds.isEmpty &&
                width > paddingLeft + paddingRight && height > paddingTop + paddingBottom
            ) {
                drawSoftenedSides(canvas, image)
                return
            }
            if (!fadingEdges || width <= paddingLeft + paddingRight || height <= paddingTop + paddingBottom) {
                super.onDraw(canvas)
            } else {
                val left = paddingLeft.toFloat()
                val top = paddingTop.toFloat()
                val right = (width - paddingRight).toFloat()
                val bottom = (height - paddingBottom).toFloat()
                val layer = canvas.saveLayer(left, top, right, bottom, null)
                try {
                    super.onDraw(canvas)
                    drawFadingMask(canvas, left, top, right, bottom)
                } finally {
                    canvas.restoreToCount(layer)
                }
            }
            return
        }
        val shader = softener ?: RuntimeShader(SOFTENING_SHADER).apply {
            // This kernel's per-axis variance is one quarter of its sampling offset squared.
            setFloatUniform("radius", softness * 2f)
            setFloatUniform("fading", if (fadingEdges) 1f else 0f)
            setFloatUniform("sideFadeWidth", SIDE_FADE_FRACTION)
            setFloatUniform("edgeOpacity", EDGE_OPACITY)
        }.also { softener = it }
        shader.setFloatUniform("origin", paddingLeft.toFloat(), paddingTop.toFloat())
        shader.setFloatUniform(
            "extent", (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f),
            (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(1f)
        )
        val sampling = prepareSampling(image)
        shader.setInputShader("photo", sampling)
        imagePaint.shader = shader
        imagePaint.colorFilter = image.colorFilter
        imagePaint.alpha = image.alpha
        canvas.drawRect(
            paddingLeft.toFloat(), paddingTop.toFloat(),
            (width - paddingRight).toFloat(), (height - paddingBottom).toFloat(), imagePaint
        )
    }

    private fun prepareSampling(image: BitmapDrawable): BitmapShader {
        val bitmap = image.bitmap
        if (source !== bitmap) {
            source = bitmap
            texture = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                if (Build.VERSION.SDK_INT >= 33) setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            }
        }
        imageToView.setScale(
            image.bounds.width().toFloat() / bitmap.width,
            image.bounds.height().toFloat() / bitmap.height
        )
        imageToView.postTranslate(image.bounds.left.toFloat(), image.bounds.top.toFloat())
        imageToView.postConcat(imageMatrix)
        imageToView.postTranslate(paddingLeft.toFloat(), paddingTop.toFloat())
        return requireNotNull(texture).also { it.setLocalMatrix(imageToView) }
    }

    private fun drawFadingMask(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        fadeMatrix.setScale(right - left, 1f)
        fadeMatrix.postTranslate(left, 0f)
        fadeMask.setLocalMatrix(fadeMatrix)
        canvas.drawRect(left, top, right, bottom, fadePaint)
    }

    private fun drawSoftenedSides(canvas: Canvas, image: BitmapDrawable) {
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = (width - paddingRight).toFloat()
        val bottom = (height - paddingBottom).toFloat()
        val extent = right - left
        val edgeWidth = (extent * SIDE_FADE_FRACTION).roundToInt()
        val sampling = prepareSampling(image)
        imagePaint.shader = sampling
        imagePaint.colorFilter = image.colorFilter
        imagePaint.alpha = image.alpha
        val saved = canvas.saveLayer(left, top, right, bottom, null)
        try {
            if (softness == 0f) {
                canvas.drawRect(left, top, right, bottom, imagePaint)
            } else {
                canvas.drawRect(left + edgeWidth, top, right - edgeWidth, bottom, imagePaint)
                // Bounded strips approximate the kernel for API 31/32 and software flip snapshots.
                val bands = minOf(edgeWidth, 24)
                val sideAlpha = (image.alpha / 8f).roundToInt()
                imagePaint.blendMode = BlendMode.PLUS
                for (side in 0..1) for (band in 0 until bands) {
                    val start = edgeWidth * band / bands
                    val end = edgeWidth * (band + 1) / bands
                    val x0 = if (side == 0) left + start else right - end
                    val x1 = if (side == 0) left + end else right - start
                    val x = ((x0 + x1) / 2f - left) / extent
                    val weight = 1f - (minOf(x, 1f - x) / SIDE_FADE_FRACTION).coerceIn(0f, 1f)
                    val offset = softness * 2f * weight
                    for (tap in 0..4) {
                        val dx = when (tap) { 1 -> offset; 2 -> -offset; else -> 0f }
                        val dy = when (tap) { 3 -> offset; 4 -> -offset; else -> 0f }
                        sampleMatrix.set(imageToView)
                        sampleMatrix.postTranslate(dx, dy)
                        sampling.setLocalMatrix(sampleMatrix)
                        imagePaint.alpha = if (tap == 0) image.alpha - 4 * sideAlpha else sideAlpha
                        canvas.drawRect(x0, top, x1, bottom, imagePaint)
                    }
                }
            }
            drawFadingMask(canvas, left, top, right, bottom)
        } finally {
            imagePaint.blendMode = null
            sampling.setLocalMatrix(imageToView)
            canvas.restoreToCount(saved)
        }
    }

    private fun clearShader() {
        imagePaint.shader = null
        softener = null
        texture = null
        source = null
    }

    override fun onDetachedFromWindow() {
        clearShader()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val SIDE_FADE_FRACTION = .24f
        private const val EDGE_OPACITY = .20f
        private const val SOFTENING_SHADER = """
            uniform shader photo;
            uniform float radius;
            uniform float fading;
            uniform float sideFadeWidth;
            uniform float edgeOpacity;
            uniform float2 origin;
            uniform float2 extent;
            half4 main(float2 p) {
                float x = (p.x - origin.x) / extent.x;
                float sideWeight = 1.0 - clamp(min(x, 1.0 - x) / sideFadeWidth, 0.0, 1.0);
                float offset = radius * mix(1.0, sideWeight, fading);
                half4 face = photo.eval(p);
                if (offset > 0.0) {
                    face = face * 0.5 +
                        (photo.eval(p + float2(offset, 0)) +
                         photo.eval(p - float2(offset, 0)) +
                         photo.eval(p + float2(0, offset)) +
                         photo.eval(p - float2(0, offset))) * 0.125;
                }
                float opacity = mix(1.0, edgeOpacity, sideWeight);
                return face * mix(1.0, opacity, fading);
            }
        """
    }
}
