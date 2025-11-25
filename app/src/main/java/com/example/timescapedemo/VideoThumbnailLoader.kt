package com.example.timescapedemo

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.random.Random

object VideoThumbnailLoader {
    sealed class Result {
        data class Success(val bitmap: Bitmap) : Result()
        data class PermissionDenied(val exception: SecurityException) : Result()
        object NotFound : Result()
        data class Error(val throwable: Throwable?) : Result()
    }

    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(cacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private val executor: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "VideoThumbnailLoader").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inFlight = mutableMapOf<String, MutableList<(Result) -> Unit>>()
    private val lock = Any()

    fun load(
        context: Context,
        imageView: ImageView,
        video: CardVideo,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        onResult: (Result) -> Unit
    ) {
        val appContext = context.applicationContext
        imageView.setTag(R.id.tag_video_uri, video.uri)
        val cacheKey = cacheKey(video, targetWidth, targetHeight)
        cache.get(cacheKey)?.let { cached ->
            onResult(Result.Success(cached))
            return
        }

        val callback: (Result) -> Unit = { result ->
            val currentTag = imageView.getTag(R.id.tag_video_uri)
            if (currentTag == video.uri) {
                onResult(result)
            }
        }
        val shouldStart = registerCallback(cacheKey, callback)
        if (shouldStart) {
            executor.execute {
                val result = try {
                    val bitmap = decode(appContext, video, targetWidth, targetHeight)
                    if (bitmap != null) {
                        cache.put(cacheKey, bitmap)
                        Result.Success(bitmap)
                    } else {
                        Result.Error(null)
                    }
                } catch (se: SecurityException) {
                    Result.PermissionDenied(se)
                } catch (io: IOException) {
                    Result.Error(io)
                } catch (oom: OutOfMemoryError) {
                    Result.Error(oom)
                } catch (t: Throwable) {
                    Result.Error(t)
                }
                val callbacks = finishCallbacks(cacheKey)
                if (callbacks.isEmpty()) return@execute
                mainHandler.post { callbacks.forEach { it(result) } }
            }
        }
    }

    fun clear(imageView: ImageView) {
        imageView.setTag(R.id.tag_video_uri, null)
    }

    private fun decode(
        context: Context,
        video: CardVideo,
        targetWidth: Int?,
        targetHeight: Int?
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            when (video.uri.scheme?.lowercase()) {
                ContentResolver.SCHEME_FILE -> {
                    val path = video.uri.path ?: return null
                    retriever.setDataSource(path)
                }
                else -> retriever.setDataSource(context, video.uri)
            }
            val durationUs = video.durationUs ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.times(1000)
            val frameUs = video.previewFrameMicros ?: durationUs?.let { choosePreviewFrameUs(it) } ?: 0L
            val raw = retriever.getFrameAtTime(frameUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(frameUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(-1)
                ?: return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()?.let { (it % 360 + 360) % 360 } ?: 0
            val oriented = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            } else raw
            if (targetWidth == null || targetHeight == null || targetWidth <= 0 || targetHeight <= 0) {
                return oriented
            }
            val scale = minOf(targetWidth.toFloat() / oriented.width, targetHeight.toFloat() / oriented.height)
            if (scale >= 1f) return oriented
            val scaledW = (oriented.width * scale).roundToInt().coerceAtLeast(1)
            val scaledH = (oriented.height * scale).roundToInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(oriented, scaledW, scaledH, true)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun registerCallback(cacheKey: String, callback: (Result) -> Unit): Boolean {
        synchronized(lock) {
            val existing = inFlight[cacheKey]
            if (existing != null) {
                existing.add(callback)
                return false
            }
            inFlight[cacheKey] = mutableListOf(callback)
            return true
        }
    }

    private fun finishCallbacks(cacheKey: String): List<(Result) -> Unit> {
        return synchronized(lock) { inFlight.remove(cacheKey)?.toList() ?: emptyList() }
    }

    private fun cacheKey(video: CardVideo, targetWidth: Int?, targetHeight: Int?): String {
        val widthBucket = bucketDimension(targetWidth)
        val heightBucket = bucketDimension(targetHeight)
        val frameBucket = video.previewFrameMicros ?: 0L
        return "${video.uri}@${widthBucket}x${heightBucket}@${frameBucket}"
    }

    private fun bucketDimension(dimension: Int?): Int {
        if (dimension == null || dimension <= 0) return 0
        val bucketSize = 32
        return ((dimension + bucketSize - 1) / bucketSize) * bucketSize
    }

    private fun cacheSize(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory()
        val target = maxMemory / 8L
        val minimum = 8L * 1024L * 1024L
        val desired = target.coerceAtLeast(minimum)
        return desired.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun choosePreviewFrameUs(durationUs: Long): Long {
        val start = (durationUs * 0.1).toLong().coerceAtLeast(1L)
        val endExclusive = (durationUs * 0.9).toLong().coerceAtLeast(start + 1)
        return if (endExclusive > start) {
            Random.nextLong(start, endExclusive)
        } else {
            durationUs / 2
        }
    }
}
