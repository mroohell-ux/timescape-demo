package com.example.timescapedemo

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

object BackgroundImageLoader {
    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(cacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private val executor: ExecutorService = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "BackgroundImageLoader").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inFlight = mutableMapOf<String, MutableList<(Result) -> Unit>>()
    private val lock = Any()

    sealed class Result {
        data class Success(val bitmap: Bitmap) : Result()
        data class PermissionDenied(val exception: SecurityException) : Result()
        object NotFound : Result()
        data class Error(val throwable: Throwable?) : Result()
    }

    fun load(
        context: Context,
        imageView: ImageView,
        uri: Uri,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        onResult: (Result) -> Unit
    ) {
        val appContext = context.applicationContext
        imageView.setTag(R.id.tag_bg_uri, uri)
        val width = targetWidth?.takeIf { it > 0 }
        val height = targetHeight?.takeIf { it > 0 }
        val cacheKey = cacheKey(uri, width, height)
        cache.get(cacheKey)?.let { cached ->
            onResult(Result.Success(cached))
            return
        }

        val callback: (Result) -> Unit = { result ->
            val currentTag = imageView.getTag(R.id.tag_bg_uri)
            if (currentTag == uri) {
                onResult(result)
            }
        }
        val shouldStart = registerCallback(cacheKey, callback)
        if (shouldStart) {
            enqueueDecode(appContext, cacheKey, uri, width, height)
        }
    }

    fun clear(imageView: ImageView) {
        imageView.setTag(R.id.tag_bg_uri, null)
    }

    private fun enqueueDecode(
        context: Context,
        cacheKey: String,
        uri: Uri,
        targetWidth: Int?,
        targetHeight: Int?
    ) {
        executor.execute {
            val result = try {
                val bitmap = decode(context, uri, targetWidth, targetHeight)
                if (bitmap != null) {
                    cache.put(cacheKey, bitmap)
                    Result.Success(bitmap)
                } else {
                    Result.Error(null)
                }
            } catch (se: SecurityException) {
                Result.PermissionDenied(se)
            } catch (fnf: FileNotFoundException) {
                Result.NotFound
            } catch (oom: OutOfMemoryError) {
                Result.Error(oom)
            } catch (io: IOException) {
                Result.Error(io)
            } catch (t: Throwable) {
                Result.Error(t)
            }
            val callbacks = finishCallbacks(cacheKey)
            if (callbacks.isEmpty()) return@execute
            mainHandler.post {
                callbacks.forEach { it(result) }
            }
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
        return synchronized(lock) {
            inFlight.remove(cacheKey)?.toList() ?: emptyList()
        }
    }

    private fun decode(
        context: Context,
        uri: Uri,
        targetWidth: Int?,
        targetHeight: Int?
    ): Bitmap? {
        val width = targetWidth
        val height = targetHeight
        val options = if (width != null && height != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream(context, uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            val sampleSize = calculateSampleSize(bounds, width, height)
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        } else {
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        }
        return openStream(context, uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    private fun openStream(context: Context, uri: Uri): InputStream? {
        return if (uri.scheme.equals(ContentResolver.SCHEME_FILE, ignoreCase = true)) {
            val path = uri.path ?: return null
            FileInputStream(File(path))
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }

    private fun calculateSampleSize(bounds: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return 1
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfWidth = width / 2
            var halfHeight = height / 2
            while ((halfWidth / inSampleSize) >= reqWidth && (halfHeight / inSampleSize) >= reqHeight) {
                inSampleSize *= 2
            }
        }
        return max(1, inSampleSize)
    }

    private fun cacheSize(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory()
        val target = maxMemory / 6L
        val minimum = 8L * 1024L * 1024L
        val desired = target.coerceAtLeast(minimum)
        return desired.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun cacheKey(uri: Uri, targetWidth: Int?, targetHeight: Int?): String {
        val widthBucket = bucketDimension(targetWidth)
        val heightBucket = bucketDimension(targetHeight)
        return "${uri}@${widthBucket}x${heightBucket}"
    }

    private fun bucketDimension(dimension: Int?): Int {
        if (dimension == null || dimension <= 0) return 0
        val bucketSize = 32
        return ((dimension + bucketSize - 1) / bucketSize) * bucketSize
    }
}
