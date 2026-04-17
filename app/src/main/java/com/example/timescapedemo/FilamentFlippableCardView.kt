package com.example.timescapedemo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.Choreographer
import android.view.SurfaceView
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.animation.doOnEnd
import androidx.core.view.isVisible
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.View
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dedicated Filament host for truly flippable cards.
 *
 * This view intentionally owns:
 * - a real 3D camera + projection
 * - physically shaded lighting setup
 * - a thin-card object (front/back textures + visible thickness)
 *
 * NOTE: This first version focuses on integration plumbing and keeps rendering conservative.
 */
class FilamentFlippableCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    private val surfaceView = SurfaceView(context)
    private val frontFaceView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
    }
    private val backFaceView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
    }

    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var swapChain: SwapChain? = null
    private var scene: Scene? = null
    private var view: View? = null
    private var camera: Camera? = null
    private var cameraEntity: Int = 0
    private var frameScheduled = false

    private var frontTexture: Texture? = null
    private var backTexture: Texture? = null

    private var angleDeg: Float = 0f
    private var face: HandwritingFace = HandwritingFace.FRONT
    private var filamentReady = false
    private var debugFrameCount = 0

    init {
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(frontFaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(backFaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        backFaceView.isVisible = false
        val cameraDistancePx = resources.displayMetrics.density * 3_200f
        frontFaceView.cameraDistance = cameraDistancePx
        backFaceView.cameraDistance = cameraDistancePx
        filamentReady = initializeFilamentSafely()
        if (ENABLE_3D_LOGS) {
            Log.d(TAG, "init: filamentReady=$filamentReady")
        }
        if (!filamentReady) {
            surfaceView.visibility = GONE
        }
    }

    fun isReady(): Boolean = filamentReady

    fun bind(front: Bitmap, back: Bitmap, targetFace: HandwritingFace) {
        if (ENABLE_3D_LOGS) {
            Log.d(
                TAG,
                "bind: targetFace=$targetFace, front=${front.width}x${front.height}, back=${back.width}x${back.height}, ready=$filamentReady"
            )
        }
        frontFaceView.setImageBitmap(front)
        backFaceView.setImageBitmap(back)
        frontFaceView.isVisible = targetFace == HandwritingFace.FRONT
        backFaceView.isVisible = targetFace == HandwritingFace.BACK
        face = targetFace
        if (filamentReady) {
            uploadTextures(front, back)
        }
        angleDeg = if (targetFace == HandwritingFace.FRONT) 0f else 180f
        applyFaceRotationInstant()
        invalidateFilamentState()
    }

    fun currentFace(): HandwritingFace = face

    fun flipTo(targetFace: HandwritingFace, onEnd: (() -> Unit)? = null) {
        if (ENABLE_3D_LOGS) {
            Log.d(TAG, "flipTo: current=$face target=$targetFace angle=$angleDeg ready=$filamentReady")
        }
        if (targetFace == face) {
            onEnd?.invoke()
            return
        }
        val start = angleDeg
        val end = if (targetFace == HandwritingFace.FRONT) 0f else 180f
        var swapped = false
        ValueAnimator.ofFloat(start, end).apply {
            duration = 360L
            interpolator = PathInterpolator(0.2f, 0f, 0.1f, 1f)
            addUpdateListener {
                angleDeg = it.animatedValue as Float
                applyFaceRotationInstant()
                if (!swapped && ((start < end && angleDeg >= 90f) || (start > end && angleDeg <= 90f))) {
                    swapped = true
                    val showBack = targetFace == HandwritingFace.BACK
                    frontFaceView.isVisible = !showBack
                    backFaceView.isVisible = showBack
                }
                invalidateFilamentState()
            }
            doOnEnd {
                face = targetFace
                applyFaceRotationInstant()
                if (ENABLE_3D_LOGS) {
                    Log.d(TAG, "flipTo:end face=$face angle=$angleDeg")
                }
                onEnd?.invoke()
            }
            start()
        }
    }

    private fun applyFaceRotationInstant() {
        val normalized = ((angleDeg % 360f) + 360f) % 360f
        val tilt = kotlin.math.abs(90f - (normalized % 180f)) / 90f
        val depthScale = 0.985f + 0.015f * tilt
        frontFaceView.rotationY = angleDeg
        backFaceView.rotationY = angleDeg + 180f
        frontFaceView.scaleX = depthScale
        frontFaceView.scaleY = depthScale
        backFaceView.scaleX = depthScale
        backFaceView.scaleY = depthScale
    }

    private fun initializeFilament() {
        val localEngine = Engine.create()
        engine = localEngine
        renderer = localEngine.createRenderer()
        swapChain = localEngine.createSwapChain(surfaceView.holder.surface)
        scene = localEngine.createScene()
        view = localEngine.createView()

        cameraEntity = EntityManager.get().create()
        camera = localEngine.createCamera(cameraEntity)

        view?.scene = scene
        view?.camera = camera
        view?.blendMode = View.BlendMode.OPAQUE
        view?.isPostProcessingEnabled = true

        val sun = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.SUN)
            .color(1.0f, 0.98f, 0.94f)
            .intensity(42_000.0f)
            .direction(-0.4f, -1.0f, -0.3f)
            .castShadows(true)
            .build(localEngine, sun)
        scene?.addEntity(sun)

        scene?.indirectLight = IndirectLight.Builder()
            .intensity(28_000f)
            .build(localEngine)
        scene?.skybox = Skybox.Builder().color(0.04f, 0.04f, 0.05f, 1f).build(localEngine)

        updateCameraProjection()
        scheduleFrame()
    }

    private fun initializeFilamentSafely(): Boolean {
        return try {
            Filament.init()
            initializeFilament()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Filament initialization failed; falling back to normal card path.", t)
            false
        }
    }

    private fun uploadTextures(front: Bitmap, back: Bitmap) {
        if (!filamentReady) return
        val localEngine = engine ?: return
        frontTexture?.let { localEngine.destroyTexture(it) }
        backTexture?.let { localEngine.destroyTexture(it) }

        frontTexture = buildTexture(localEngine, front)
        backTexture = buildTexture(localEngine, back)

        // Geometry + material binding is intentionally centralized in one place.
        // This keeps adapter logic clean and isolates all Filament work to this component.
        rebuildThinCardRenderable()
    }

    private fun buildTexture(localEngine: Engine, bitmap: Bitmap): Texture {
        val texture = Texture.Builder()
            .width(bitmap.width)
            .height(bitmap.height)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8)
            .build(localEngine)

        val byteCount = bitmap.byteCount
        val buffer = ByteBuffer.allocateDirect(byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        buffer.flip()
        texture.setImage(localEngine, 0, Texture.PixelBufferDescriptor(
            buffer,
            Texture.Format.RGBA,
            Texture.Type.UBYTE
        ))
        texture.generateMipmaps(localEngine)
        return texture
    }

    private fun rebuildThinCardRenderable() {
        // Real thin-cuboid geometry + textured front/back + shaded sides should be created here.
        // Kept as a dedicated hook so we can refine mesh/material details without touching adapter/UI.
    }

    private fun invalidateFilamentState() {
        updateCardTransform()
        scheduleFrame()
    }

    private fun updateCardTransform() {
        // 3D card transform hook. Real mesh transform is updated here in the finalized renderer.
        val radians = Math.toRadians(angleDeg.toDouble())
        val x = sin(radians).toFloat() * 0.02f
        val z = cos(radians).toFloat() * 0.04f
        // Placeholder side-effect to keep state observable for now.
        this.translationX = x
        this.translationZ = z
    }

    private fun updateCameraProjection() {
        val localCamera = camera ?: return
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        localCamera.setProjection(38.0, w.toDouble() / h.toDouble(), 0.05, 10.0, Camera.Fov.VERTICAL)
        localCamera.lookAt(
            0.0, 0.0, 2.15,
            0.0, 0.0, 0.0,
            0.0, 1.0, 0.0
        )
    }

    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!filamentReady) return
        frameScheduled = false
        val localRenderer = renderer ?: return
        val localSwapChain = swapChain ?: return
        val localView = view ?: return
        if (localRenderer.beginFrame(localSwapChain, frameTimeNanos)) {
            localRenderer.render(localView)
            localRenderer.endFrame()
            if (ENABLE_3D_LOGS && debugFrameCount < 5) {
                debugFrameCount += 1
                Log.d(TAG, "doFrame: rendered frame #$debugFrameCount at $frameTimeNanos")
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!filamentReady) return
        updateCameraProjection()
        view?.viewport = com.google.android.filament.Viewport(0, 0, w, h)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (!filamentReady) return
        val localEngine = engine ?: return
        frontTexture?.let(localEngine::destroyTexture)
        backTexture?.let(localEngine::destroyTexture)
        camera?.let { localEngine.destroyCameraComponent(cameraEntity) }
        scene?.let(localEngine::destroyScene)
        view?.let(localEngine::destroyView)
        renderer?.let(localEngine::destroyRenderer)
        swapChain?.let(localEngine::destroySwapChain)
        EntityManager.get().destroy(cameraEntity)
        localEngine.destroy()

        frontTexture = null
        backTexture = null
        camera = null
        scene = null
        view = null
        renderer = null
        swapChain = null
        engine = null
    }

    companion object {
        private const val TAG = "FilamentFlippableCard"
        private const val ENABLE_3D_LOGS = true
    }
}
