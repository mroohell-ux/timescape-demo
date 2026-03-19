package com.example.timescapedemo

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView

/**
 * TextureView-backed inline video view so rotation/scale transforms apply reliably.
 * VideoView uses SurfaceView internally, which does not transform predictably.
 */
class InlineVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr), TextureView.SurfaceTextureListener {

    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private var sourceUri: Uri? = null
    private var preparedListener: ((MediaPlayer) -> Unit)? = null
    private var isPrepared = false
    private var videoWidthPx: Int = 0
    private var videoHeightPx: Int = 0
    private var videoRotationDegrees: Int = 0

    init {
        surfaceTextureListener = this
    }

    fun setVideoURI(uri: Uri) {
        sourceUri = uri
        preparePlayback()
    }

    fun setOnPreparedListener(listener: ((MediaPlayer) -> Unit)?) {
        preparedListener = listener
    }

    fun start() {
        mediaPlayer?.takeIf { isPrepared }?.start()
    }

    fun pause() {
        mediaPlayer?.takeIf { isPrepared }?.pause()
    }

    fun seekTo(positionMs: Int) {
        mediaPlayer?.takeIf { isPrepared }?.seekTo(positionMs)
    }

    fun stopPlayback() {
        isPrepared = false
        videoWidthPx = 0
        videoHeightPx = 0
        videoRotationDegrees = 0
        mediaPlayer?.setOnPreparedListener(null)
        mediaPlayer?.reset()
        mediaPlayer?.release()
        mediaPlayer = null
        setTransform(null)
    }

    fun setVideoRotationDegrees(rotationDegrees: Int) {
        videoRotationDegrees = ((rotationDegrees % 360) + 360) % 360
        applyAspectTransform()
    }

    val isPlaying: Boolean
        get() = mediaPlayer?.isPlaying == true

    val duration: Int
        get() = mediaPlayer?.takeIf { isPrepared }?.duration ?: 0

    val currentPosition: Int
        get() = mediaPlayer?.takeIf { isPrepared }?.currentPosition ?: 0

    private fun preparePlayback() {
        val uri = sourceUri ?: return
        val targetSurface = surface ?: return
        stopPlayback()
        val player = MediaPlayer()
        mediaPlayer = player
        runCatching {
            player.setDataSource(context, uri)
            player.setSurface(targetSurface)
            player.setOnPreparedListener { mp ->
                isPrepared = true
                videoWidthPx = mp.videoWidth
                videoHeightPx = mp.videoHeight
                applyAspectTransform()
                preparedListener?.invoke(mp)
            }
            player.setOnVideoSizeChangedListener { _, width, height ->
                videoWidthPx = width
                videoHeightPx = height
                applyAspectTransform()
            }
            player.setOnErrorListener { _, _, _ ->
                isPrepared = false
                true
            }
            player.prepareAsync()
        }.onFailure {
            stopPlayback()
        }
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(surfaceTexture)
        preparePlayback()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyAspectTransform()
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        stopPlayback()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun applyAspectTransform() {
        val vw = width.toFloat().takeIf { it > 0f } ?: return
        val vh = height.toFloat().takeIf { it > 0f } ?: return
        val videoW = videoWidthPx.toFloat().takeIf { it > 0f } ?: return
        val videoH = videoHeightPx.toFloat().takeIf { it > 0f } ?: return
        val normalizedRotation = ((videoRotationDegrees % 360) + 360) % 360
        val src = RectF(0f, 0f, videoW, videoH)
        val dst = RectF(0f, 0f, vw, vh)
        val cx = vw / 2f
        val cy = vh / 2f
        val matrix = Matrix()

        // First, fit the unrotated frame inside the view bounds.
        matrix.setRectToRect(src, dst, Matrix.ScaleToFit.CENTER)

        // Then rotate around the view center when requested.
        if (normalizedRotation != 0) {
            matrix.postRotate(normalizedRotation.toFloat(), cx, cy)
        }

        // Finally, normalize scale/translation so rotated content remains centered and fully visible.
        val mapped = RectF(src)
        matrix.mapRect(mapped)
        val fitScale = kotlin.math.min(vw / mapped.width(), vh / mapped.height())
        if (fitScale.isFinite() && fitScale > 0f) {
            matrix.postScale(fitScale, fitScale, cx, cy)
        }
        val adjusted = RectF(src)
        matrix.mapRect(adjusted)
        matrix.postTranslate(cx - adjusted.centerX(), cy - adjusted.centerY())
        if (!adjusted.width().isFinite() || !adjusted.height().isFinite()) {
            return
        }
        setTransform(matrix)
    }
}
