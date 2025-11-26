package com.example.timescapedemo

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class VideoToCollageActivity : AppCompatActivity() {

    private lateinit var pickVideoButton: MaterialButton
    private lateinit var generateButton: MaterialButton
    private lateinit var progressIndicator: LinearProgressIndicator
    private lateinit var progressText: TextView
    private lateinit var selectedVideoText: TextView
    private lateinit var collageCard: MaterialCardView
    private lateinit var collageImage: ImageView
    private lateinit var saveButton: MaterialButton
    private lateinit var shareButton: MaterialButton
    private lateinit var addToFlowButton: MaterialButton

    private var selectedVideoUri: Uri? = null
    private var collageBitmap: Bitmap? = null
    private var collageFile: File? = null
    private var collageFileUri: Uri? = null

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            handleVideoSelection(uri)
        }

    private val openVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            handleVideoSelection(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_to_collage)
        val toolbar = findViewById<MaterialToolbar>(R.id.videoToCollageToolbar)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        pickVideoButton = findViewById(R.id.buttonPickVideo)
        generateButton = findViewById(R.id.buttonGenerateCollage)
        progressIndicator = findViewById(R.id.videoToCollageProgress)
        progressText = findViewById(R.id.textProgress)
        selectedVideoText = findViewById(R.id.textSelectedVideo)
        collageCard = findViewById(R.id.collagePreviewCard)
        collageImage = findViewById(R.id.imageCollage)
        saveButton = findViewById(R.id.buttonSaveToGallery)
        shareButton = findViewById(R.id.buttonShareCollage)
        addToFlowButton = findViewById(R.id.buttonAddToFlow)

        pickVideoButton.setOnClickListener { launchVideoPicker() }
        generateButton.setOnClickListener { generateCollage() }
        saveButton.setOnClickListener { saveToGallery() }
        shareButton.setOnClickListener { shareCollage() }
        addToFlowButton.setOnClickListener { returnCollageToFlow() }
    }

    override fun onDestroy() {
        super.onDestroy()
        collageBitmap?.recycle()
    }

    private fun launchVideoPicker() {
        if (isPickerAvailable()) {
            pickVideoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        } else {
            openVideoLauncher.launch(arrayOf("video/*"))
        }
    }

    private fun handleVideoSelection(uri: Uri?) {
        if (uri == null) {
            snackbar(getString(R.string.video_to_collage_no_video_selected))
            return
        }
        persistReadPermission(uri)
        selectedVideoUri = uri
        val name = resolveDisplayName(uri)
        selectedVideoText.text = name ?: getString(R.string.video_to_collage_selected_placeholder)
        generateButton.isEnabled = true
    }

    private fun persistReadPermission(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: SecurityException) {
                // Best effort; some URIs do not support persistable permissions.
            }
        }
    }

    private fun isPickerAvailable(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) true
        else ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)

    private fun generateCollage() {
        val uri = selectedVideoUri
        if (uri == null) {
            snackbar(getString(R.string.video_to_collage_no_video))
            return
        }
        lifecycleScope.launch {
            setProcessing(true, getString(R.string.video_to_collage_processing))
            try {
                val result = createCollage(uri) { current, total, message ->
                    updateProgress(current, total, message)
                }
                displayCollage(result)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val detail = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "Failed to generate collage from $uri: $detail", t)
                snackbar(getString(R.string.video_to_collage_error_loading_video_with_reason, detail))
            } finally {
                setProcessing(false)
            }
        }
    }

    private suspend fun createCollage(
        uri: Uri,
        onProgress: suspend (Int, Int, String) -> Unit
    ): CollageResult = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var cachedCopy: File? = null
        try {
            val configured = setRetrieverDataSource(retriever, uri)
            if (!configured) {
                cachedCopy = cacheVideoLocally(uri)
                retriever.setDataSource(cachedCopy.absolutePath)
            }
        } catch (t: Throwable) {
            retriever.release()
            cachedCopy?.delete()
            throw t
        }

        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?: 0L
        val frameTimes = sampleFrameTimes(durationMs)
        val frames = mutableListOf<Bitmap>()
        var targetHeight = 0
        for ((index, timeMs) in frameTimes.withIndex()) {
            val frame = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
            if (frame != null) {
                if (targetHeight == 0) targetHeight = min(frame.height, MAX_FRAME_HEIGHT)
                val resized = scaleToHeight(frame, targetHeight)
                if (resized !== frame) frame.recycle()
                frames += resized
            }
            onProgress(index + 1, frameTimes.size,
                getString(R.string.video_to_collage_extracting_progress, index + 1, frameTimes.size))
        }
        retriever.release()
        cachedCopy?.delete()
        if (frames.isEmpty()) throw IllegalStateException("No frames available")

        onProgress(frameTimes.size, frameTimes.size, getString(R.string.video_to_collage_detecting_subtitles))
        val subtitleBand = detectSubtitleBand(frames.first())
            ?: defaultSubtitleBand(frames.first())
        val collage = composeCollage(frames, subtitleBand)
        CollageResult(collage)
    }

    private suspend fun detectSubtitleBand(frame: Bitmap): Rect? = withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(frame, 0)
        val visionText = recognizer.process(image).await()
        val boxes = visionText.textBlocks.flatMap { block -> block.lines.mapNotNull { it.boundingBox } }
        if (boxes.isEmpty()) return@withContext null
        val focusCandidates = boxes.filter { it.centerY() > frame.height * 0.4f }
        val region = if (focusCandidates.isNotEmpty()) focusCandidates else boxes
        val padding = (frame.height * 0.04f).roundToInt()
        val top = (region.minOf { it.top } - padding).coerceAtLeast(0)
        val bottom = (region.maxOf { it.bottom } + padding).coerceAtMost(frame.height)
        Rect(0, top, frame.width, bottom)
    }

    private fun defaultSubtitleBand(frame: Bitmap): Rect {
        val bandHeight = (frame.height * 0.2f).roundToInt().coerceAtLeast(1)
        val top = (frame.height - bandHeight).coerceAtLeast(0)
        return Rect(0, top, frame.width, frame.height)
    }

    private fun sampleFrameTimes(durationMs: Long): List<Long> {
        val targetFrames = 5
        if (durationMs <= 0) return List(targetFrames) { it * 300L }
        if (durationMs < targetFrames) return listOf(0L, durationMs / 2)
        val step = durationMs / (targetFrames - 1)
        return (0 until targetFrames).map { index ->
            val candidate = index * step
            candidate.coerceAtMost(durationMs - 1)
        }.distinct()
    }

    private fun scaleToHeight(frame: Bitmap, targetHeight: Int): Bitmap {
        if (frame.height == targetHeight) return frame
        val ratio = targetHeight.toFloat() / frame.height.toFloat()
        val width = (frame.width * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(frame, width, targetHeight, true)
    }

    private fun composeCollage(frames: List<Bitmap>, subtitleBand: Rect): Bitmap {
        val first = frames.first()
        val croppedBandHeight = subtitleBand.height().coerceAtLeast(1)
        val bandFrames = frames.drop(1).map { frame ->
            val clampedTop = subtitleBand.top.coerceAtLeast(0).coerceAtMost(frame.height - 1)
            val height = min(croppedBandHeight, frame.height - clampedTop)
            Bitmap.createBitmap(frame, 0, clampedTop, frame.width, height)
        }
        val collageHeight = first.height
        val collageWidth = first.width + bandFrames.sumOf { it.width }
        val output = Bitmap.createBitmap(collageWidth, collageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        var offsetX = 0
        canvas.drawBitmap(first, offsetX.toFloat(), 0f, null)
        offsetX += first.width
        bandFrames.forEach { band ->
            val y = (collageHeight - band.height).coerceAtLeast(0)
            canvas.drawBitmap(band, offsetX.toFloat(), y.toFloat(), null)
            offsetX += band.width
        }
        return output
    }

    private fun setRetrieverDataSource(retriever: MediaMetadataRetriever, uri: Uri): Boolean {
        val afd = contentResolver.openAssetFileDescriptor(uri, "r")
        if (afd != null) {
            afd.use {
                try {
                    if (it.declaredLength >= 0 && it.startOffset >= 0) {
                        retriever.setDataSource(it.fileDescriptor, it.startOffset, it.declaredLength)
                    } else {
                        retriever.setDataSource(it.fileDescriptor)
                    }
                    return true
                } catch (_: Throwable) {
                    // Fall back to other strategies below.
                }
            }
        }

        val descriptor = contentResolver.openFileDescriptor(uri, "r")
        if (descriptor != null) {
            descriptor.use {
                return try {
                    retriever.setDataSource(it.fileDescriptor)
                    true
                } catch (_: Throwable) {
                    false
                }
            }
        }

        return try {
            retriever.setDataSource(this, uri)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun cacheVideoLocally(uri: Uri): File {
        val cacheFile = File(cacheDir, "picked_video_${System.currentTimeMillis()}.tmp")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("Unable to open input stream for URI: $uri")
        return cacheFile
    }

    private fun displayCollage(result: CollageResult) {
        collageBitmap?.recycle()
        collageBitmap = result.bitmap
        collageImage.setImageBitmap(result.bitmap)
        collageCard.isVisible = true
        val saved = saveCollageToFile(result.bitmap)
        if (saved != null) {
            collageFile = saved
            collageFileUri = saved.toUri()
            enableActions(true)
        } else {
            enableActions(false)
        }
    }

    private fun setProcessing(inProgress: Boolean, status: String? = null) {
        pickVideoButton.isEnabled = !inProgress
        generateButton.isEnabled = !inProgress && selectedVideoUri != null
        saveButton.isEnabled = !inProgress && collageBitmap != null
        shareButton.isEnabled = !inProgress && collageBitmap != null
        addToFlowButton.isEnabled = !inProgress && collageBitmap != null
        progressIndicator.isVisible = inProgress
        progressIndicator.isIndeterminate = true
        progressText.isVisible = inProgress
        progressText.text = status
        if (!inProgress) {
            progressIndicator.progress = 0
        }
    }

    private suspend fun updateProgress(current: Int, total: Int, message: String) {
        withContext(Dispatchers.Main) {
            progressIndicator.isVisible = true
            progressIndicator.isIndeterminate = total == 0
            val percent = if (total > 0) {
                ((current.toFloat() / total.toFloat()) * 100).roundToInt().coerceIn(0, 100)
            } else 0
            progressIndicator.setProgressCompat(percent, true)
            progressText.isVisible = true
            progressText.text = message
        }
    }

    private fun saveToGallery() {
        val bitmap = collageBitmap ?: run {
            snackbar(getString(R.string.video_to_collage_result_missing))
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = writeToGallery(bitmap)
            withContext(Dispatchers.Main) {
                if (saved) {
                    snackbar(getString(R.string.video_to_collage_saved_to_gallery))
                } else {
                    snackbar(getString(R.string.video_to_collage_save_failed))
                }
            }
        }
    }

    private suspend fun writeToGallery(bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "collage_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, DEFAULT_COLLAGE_MIME)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Timescape Collages")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext false
        return@withContext try {
            contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            true
        } catch (io: IOException) {
            false
        }
    }

    private fun shareCollage() {
        val file = collageFile ?: run {
            snackbar(getString(R.string.video_to_collage_result_missing))
            return
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = DEFAULT_COLLAGE_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.video_to_collage_share)))
    }

    private fun returnCollageToFlow() {
        val uri = collageFileUri ?: run {
            snackbar(getString(R.string.video_to_collage_result_missing))
            return
        }
        val data = Intent().apply {
            data = uri
            putExtra(EXTRA_RESULT_MIME_TYPE, DEFAULT_COLLAGE_MIME)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun saveCollageToFile(bitmap: Bitmap): File? {
        return try {
            val dir = File(filesDir, "collages").apply { mkdirs() }
            val file = File(dir, "collage_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file
        } catch (_: IOException) {
            null
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            } ?: uri.lastPathSegment
    }

    private fun snackbar(message: String) {
        Snackbar.make(findViewById(R.id.videoToCollageRoot), message, Snackbar.LENGTH_LONG).show()
    }

    private fun enableActions(enabled: Boolean) {
        saveButton.isEnabled = enabled
        shareButton.isEnabled = enabled
        addToFlowButton.isEnabled = enabled
    }

    data class CollageResult(val bitmap: Bitmap)

    companion object {
        const val EXTRA_RESULT_MIME_TYPE = "result_mime_type"
        private const val MAX_FRAME_HEIGHT = 720
        private const val DEFAULT_COLLAGE_MIME = "image/png"
        private const val TAG = "VideoToCollage"
    }
}
