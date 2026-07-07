package com.example.timescapedemo.filament

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.TextureView
import android.view.animation.PathInterpolator
import androidx.core.view.isVisible

/**
 * Dedicated Filament flip surface. This keeps the rest of Timescape in standard Android Views,
 * while isolating true 3D card rendering into a reusable component.
 */
class FilamentCardFlipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private val renderer = FilamentCardFlipRenderer(context)
    private var flipAnimator: ValueAnimator? = null

    init {
        surfaceTextureListener = this
        isOpaque = false
        isVisible = false
    }

    fun startFlip(
        frontBitmap: Bitmap,
        backBitmap: Bitmap,
        durationMs: Long = 520L,
        onEnd: (() -> Unit)? = null
    ) {
        if (!isAvailable) return
        isVisible = true
        renderer.updateCardTextures(frontBitmap, backBitmap)
        flipAnimator?.cancel()
        flipAnimator = ValueAnimator.ofFloat(0f, 1.02f, 1f).apply {
            duration = durationMs
            interpolator = PathInterpolator(0.18f, 0.86f, 0.22f, 1f)
            addUpdateListener { animator ->
                val progress = (animator.animatedValue as Float).coerceIn(0f, 1.03f)
                renderer.setFlipProgress(progress)
                renderer.renderFrame()
            }
            doOnEnd {
                isVisible = false
                onEnd?.invoke()
            }
            start()
        }
    }

    fun stopFlip() {
        flipAnimator?.cancel()
        flipAnimator = null
        isVisible = false
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        renderer.attach(android.view.Surface(surface))
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopFlip()
        renderer.detachSurface()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        stopFlip()
        renderer.destroy()
        super.onDetachedFromWindow()
    }

    private inline fun ValueAnimator.doOnEnd(crossinline block: () -> Unit) {
        addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) = Unit
            override fun onAnimationEnd(animation: android.animation.Animator) = block()
            override fun onAnimationCancel(animation: android.animation.Animator) = Unit
            override fun onAnimationRepeat(animation: android.animation.Animator) = Unit
        })
    }
}
