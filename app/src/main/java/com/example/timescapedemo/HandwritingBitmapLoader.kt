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

object HandwritingBitmapLoader {
    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(cacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private val executor: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "HandwritingBitmapLoader").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(context: Context, path: String, imageView: ImageView, onResult: (Bitmap?) -> Unit) {
        val appContext = context.applicationContext
        imageView.setTag(R.id.tag_handwriting_path, path)
        cache.get(path)?.let { cached ->
            onResult(cached)
            return
        }
        executor.execute {
            val bitmap = decode(appContext, path)
            if (bitmap != null) {
                cache.put(path, bitmap)
            }
            mainHandler.post {
                val currentTag = imageView.getTag(R.id.tag_handwriting_path)
                if (currentTag == path) {
                    onResult(bitmap)
                }
            }
        }
    }

    fun clear(imageView: ImageView) {
        imageView.setTag(R.id.tag_handwriting_path, null)
        imageView.setImageDrawable(null)
    }

    private fun decode(context: Context, path: String): Bitmap? {
        return try {
            context.openFileInput(path).use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (ignored: FileNotFoundException) {
            null
        } catch (ignored: Exception) {
            null
        }
    }

    private fun cacheSize(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory()
        val target = maxMemory / 8L
        val minimum = 4L * 1024L * 1024L
        val desired = target.coerceAtLeast(minimum)
        return desired.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
