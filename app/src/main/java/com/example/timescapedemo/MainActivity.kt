package com.example.timescapedemo

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.applyCanvas
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import org.json.JSONArray
import org.json.JSONObject
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
    private lateinit var cardsAdapter: CardsAdapter
    private lateinit var imagesAdapter: SelectedImagesAdapter
    private val selectedImages: MutableList<BgImage> = mutableListOf()
    private val cards: MutableList<CardItem> = mutableListOf()
    private var nextCardId: Long = 0
    private var currentTab: Tab = Tab.CARDS
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    // ------- pickers -------
    private val pickImages =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (!uris.isNullOrEmpty()) {
                selectedImages.addAll(uris.map { BgImage.UriRef(it) })
                selectedImages.shuffle()
                imagesAdapter.submit(selectedImages)
                refreshCardsAdapter()
                saveState()
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
                refreshCardsAdapter()
                saveState()
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

        val tint: TintStyle = TintStyle.MultiplyDark(color = Color.BLACK, alpha = 0.15f)
        cardsAdapter = CardsAdapter(
            tint,
            onItemClick = { index ->
                if (lm.isFocused(index)) {
                    lm.clearFocus()
                } else {
                    val delta = lm.offsetTo(index)
                    if (delta == 0) {
                        lm.focus(index)
                    } else {
                        recyclerCards.smoothScrollBy(0, delta)
                    }
                }
            },
            onItemDoubleClick = { index -> editCard(index) }
        )
        recyclerCards.adapter = cardsAdapter

        // Images grid + adapter (lambda references class property 'imagesAdapter')
        recyclerImages.layoutManager = GridLayoutManager(this, 3)
        imagesAdapter = SelectedImagesAdapter { img ->
            selectedImages.remove(img)
            imagesAdapter.submit(selectedImages)
            refreshCardsAdapter()
            saveState()
            snackbar("Removed 1 image")
        }
        recyclerImages.adapter = imagesAdapter

        loadState()

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

        if (selectedImages.isEmpty()) {
            // Seed from project drawables (bg_*) on first launch
            syncFromProjectDrawables(announce = false)
        } else {
            imagesAdapter.submit(selectedImages)
            refreshCardsAdapter()
        }

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
        val showImagesActions = (tab == Tab.IMAGES)
        toolbar.menu.findItem(R.id.action_pick)?.isVisible = showImagesActions
        toolbar.menu.findItem(R.id.action_sync)?.isVisible = showImagesActions
        toolbar.menu.findItem(R.id.action_clear)?.isVisible = showImagesActions
        val showCardActions = (tab == Tab.CARDS)
        toolbar.menu.findItem(R.id.action_add_card)?.isVisible = showCardActions

        toolbar.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                R.id.action_add_card -> { showAddCardDialog(); true }
                R.id.action_pick -> { launchPicker(); true }
                R.id.action_sync -> { syncFromProjectDrawables(); true }
                R.id.action_clear -> {
                    selectedImages.clear()
                    imagesAdapter.submit(selectedImages)
                    refreshCardsAdapter()
                    saveState()
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
    private fun syncFromProjectDrawables(announce: Boolean = true) {
        val before = selectedImages.toList()
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
        refreshCardsAdapter()

        val changed = before != selectedImages
        if (changed) {
            saveState()
            if (announce) snackbar("Synced ${found.size} drawable image(s)")
        } else if (announce) {
            snackbar("Project images are already up to date")
        }
    }

    // ---------- cards ----------
    private fun refreshCardsAdapter(scrollToTop: Boolean = false) {
        val seeded = ensureSeedCards()
        cards.sortByDescending { it.updatedAt }

        val fallback: BgImage = BgImage.Res(R.drawable.bg_placeholder)
        val backgrounds = if (selectedImages.isNotEmpty()) selectedImages else listOf(fallback)
        cards.forEachIndexed { index, card ->
            card.bg = backgrounds[index % backgrounds.size]
        }

        cardsAdapter.submitList(cards)
        if (seeded) saveState()
        if (scrollToTop) {
            lm.clearFocus()
            recyclerCards.scrollToPosition(0)
        }
    }

    private fun ensureSeedCards(): Boolean {
        if (cards.isNotEmpty()) return false

        val variants = listOf(
            "Ping me when you’re free.",
            "Grows toward center. Two neighbors overlap a bit.",
            "This is a slightly longer body so you can see the card stretch beyond a couple of lines.",
            "Here’s a longer description to show adaptive height. Tap to focus; tap again to defocus.",
            "Long body to approach the 2/3 screen-height cap.",
            "EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height."
        )

        repeat(30) { i ->
            cards += CardItem(
                id = nextCardId++,
                title = "Contact $i",
                snippet = variants[i % variants.size],
                bg = BgImage.Res(R.drawable.bg_placeholder),
                updatedAt = System.currentTimeMillis() - (i * 90L * 60L * 1000L)
            )
        }
        return true
    }

    private fun editCard(index: Int) {
        val card = cards.getOrNull(index) ?: return
        showCardEditor(card.title, card.snippet) { newTitle, newSnippet ->
            val titleValue = newTitle.trim()
            val snippetValue = newSnippet.trim()
            if (titleValue.isNotEmpty()) card.title = titleValue
            if (snippetValue.isNotEmpty()) card.snippet = snippetValue
            card.updatedAt = System.currentTimeMillis()
            refreshCardsAdapter(scrollToTop = true)
            saveState()
            snackbar("Card updated")
        }
    }

    private fun showAddCardDialog() {
        showCardEditor(initialTitle = "", initialSnippet = "") { title, snippet ->
            val finalTitle = title.trim().ifBlank { "New Contact" }
            val finalSnippet = snippet.trim().ifBlank { "Tap to edit this card." }
            val card = CardItem(
                id = nextCardId++,
                title = finalTitle,
                snippet = finalSnippet,
                updatedAt = System.currentTimeMillis()
            )
            cards += card
            refreshCardsAdapter(scrollToTop = true)
            saveState()
            snackbar("Added card")
        }
    }

    private fun showCardEditor(
        initialTitle: String,
        initialSnippet: String,
        onSave: (title: String, snippet: String) -> Unit
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_card, null, false)
        val titleInput = dialogView.findViewById<android.widget.EditText>(R.id.inputTitle)
        val snippetInput = dialogView.findViewById<android.widget.EditText>(R.id.inputSnippet)
        titleInput.setText(initialTitle)
        snippetInput.setText(initialSnippet)

        AlertDialog.Builder(this)
            .setTitle(if (initialTitle.isEmpty()) getString(R.string.dialog_add_card_title) else getString(R.string.dialog_edit_card_title))
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                onSave(titleInput.text.toString(), snippetInput.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun loadState() {
        cards.clear()
        selectedImages.clear()

        val cardsJson = prefs.getString(KEY_CARDS, null)
        if (!cardsJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(cardsJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    cards += CardItem(
                        id = obj.optLong("id"),
                        title = obj.optString("title"),
                        snippet = obj.optString("snippet"),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                    )
                }
            } catch (_: Exception) {
                cards.clear()
            }
        }

        val imagesJson = prefs.getString(KEY_IMAGES, null)
        if (!imagesJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(imagesJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    when (obj.optString("type")) {
                        "res" -> {
                            val id = obj.optInt("id")
                            val resolvedId = if (id != 0) id else {
                                val name = obj.optString("name")
                                if (name.isNullOrBlank()) 0 else resources.getIdentifier(name, "drawable", packageName)
                            }
                            if (resolvedId != 0) selectedImages += BgImage.Res(resolvedId)
                        }
                        "uri" -> {
                            val value = obj.optString("value")
                            if (!value.isNullOrBlank()) selectedImages += BgImage.UriRef(Uri.parse(value))
                        }
                    }
                }
            } catch (_: Exception) {
                selectedImages.clear()
            }
        }

        val savedNext = prefs.getLong(KEY_NEXT_CARD_ID, -1L)
        nextCardId = if (savedNext >= 0) savedNext else (cards.maxOfOrNull { it.id }?.plus(1) ?: 0L)
    }

    private fun saveState() {
        val cardsArray = JSONArray()
        cards.forEach { card ->
            val obj = JSONObject()
            obj.put("id", card.id)
            obj.put("title", card.title)
            obj.put("snippet", card.snippet)
            obj.put("updatedAt", card.updatedAt)
            cardsArray.put(obj)
        }

        val imagesArray = JSONArray()
        selectedImages.forEach { img ->
            val obj = JSONObject()
            when (img) {
                is BgImage.Res -> {
                    obj.put("type", "res")
                    obj.put("id", img.id)
                    runCatching { resources.getResourceEntryName(img.id) }
                        .getOrNull()?.let { obj.put("name", it) }
                }
                is BgImage.UriRef -> {
                    obj.put("type", "uri")
                    obj.put("value", img.uri.toString())
                }
            }
            imagesArray.put(obj)
        }

        prefs.edit()
            .putString(KEY_CARDS, cardsArray.toString())
            .putString(KEY_IMAGES, imagesArray.toString())
            .putLong(KEY_NEXT_CARD_ID, nextCardId)
            .apply()
    }

    override fun onStop() {
        super.onStop()
        saveState()
    }

    companion object {
        private const val PREFS_NAME = "timescape_state"
        private const val KEY_CARDS = "cards"
        private const val KEY_IMAGES = "images"
        private const val KEY_NEXT_CARD_ID = "next_card_id"
    }
}
