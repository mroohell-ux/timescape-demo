package com.example.timescapedemo

import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.applyCanvas
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import java.io.FileNotFoundException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class Tab { CARDS, IMAGES }

    // Views
    private lateinit var recyclerCards: RecyclerView
    private lateinit var recyclerImages: RecyclerView
    private lateinit var lm: RightRailFlowLayoutManager
    private lateinit var bottomBar: BottomNavigationView
    private lateinit var toolbar: MaterialToolbar

    // Adapters / state
    private lateinit var imagesAdapter: SelectedImagesAdapter
    private val selectedImages: MutableList<BgImage> = mutableListOf()
    private var currentTab: Tab = Tab.CARDS

    // ------- pickers -------
    private val pickImages =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (!uris.isNullOrEmpty()) {
                selectedImages.addAll(uris.map { BgImage.UriRef(it) })
                selectedImages.shuffle()
                imagesAdapter.submit(selectedImages)
                rebuildCardsAdapter()
                snackbar("Added ${uris.size} image(s)")
            } else snackbar("No photos selected")
        }

    private val openDocs =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) {
                for (u in uris) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            u, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: SecurityException) { }
                }
                selectedImages.addAll(uris.map { BgImage.UriRef(it) })
                selectedImages.shuffle()
                imagesAdapter.submit(selectedImages)
                rebuildCardsAdapter()
                snackbar("Added ${uris.size} image(s)")
            } else snackbar("No photos selected")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        setupToolbarActionsFor(Tab.CARDS) // default

        recyclerCards = findViewById(R.id.recyclerCards)
        recyclerImages = findViewById(R.id.recyclerImages)

        // Cards layout manager
        val d = resources.displayMetrics.density
        val baseSide = (380 * d).toInt()
        val focusSide = (380 * d).toInt()
        val pitch = (focusSide * 0.26f).toInt()
        lm = RightRailFlowLayoutManager(
            baseSidePx = baseSide,
            focusSidePx = focusSide,
            itemPitchPx = pitch,
            rightInsetPx = (16 * d).toInt()
        )
        recyclerCards.layoutManager = lm

        // Images grid + adapter (lambda references class property 'imagesAdapter')
        recyclerImages.layoutManager = GridLayoutManager(this, 3)
        imagesAdapter = SelectedImagesAdapter { img ->
            selectedImages.remove(img)
            imagesAdapter.submit(selectedImages)
            rebuildCardsAdapter()
            snackbar("Removed 1 image")
        }
        recyclerImages.adapter = imagesAdapter

        // BottomNav with TWO tabs
        bottomBar = findViewById(R.id.bottomBar)
        bottomBar.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_cards -> { switchTo(Tab.CARDS); true }
                R.id.nav_images -> { switchTo(Tab.IMAGES); true }
                else -> false
            }
        }
        bottomBar.selectedItemId = R.id.nav_cards

        // Seed from project drawables (bg_*) on first launch
        syncFromProjectDrawables()
        imagesAdapter.submit(selectedImages)

        rebuildCardsAdapter()

        // Back press: defocus first
        onBackPressedDispatcher.addCallback(this) {
            val idx = lm.nearestIndex()
            if (lm.isFocused(idx)) lm.clearFocus() else {
                remove(); onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    // ---------- tabs ----------
    private fun switchTo(tab: Tab) {
        currentTab = tab
        recyclerCards.visibility = if (tab == Tab.CARDS) View.VISIBLE else View.GONE
        recyclerImages.visibility = if (tab == Tab.IMAGES) View.VISIBLE else View.GONE
        setupToolbarActionsFor(tab)
    }

    private fun setupToolbarActionsFor(tab: Tab) {
        toolbar.menu.clear()
        menuInflater.inflate(R.menu.menu_main, toolbar.menu)
        val visible = (tab == Tab.IMAGES)
        toolbar.menu.findItem(R.id.action_pick)?.isVisible = visible
        toolbar.menu.findItem(R.id.action_sync)?.isVisible = visible
        toolbar.menu.findItem(R.id.action_clear)?.isVisible = visible

        toolbar.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                R.id.action_pick -> { launchPicker(); true }
                R.id.action_sync -> { syncFromProjectDrawables(); true }
                R.id.action_clear -> {
                    selectedImages.clear()
                    imagesAdapter.submit(selectedImages)
                    rebuildCardsAdapter()
                    snackbar("Cleared all images"); true
                }
                else -> false
            }
        }
    }

    // ---------- images selection / sync ----------
    private fun launchPicker() {
        if (isPhotoPickerAvailable()) {
            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            openDocs.launch(arrayOf("image/*"))
        }
    }

    /** Merge all drawables named bg_* into selectedImages (no duplicates). */
    private fun syncFromProjectDrawables() {
        val res = resources
        val pkg = packageName
        val found = mutableListOf<BgImage.Res>()

        // Fast numeric series
        var gap = 0
        for (i in 0..199) {
            val id = res.getIdentifier("bg_$i", "drawable", pkg)
            if (id != 0) { found += BgImage.Res(id); gap = 0 } else { gap++; if (i > 20 && gap > 20) break }
        }
        // Reflection fallback
        if (found.isEmpty()) {
            val skip = listOf("gradient", "scrim", "placeholder")
            for (f in R.drawable::class.java.fields) {
                val n = f.name
                if (n.startsWith("bg_") && skip.none { word -> n.contains(word) }) {
                    try { found += BgImage.Res(f.getInt(null)) } catch (_: Exception) { }
                }
            }
        }

        // Merge without duplicates
        val currentSet = selectedImages.toMutableSet()
        currentSet.addAll(found)
        selectedImages.clear()
        selectedImages.addAll(currentSet)
        selectedImages.sortBy { (it as? BgImage.Res)?.id ?: Int.MAX_VALUE }

        imagesAdapter.submit(selectedImages)
        rebuildCardsAdapter()
        snackbar("Synced ${found.size} drawable image(s)")
    }

    // ---------- cards ----------
    private fun rebuildCardsAdapter() {
        val variants = listOf(
            "Ping me when you’re free.",
            "Grows toward center. Two neighbors overlap a bit.",
            "This is a slightly longer body so you can see the card stretch beyond a couple of lines.",
            "Here’s a longer description to show adaptive height. Tap to focus; tap again to defocus.",
            "Long body to approach the 2/3 screen-height cap.",
            "EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height."
        )

        val fallback: BgImage = BgImage.Res(R.drawable.bg_placeholder)
        val items = List(30) { i ->
            val bg = if (selectedImages.isNotEmpty()) selectedImages[i % selectedImages.size] else fallback
            CardItem(
                title = "Contact $i",
                snippet = variants[i % variants.size],
                time = "${(i % 12) + 1}h ago",
                bg = bg
            )
        }

        val tint: TintStyle = TintStyle.LiquidGlass(dimAmount = 0.38f, desaturation = 0.5f, lift = 0.05f)

        recyclerCards.adapter = CardsAdapter(items, tint) { index ->
            if (lm.isFocused(index)) { lm.clearFocus(); return@CardsAdapter }
            val delta = lm.offsetTo(index)
            if (delta == 0) lm.focus(index) else recyclerCards.smoothScrollBy(0, delta)
        }
    }

    // ---------- bitmap helpers (kept in case you reuse previews) ----------
    private fun makeCircularPreview(img: BgImage, sizePx: Int): Bitmap? = try {
        val src = decodeBitmapSafe(img, targetMaxEdge = 512) ?: return null
        val w = src.width; val h = src.height
        val side = min(w, h)
        val left = (w - side) / 2
        val top = (h - side) / 2
        val square = Bitmap.createBitmap(src, left, top, side, side)
        if (square != src) src.recycle()

        val scaled = Bitmap.createScaledBitmap(square, sizePx, sizePx, true)
        if (scaled != square) square.recycle()

        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        out.applyCanvas {
            val r = sizePx / 2f
            val path = Path().apply { addCircle(r, r, r, Path.Direction.CW) }
            clipPath(path)
            drawBitmap(scaled, 0f, 0f, null)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = 0x33FFFFFF
                strokeWidth = max(2f, sizePx / 24f)
            }
            drawCircle(r, r, r - p.strokeWidth / 2f, p)
        }
        if (!scaled.isRecycled) scaled.recycle()
        out
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: Exception) {
        null
    }

    private fun decodeBitmapSafe(img: BgImage, targetMaxEdge: Int): Bitmap? = try {
        when (img) {
            is BgImage.Res -> {
                val o1 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeResource(resources, img.id, o1)
                val sample = computeSample(o1.outWidth, o1.outHeight, targetMaxEdge)
                val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeResource(resources, img.id, o2)
            }
            is BgImage.UriRef -> {
                val o1 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                open(img.uri)?.use { BitmapFactory.decodeStream(it, null, o1) }
                val sample = computeSample(o1.outWidth, o1.outHeight, targetMaxEdge)
                val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
                open(img.uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
            }
        }
    } catch (_: FileNotFoundException) { null }

    private fun computeSample(w: Int, h: Int, targetMax: Int): Int {
        var s = 1; val maxEdge = max(w, h)
        while (maxEdge / s > targetMax) s *= 2
        return s.coerceAtLeast(1)
    }

    private fun open(uri: Uri): InputStream? = try {
        contentResolver.openInputStream(uri)
    } catch (_: Exception) { null }

    private fun isPhotoPickerAvailable(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) true
        else ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)

    private fun snackbar(msg: String) =
        Snackbar.make(findViewById(R.id.content), msg, Snackbar.LENGTH_SHORT).show()
}
