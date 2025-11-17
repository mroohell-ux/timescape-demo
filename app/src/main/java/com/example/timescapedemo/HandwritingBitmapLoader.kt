package com.example.timescapedemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.util.LruCache
import java.io.FileNotFoundException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

object HandwritingBitmapLoader {
    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(cacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private val executor: ExecutorService = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "HandwritingBitmapLoader").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inFlight = mutableMapOf<String, MutableList<(Bitmap?) -> Unit>>()
    private val lock = Any()
    private val generationLock = Any()
    private val generations = mutableMapOf<String, Int>()

    fun load(
        context: Context,
        path: String,
        imageView: ImageView,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        onResult: (Bitmap?) -> Unit
    ) {
        val appContext = context.applicationContext
        val generation = currentGeneration(path)
        val cacheKey = cacheKey(path, targetWidth, targetHeight, generation)
        imageView.setTag(R.id.tag_handwriting_path, cacheKey)
        cache.get(cacheKey)?.let { cached ->
            onResult(cached)
            return
        }

        val callback: (Bitmap?) -> Unit = { bitmap ->
            val currentTag = imageView.getTag(R.id.tag_handwriting_path)
            if (currentTag == cacheKey) {
                onResult(bitmap)
            }
        }
        val shouldStart = registerCallback(cacheKey, callback)
        if (shouldStart) {
            enqueueDecode(appContext, cacheKey, path, targetWidth, targetHeight, generation)
        }
    }

    fun clear(imageView: ImageView) {
        imageView.setTag(R.id.tag_handwriting_path, null)
        imageView.setImageDrawable(null)
    }

    fun invalidate(path: String) {
        bumpGeneration(path)
        val prefix = "$path@"
        val keys = synchronized(cache) {
            cache.snapshot().keys.filter { it.startsWith(prefix) }
        }
        keys.forEach { key -> cache.remove(key) }
        cancelInFlight(prefix)
    }

    fun prefetch(
        context: Context,
        path: String,
        targetWidth: Int? = null,
        targetHeight: Int? = null
    ) {
        val generation = currentGeneration(path)
        val cacheKey = cacheKey(path, targetWidth, targetHeight, generation)
        if (cache.get(cacheKey) != null) return
        val shouldStart = registerCallback(cacheKey, null)
        if (shouldStart) {
            enqueueDecode(context.applicationContext, cacheKey, path, targetWidth, targetHeight, generation)
        }
    }

    private fun enqueueDecode(
        context: Context,
        cacheKey: String,
        path: String,
        targetWidth: Int?,
        targetHeight: Int?,
        generation: Int
    ) {
        executor.execute {
            val bitmap = decode(context, path, targetWidth, targetHeight)
            val callbacks = finishCallbacks(cacheKey)
            val isCurrent = generation == currentGeneration(path)
            if (isCurrent && bitmap != null) {
                cache.put(cacheKey, bitmap)
            }
            if (!isCurrent || callbacks.isEmpty()) return@execute
            mainHandler.post {
                callbacks.forEach { it(bitmap) }
            }
        }
    }

    private fun registerCallback(cacheKey: String, callback: ((Bitmap?) -> Unit)?): Boolean {
        synchronized(lock) {
            val existing = inFlight[cacheKey]
            if (existing != null) {
                if (callback != null) existing.add(callback)
                return false
            }
            if (callback != null) {
                inFlight[cacheKey] = mutableListOf(callback)
            } else {
                inFlight[cacheKey] = mutableListOf()
            }
            return true
        }
    }

    private fun finishCallbacks(cacheKey: String): List<(Bitmap?) -> Unit> {
        return synchronized(lock) {
            inFlight.remove(cacheKey)?.toList() ?: emptyList()
        }
    }

    private fun decode(
        context: Context,
        path: String,
        targetWidth: Int?,
        targetHeight: Int?
    ): Bitmap? {
        return try {
            val width = targetWidth?.takeIf { it > 0 }
            val height = targetHeight?.takeIf { it > 0 }
            val options = if (width != null && height != null) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.openFileInput(path).use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                val sampleSize = calculateSampleSize(bounds, width, height)
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            } else {
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            }
            context.openFileInput(path).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (ignored: FileNotFoundException) {
            null
        } catch (ignored: Exception) {
            null
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
        val target = maxMemory / 8L
        val minimum = 4L * 1024L * 1024L
        val desired = target.coerceAtLeast(minimum)
        return desired.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun cacheKey(path: String, targetWidth: Int?, targetHeight: Int?, generation: Int): String {
        val widthBucket = bucketDimension(targetWidth)
        val heightBucket = bucketDimension(targetHeight)
        return "$path@$widthBucket@$heightBucket@$generation"
    }

    private fun bucketDimension(dimension: Int?): Int {
        if (dimension == null || dimension <= 0) return 0
        val bucketSize = 32
        return ((dimension + bucketSize - 1) / bucketSize) * bucketSize
    }

    private fun cancelInFlight(prefix: String) {
        synchronized(lock) {
            val iterator = inFlight.keys.iterator()
            while (iterator.hasNext()) {
                val key = iterator.next()
                if (key.startsWith(prefix)) {
                    iterator.remove()
                }
            }
        }
    }

    private fun currentGeneration(path: String): Int = synchronized(generationLock) {
        generations[path] ?: 0
    }

    private fun bumpGeneration(path: String): Int = synchronized(generationLock) {
        val next = (generations[path] ?: 0) + 1
        generations[path] = next
        next
    }
}
