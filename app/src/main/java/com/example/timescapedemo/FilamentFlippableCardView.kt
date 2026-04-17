package com.example.timescapedemo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View as AndroidView
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
import com.google.android.filament.View as FilamentView
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
) : FrameLayout(context, attrs, defStyleAttr), Choreographer.FrameCallback, SurfaceHolder.Callback {

    private val surfaceView = SurfaceView(context)
    private val frontFaceView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private val backFaceView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private val frontShadeView = AndroidView(context).apply {
        setBackgroundColor(0xFF000000.toInt())
        alpha = 0f
    }
    private val backShadeView = AndroidView(context).apply {
        setBackgroundColor(0xFF000000.toInt())
        alpha = 0f
    }
    private val edgeView = AndroidView(context).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x33FFFFFF, 0xCCF2EBDD.toInt(), 0x22FFFFFF)
        )
        alpha = 0f
    }
    private val shadowView = AndroidView(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 26f
            setColor(0x48000000)
        }
        alpha = 0f
    }

    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var swapChain: SwapChain? = null
    private var scene: Scene? = null
    private var view: FilamentView? = null
    private var camera: Camera? = null
    private var cameraEntity: Int = 0
    private var frameScheduled = false

    private var frontTexture: Texture? = null
    private var backTexture: Texture? = null

    private var angleDeg: Float = 0f
    private var face: HandwritingFace = HandwritingFace.FRONT
    private var filamentReady = false
    private var filamentInitAttempted = false
    private var debugFrameCount = 0
    private var isSurfaceAvailable = false

    init {
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(shadowView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(frontFaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(backFaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(frontShadeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(backShadeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(edgeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        backFaceView.isVisible = false
        val cameraDistancePx = resources.displayMetrics.density * 3_200f
        frontFaceView.cameraDistance = cameraDistancePx
        backFaceView.cameraDistance = cameraDistancePx
        frontShadeView.cameraDistance = cameraDistancePx
        backShadeView.cameraDistance = cameraDistancePx
        surfaceView.holder.addCallback(this)
        surfaceView.visibility = GONE
    }

    fun isReady(): Boolean {
        ensureFilamentInitialized()
        return filamentReady
    }

    fun bind(front: Bitmap, back: Bitmap, targetFace: HandwritingFace) {
        ensureFilamentInitialized()
        if (ENABLE_3D_LOGS) {
            Log.i(
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
            surfaceView.visibility = VISIBLE
        }
        angleDeg = if (targetFace == HandwritingFace.FRONT) 0f else 180f
        applyFaceRotationInstant()
        invalidateFilamentState()
    }

    fun currentFace(): HandwritingFace = face

    fun setFace(targetFace: HandwritingFace) {
        face = targetFace
        frontFaceView.isVisible = targetFace == HandwritingFace.FRONT
        backFaceView.isVisible = targetFace == HandwritingFace.BACK
        angleDeg = if (targetFace == HandwritingFace.FRONT) 0f else 180f
        applyFaceRotationInstant(1f, 1f)
        invalidateFilamentState()
    }

    fun flipTo(targetFace: HandwritingFace, onEnd: (() -> Unit)? = null) {
        if (ENABLE_3D_LOGS) {
            Log.i(TAG, "flipTo: current=$face target=$targetFace angle=$angleDeg ready=$filamentReady")
        }
        if (targetFace == face) {
            onEnd?.invoke()
            return
        }
        val start = angleDeg
        val end = if (targetFace == HandwritingFace.FRONT) 0f else 180f
        val direction = if (end >= start) 1f else -1f
        var swapped = false
        ValueAnimator.ofFloat(start, end).apply {
            duration = HAND_FLIP_DURATION_MS
            interpolator = PathInterpolator(0.18f, 0.02f, 0.12f, 1f)
            addUpdateListener {
                angleDeg = it.animatedValue as Float
                val rawProgress = it.animatedFraction.coerceIn(0f, 1f)
                val handProgress = handEasedProgress(rawProgress)
                applyFaceRotationInstant(handProgress, direction)
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
                applyFaceRotationInstant(1f, direction)
                if (ENABLE_3D_LOGS) {
                    Log.i(TAG, "flipTo:end face=$face angle=$angleDeg")
                }
                onEnd?.invoke()
            }
            start()
        }
    }

    private fun applyFaceRotationInstant(progress: Float = 0f, direction: Float = 1f) {
        val normalized = ((angleDeg % 360f) + 360f) % 360f
        val edge = kotlin.math.abs(90f - (normalized % 180f)) / 90f
        val depthScale = 0.982f + 0.018f * edge
        val liftPx = resources.displayMetrics.density * HAND_LIFT_DP
        val arc = (kotlin.math.sin(progress * Math.PI)).toFloat()
        val tiltX = HAND_MAX_TILT_X_DEG * arc
        val driftY = -liftPx * arc
        val rollZ = HAND_MAX_ROLL_DEG * arc * -direction

        frontFaceView.rotationY = angleDeg
        backFaceView.rotationY = angleDeg + 180f
        frontFaceView.rotationX = tiltX
        backFaceView.rotationX = tiltX
        frontFaceView.rotation = rollZ
        backFaceView.rotation = rollZ
        frontFaceView.translationY = driftY
        backFaceView.translationY = driftY
        frontFaceView.scaleX = depthScale
        frontFaceView.scaleY = depthScale
        backFaceView.scaleX = depthScale
        backFaceView.scaleY = depthScale

        val pivotX = width * if (direction >= 0f) HAND_PIVOT_RIGHT_FRACTION else HAND_PIVOT_LEFT_FRACTION
        val pivotY = height * HAND_PIVOT_Y_FRACTION
        for (v in listOf(frontFaceView, backFaceView, frontShadeView, backShadeView)) {
            v.pivotX = pivotX
            v.pivotY = pivotY
        }

        val rad = Math.toRadians(normalized.toDouble())
        val sideExposure = kotlin.math.abs(kotlin.math.sin(rad)).toFloat().coerceIn(0f, 1f)
        val facing = kotlin.math.abs(kotlin.math.cos(rad)).toFloat().coerceIn(0f, 1f)

        val edgeWidthPx = (resources.displayMetrics.density * HAND_EDGE_MAX_DP * sideExposure).coerceAtLeast(1f)
        edgeView.scaleX = edgeWidthPx / width.coerceAtLeast(1)
        edgeView.alpha = 0.88f * sideExposure
        edgeView.translationX = (width * 0.5f) + (direction * width * 0.06f * (1f - facing)) - (edgeWidthPx * 0.5f)

        val darken = ((1f - facing) * HAND_MAX_DARKEN_ALPHA).coerceIn(0f, HAND_MAX_DARKEN_ALPHA)
        frontShadeView.alpha = darken
        backShadeView.alpha = darken * 0.92f
        frontShadeView.rotationY = frontFaceView.rotationY
        backShadeView.rotationY = backFaceView.rotationY
        frontShadeView.rotationX = frontFaceView.rotationX
        backShadeView.rotationX = backFaceView.rotationX
        frontShadeView.rotation = frontFaceView.rotation
        backShadeView.rotation = backFaceView.rotation
        frontShadeView.translationY = frontFaceView.translationY
        backShadeView.translationY = backFaceView.translationY

        shadowView.alpha = (HAND_SHADOW_BASE_ALPHA + sideExposure * 0.24f).coerceIn(0f, 0.5f)
        shadowView.scaleX = 0.93f + sideExposure * 0.1f
        shadowView.scaleY = 0.88f + sideExposure * 0.1f
        shadowView.translationY = resources.displayMetrics.density * (HAND_SHADOW_DROP_DP + arc * 4.2f)
        shadowView.translationX = -direction * resources.displayMetrics.density * 2.6f * arc
    }

    private fun handEasedProgress(raw: Float): Float {
        val clamped = raw.coerceIn(0f, 1f)
        return if (clamped < 0.5f) {
            2f * clamped * clamped
        } else {
            1f - ((-2f * clamped + 2f) * (-2f * clamped + 2f)) / 2f
        }
    }

    private fun initializeFilament() {
        val localEngine = Engine.create()
        engine = localEngine
        renderer = localEngine.createRenderer()
        scene = localEngine.createScene()
        view = localEngine.createView()

        cameraEntity = EntityManager.get().create()
        camera = localEngine.createCamera(cameraEntity)

        view?.scene = scene
        view?.camera = camera
        view?.blendMode = FilamentView.BlendMode.OPAQUE
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
        ensureSwapChain()
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

    private fun ensureFilamentInitialized() {
        if (filamentInitAttempted) return
        filamentInitAttempted = true
        filamentReady = initializeFilamentSafely()
        if (ENABLE_3D_LOGS) {
            Log.i(TAG, "init: filamentReady=$filamentReady")
        }
        if (!filamentReady) {
            surfaceView.visibility = GONE
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
        if (!isSurfaceAvailable || swapChain == null) return
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
                Log.i(TAG, "doFrame: rendered frame #$debugFrameCount at $frameTimeNanos")
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
        surfaceView.holder.removeCallback(this)
        frameScheduled = false
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

    override fun surfaceCreated(holder: SurfaceHolder) {
        isSurfaceAvailable = true
        if (ENABLE_3D_LOGS) {
            Log.i(TAG, "surfaceCreated: valid=${holder.surface?.isValid == true}")
        }
        ensureSwapChain()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (ENABLE_3D_LOGS) {
            Log.i(TAG, "surfaceChanged: ${width}x$height format=$format")
        }
        updateCameraProjection()
        view?.viewport = com.google.android.filament.Viewport(0, 0, width, height)
        scheduleFrame()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceAvailable = false
        frameScheduled = false
        val localEngine = engine
        val localSwap = swapChain
        if (localEngine != null && localSwap != null) {
            if (ENABLE_3D_LOGS) {
                Log.i(TAG, "surfaceDestroyed: destroying swap chain")
            }
            localEngine.destroySwapChain(localSwap)
            swapChain = null
        }
    }

    private fun ensureSwapChain() {
        if (!filamentReady) return
        if (!isSurfaceAvailable) return
        if (swapChain != null) return
        val localEngine = engine ?: return
        val surface = surfaceView.holder.surface
        if (surface == null || !surface.isValid) {
            if (ENABLE_3D_LOGS) {
                Log.i(TAG, "ensureSwapChain: surface invalid, postponing")
            }
            return
        }
        try {
            swapChain = localEngine.createSwapChain(surface)
            if (ENABLE_3D_LOGS) {
                Log.i(TAG, "ensureSwapChain: created successfully")
            }
            scheduleFrame()
        } catch (t: Throwable) {
            Log.e(TAG, "ensureSwapChain: failed to create swapchain", t)
        }
    }

    companion object {
        private const val TAG = "FilamentFlippableCard"
        private const val ENABLE_3D_LOGS = true
        private const val HAND_FLIP_DURATION_MS = 520L
        private const val HAND_MAX_TILT_X_DEG = 6.5f
        private const val HAND_MAX_ROLL_DEG = 1.4f
        private const val HAND_LIFT_DP = 3.5f
        private const val HAND_PIVOT_RIGHT_FRACTION = 0.64f
        private const val HAND_PIVOT_LEFT_FRACTION = 0.36f
        private const val HAND_PIVOT_Y_FRACTION = 0.58f
        private const val HAND_EDGE_MAX_DP = 3.2f
        private const val HAND_MAX_DARKEN_ALPHA = 0.17f
        private const val HAND_SHADOW_BASE_ALPHA = 0.16f
        private const val HAND_SHADOW_DROP_DP = 9.5f
    }
}
