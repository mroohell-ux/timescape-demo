package com.example.timescapedemo

import android.content.Context
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
        mediaPlayer?.setOnPreparedListener(null)
        mediaPlayer?.reset()
        mediaPlayer?.release()
        mediaPlayer = null
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
                preparedListener?.invoke(mp)
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

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        stopPlayback()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
