package com.example.timescapedemo

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.widget.Toast
import android.widget.VideoView

class InlineVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : VideoView(context, attrs, defStyleAttr) {
    private val audioManager = requireNotNull(context.getSystemService(AudioManager::class.java))
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (hasAudioFocus && change != AudioManager.AUDIOFOCUS_GAIN) {
                pause()
                onPlaybackInterrupted?.invoke()
            }
        }, Handler(Looper.getMainLooper()))
        .build()
    private var preparedPlayer: MediaPlayer? = null
    private var hasAudioTrack = false
    private var muted = false
    private var playbackAllowed = false
    private var preparedListener: MediaPlayer.OnPreparedListener? = null
    private var completionListener: MediaPlayer.OnCompletionListener? = null
    private var errorListener: MediaPlayer.OnErrorListener? = null

    var hasAudioFocus = false
        private set
    var isPlaybackRequested = false
        private set
    var onPlaybackInterrupted: (() -> Unit)? = null

    init {
        // VideoView otherwise requests focus while preparing, even when paused or muted.
        setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
        super.setOnPreparedListener { player ->
            preparedPlayer = player
            val tracks = player.trackInfo
            hasAudioTrack = tracks == null ||
                tracks.any { it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO }
            if (tracks == null) Log.w(TAG, "Video track information unavailable; treating as audible")
            applyVolume()
            preparedListener?.onPrepared(player)
            if (isPlaybackRequested && !isPlaying) start()
        }
        super.setOnCompletionListener { player ->
            isPlaybackRequested = false
            abandonAudioFocus()
            onPlaybackInterrupted?.invoke()
            completionListener?.onCompletion(player)
        }
        super.setOnErrorListener { player, what, extra ->
            isPlaybackRequested = false
            preparedPlayer = null
            abandonAudioFocus()
            onPlaybackInterrupted?.invoke()
            errorListener?.onError(player, what, extra) ?: false
        }
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                preparedPlayer = null
                pause()
            }
        })
    }

    override fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener?) {
        preparedListener = listener
    }

    override fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener?) {
        completionListener = listener
    }

    override fun setOnErrorListener(listener: MediaPlayer.OnErrorListener?) {
        errorListener = listener
    }

    override fun setVideoURI(uri: Uri?, headers: Map<String, String>?) {
        pause()
        preparedPlayer = null
        hasAudioTrack = false
        super.setVideoURI(uri, headers)
    }

    fun setPlaybackAllowed(allowed: Boolean) {
        playbackAllowed = allowed
        if (!allowed) pause()
    }

    fun setMuted(value: Boolean) {
        if (muted == value) return
        muted = value
        if (muted) {
            applyVolume()
            abandonAudioFocus()
        } else if (isPlaying && !requestAudioFocusIfNeeded()) {
            interruptPlayback()
        } else {
            applyVolume()
        }
    }

    override fun start() {
        if (!playbackAllowed || !isAttachedToWindow || !isShown ||
            windowVisibility != View.VISIBLE
        ) {
            pause()
            return
        }
        isPlaybackRequested = true
        if (preparedPlayer == null) return
        if (!requestAudioFocusIfNeeded()) {
            interruptPlayback()
            return
        }
        applyVolume()
        super.start()
    }

    override fun pause() {
        isPlaybackRequested = false
        super.pause()
        abandonAudioFocus()
    }

    override fun stopPlayback() {
        isPlaybackRequested = false
        preparedPlayer = null
        abandonAudioFocus()
        super.stopPlayback()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != View.VISIBLE) pause()
        super.onWindowVisibilityChanged(visibility)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        if (visibility != View.VISIBLE) pause()
        super.onVisibilityChanged(changedView, visibility)
    }

    override fun onDetachedFromWindow() {
        stopPlayback()
        super.onDetachedFromWindow()
    }

    private fun requestAudioFocusIfNeeded(): Boolean {
        if (muted || !hasAudioTrack || hasAudioFocus) return true
        hasAudioFocus = audioManager.requestAudioFocus(audioFocusRequest) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        hasAudioFocus = false
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    private fun applyVolume() {
        val volume = if (muted) 0f else 1f
        preparedPlayer?.setVolume(volume, volume)
    }

    private fun interruptPlayback() {
        pause()
        Log.w(TAG, "Audio focus request denied; leaving video paused")
        Toast.makeText(context, R.string.video_audio_focus_unavailable, Toast.LENGTH_SHORT).show()
        onPlaybackInterrupted?.invoke()
    }

    private companion object {
        const val TAG = "InlineVideoView"
    }
}
