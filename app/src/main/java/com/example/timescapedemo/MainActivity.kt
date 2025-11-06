package com.example.timescapedemo

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.activity.addCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.applyCanvas
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.CompositePageTransformer
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class Tab { CARDS, IMAGES }

    private lateinit var rootLayout: CoordinatorLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var flowPager: ViewPager2
    private lateinit var recyclerImages: RecyclerView
    private lateinit var flowBar: View
    private lateinit var flowChipGroup: ChipGroup
    private lateinit var addFlowButton: FloatingActionButton

    private lateinit var imagesAdapter: SelectedImagesAdapter
    private lateinit var flowAdapter: FlowPagerAdapter

    private val selectedImages: MutableList<BgImage> = mutableListOf()
    private val flows: MutableList<CardFlow> = mutableListOf()
    private val flowControllers: MutableMap<Long, FlowPageController> = mutableMapOf()

    private val cardTint: TintStyle = TintStyle.Colorize(color = Color.parseColor("#3D7BFF"), amount = 0.42f)

    private var nextCardId: Long = 0
    private var nextFlowId: Long = 0
    private var currentTab: Tab = Tab.CARDS

    private var toolbarBasePaddingTop: Int = 0
    private var pagerBasePaddingStart: Int = 0
    private var pagerBasePaddingTop: Int = 0
    private var pagerBasePaddingEnd: Int = 0
    private var pagerBasePaddingBottom: Int = 0
    private var imagesBasePaddingStart: Int = 0
    private var imagesBasePaddingTop: Int = 0
    private var imagesBasePaddingEnd: Int = 0
    private var imagesBasePaddingBottom: Int = 0
    private var flowBarBaseMarginBottom: Int = 0

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (!uris.isNullOrEmpty()) {
                selectedImages.addAll(uris.map { BgImage.UriRef(it) })
                selectedImages.shuffle()
                imagesAdapter.submit(selectedImages)
                refreshAllFlows()
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
                refreshAllFlows()
                saveState()
                snackbar("Added ${uris.size} image(s)")
            } else snackbar("No photos selected")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        toolbar = findViewById(R.id.toolbar)
        flowPager = findViewById(R.id.flowPager)
        recyclerImages = findViewById(R.id.recyclerImages)
        flowBar = findViewById(R.id.flowBar)
        flowChipGroup = findViewById(R.id.flowChips)
        addFlowButton = findViewById(R.id.buttonAddFlow)

        toolbarBasePaddingTop = toolbar.paddingTop
        pagerBasePaddingStart = flowPager.paddingStart
        pagerBasePaddingTop = flowPager.paddingTop
        pagerBasePaddingEnd = flowPager.paddingEnd
        pagerBasePaddingBottom = flowPager.paddingBottom
        imagesBasePaddingStart = recyclerImages.paddingStart
        imagesBasePaddingTop = recyclerImages.paddingTop
        imagesBasePaddingEnd = recyclerImages.paddingEnd
        imagesBasePaddingBottom = recyclerImages.paddingBottom
        flowBarBaseMarginBottom =
            (flowBar.layoutParams as? CoordinatorLayout.LayoutParams)?.bottomMargin ?: 0

        flowAdapter = FlowPagerAdapter()
        setupFlowPager()

        recyclerImages.layoutManager = GridLayoutManager(this, 3)
        imagesAdapter = SelectedImagesAdapter { img ->
            selectedImages.remove(img)
            imagesAdapter.submit(selectedImages)
            refreshAllFlows()
            saveState()
            snackbar("Removed 1 image")
        }
        recyclerImages.adapter = imagesAdapter

        addFlowButton.setOnClickListener { showAddFlowDialog() }

        loadState()

        if (selectedImages.isEmpty()) {
            syncFromProjectDrawables(announce = false)
        } else {
            imagesAdapter.submit(selectedImages)
            refreshAllFlows()
        }

        flowAdapter.notifyDataSetChanged()
        renderFlowChips()
        updateChipSelection(flowPager.currentItem)
        updateToolbarSubtitle()

        switchTo(currentTab)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.updatePadding(top = toolbarBasePaddingTop + systemBars.top)
            flowPager.setPaddingRelative(
                pagerBasePaddingStart,
                pagerBasePaddingTop + systemBars.top,
                pagerBasePaddingEnd,
                pagerBasePaddingBottom + systemBars.bottom
            )
            recyclerImages.setPaddingRelative(
                imagesBasePaddingStart,
                imagesBasePaddingTop + systemBars.top,
                imagesBasePaddingEnd,
                imagesBasePaddingBottom + systemBars.bottom
            )
            flowBar.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                bottomMargin = flowBarBaseMarginBottom + systemBars.bottom
            }
            insets
        }
        ViewCompat.requestApplyInsets(rootLayout)

        onBackPressedDispatcher.addCallback(this) {
            if (currentTab == Tab.IMAGES) {
                switchTo(Tab.CARDS)
                return@addCallback
            }
            val controller = currentController()
            val nearest = controller?.layoutManager?.nearestIndex() ?: RecyclerView.NO_POSITION
            if (controller != null && nearest != RecyclerView.NO_POSITION && controller.layoutManager.isFocused(nearest)) {
                controller.layoutManager.clearFocus()
            } else {
                remove()
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun setupFlowPager() {
        val density = resources.displayMetrics.density
        flowPager.adapter = flowAdapter
        flowPager.offscreenPageLimit = 1
        flowPager.clipToPadding = false
        flowPager.clipChildren = false
        (flowPager.getChildAt(0) as? RecyclerView)?.overScrollMode = RecyclerView.OVER_SCROLL_NEVER
        val transformer = CompositePageTransformer().apply {
            addTransformer(MarginPageTransformer((24 * density).roundToInt()))
            addTransformer { page, position ->
                val scale = 0.9f + (1 - abs(position)) * 0.1f
                page.scaleY = scale
                page.alpha = 0.6f + (1 - abs(position)) * 0.4f
            }
        }
        flowPager.setPageTransformer(transformer)
        flowPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateChipSelection(position)
                updateToolbarSubtitle()
            }
        })
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
        toolbar.menu.findItem(R.id.action_add_flow)?.isVisible = showCardActions

        toolbar.menu.findItem(R.id.action_toggle_images)?.let { item ->
            if (tab == Tab.CARDS) {
                item.setIcon(android.R.drawable.ic_menu_gallery)
                item.title = getString(R.string.menu_open_images)
            } else {
                item.setIcon(android.R.drawable.ic_menu_revert)
                item.title = getString(R.string.menu_close_images)
            }
        }

        toolbar.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                R.id.action_add_card -> { showAddCardDialog(); true }
                R.id.action_add_flow -> { showAddFlowDialog(); true }
                R.id.action_toggle_images -> {
                    val target = if (currentTab == Tab.CARDS) Tab.IMAGES else Tab.CARDS
                    switchTo(target)
                    true
                }
                R.id.action_pick -> { launchPicker(); true }
                R.id.action_sync -> { syncFromProjectDrawables(); true }
                R.id.action_clear -> {
                    selectedImages.clear()
                    imagesAdapter.submit(selectedImages)
                    refreshAllFlows()
                    saveState()
                    snackbar("Cleared all images"); true
                }
                else -> false
            }
        }
    }

    private fun switchTo(tab: Tab) {
        currentTab = tab
        flowPager.visibility = if (tab == Tab.CARDS) View.VISIBLE else View.GONE
        flowBar.visibility = if (tab == Tab.CARDS) View.VISIBLE else View.GONE
        recyclerImages.visibility = if (tab == Tab.IMAGES) View.VISIBLE else View.GONE
        setupToolbarActionsFor(tab)
        updateToolbarSubtitle()
    }

    private fun updateToolbarSubtitle() {
        toolbar.subtitle = when (currentTab) {
            Tab.CARDS -> currentFlow()?.name ?: ""
            Tab.IMAGES -> getString(R.string.images_subtitle)
        }
    }

    private fun updateChipSelection(position: Int) {
        for (i in 0 until flowChipGroup.childCount) {
            val chip = flowChipGroup.getChildAt(i) as? Chip ?: continue
            chip.isChecked = i == position
        }
    }

    private fun renderFlowChips() {
        flowChipGroup.removeAllViews()
        val density = resources.displayMetrics.density
        flows.forEachIndexed { index, flow ->
            val chip = Chip(this).apply {
                text = flow.name
                isCheckable = true
                isCheckedIconVisible = false
                setEnsureMinTouchTargetSize(false)
                minHeight = (36 * density).roundToInt()
                textSize = 14f
                setPadding((12 * density).roundToInt(), 0, (12 * density).roundToInt(), 0)
                isChecked = index == flowPager.currentItem
                setOnClickListener { flowPager.setCurrentItem(index, true) }
            }
            flowChipGroup.addView(chip)
        }
    }

    private fun showAddFlowDialog() {
        val suggestedName = defaultFlowName(flows.size)
        val input = EditText(this).apply {
            setText(suggestedName)
            setSelection(text.length)
            hint = getString(R.string.dialog_flow_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val container = FrameLayout(this).apply {
            val padding = (24 * resources.displayMetrics.density).roundToInt()
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(input, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_add_flow_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_create) { _, _ ->
                val name = input.text.toString().trim().ifBlank { defaultFlowName(flows.size) }
                addFlow(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addFlow(name: String) {
        val flow = CardFlow(id = nextFlowId++, name = name)
        flows += flow
        flowAdapter.notifyItemInserted(flows.lastIndex)
        renderFlowChips()
        flowPager.post {
            flowPager.setCurrentItem(flows.lastIndex, true)
            updateToolbarSubtitle()
        }
        saveState()
        snackbar(getString(R.string.snackbar_added_flow, name))
    }

    private fun currentFlow(): CardFlow? = flows.getOrNull(flowPager.currentItem)

    private fun currentController(): FlowPageController? {
        val flow = currentFlow() ?: return null
        return flowControllers[flow.id]
    }

    private fun createLayoutManager(): RightRailFlowLayoutManager {
        val metrics = resources.displayMetrics
        val density = metrics.density
        val horizontalInsetPx = (32 * density).roundToInt()
        val minSidePx = (320 * density).roundToInt()
        val availableWidth = (metrics.widthPixels - horizontalInsetPx).coerceAtLeast(minSidePx)
        val baseSide = (availableWidth * 0.84f).roundToInt()
        val focusSide = (availableWidth * 0.94f).roundToInt()
        val pitch = (baseSide * 0.32f).roundToInt()
        return RightRailFlowLayoutManager(
            baseSidePx = baseSide,
            focusSidePx = focusSide,
            itemPitchPx = pitch,
            rightInsetPx = (8 * density).roundToInt()
        )
    }

    private fun prepareFlowCards(flow: CardFlow) {
        flow.cards.sortByDescending { it.updatedAt }
        val fallback: BgImage = BgImage.Res(R.drawable.bg_placeholder)
        val backgrounds = if (selectedImages.isNotEmpty()) selectedImages else listOf(fallback)
        flow.cards.forEachIndexed { index, card ->
            card.bg = backgrounds[index % backgrounds.size]
        }
    }

    private fun refreshFlow(flow: CardFlow, scrollToTop: Boolean = false) {
        prepareFlowCards(flow)
        val controller = flowControllers[flow.id]
        if (controller != null) {
            controller.adapter.submitList(flow.cards.toList())
            controller.nameView.text = flow.name
            if (scrollToTop) {
                controller.layoutManager.clearFocus()
                controller.recycler.scrollToPosition(0)
            }
        } else {
            val index = flows.indexOfFirst { it.id == flow.id }
            if (index >= 0) flowAdapter.notifyItemChanged(index)
        }
    }

    private fun refreshAllFlows(scrollToTopCurrent: Boolean = false) {
        flows.forEach { flow ->
            val shouldScroll = scrollToTopCurrent && flow == currentFlow()
            refreshFlow(flow, shouldScroll)
        }
    }

    private fun ensureSeedCards(flow: CardFlow): Boolean {
        if (flow.cards.isNotEmpty()) return false
        val names = listOf(
            "Avery Chen",
            "Mateo Ruiz",
            "Lina Torres",
            "Noah Patel",
            "Mika Ito",
            "Jonah Brooks",
            "Camille Dubois",
            "Zoe King"
        )
        val statuses = listOf(
            "Boarding now—see you soon!",
            "Sunrise shoot delivered. Thoughts?",
            "Deck finalized. Ready for sign-off tonight.",
            "Going live in 10 minutes—link’s in bio!",
            "Prototype build is rendering. Coffee run?",
            "DM me your availability; press brief moved up.",
            "Pinned the new concept—feedback welcome.",
            "Streaming set complete. Posting highlights soon."
        )
        val networks = listOf(
            SocialNetwork.INSTAGRAM,
            SocialNetwork.FACEBOOK,
            SocialNetwork.LINKEDIN,
            SocialNetwork.TWITTER
        )
        repeat(30) { i ->
            val now = System.currentTimeMillis()
            flow.cards += CardItem(
                id = nextCardId++,
                title = names[i % names.size],
                snippet = statuses[i % statuses.size],
                network = networks[i % networks.size],
                bg = BgImage.Res(R.drawable.bg_placeholder),
                updatedAt = now - (i * 90L * 60L * 1000L)
            )
        }
        return true
    }

    private fun showAddCardDialog() {
        val flow = currentFlow()
        if (flow == null) {
            snackbar(getString(R.string.snackbar_add_flow_first))
            return
        }
        showCardEditor(initialTitle = "", initialSnippet = "", onSave = { title, snippet ->
            val finalTitle = title.trim().ifBlank { "New Contact" }
            val finalSnippet = snippet.trim().ifBlank { "Tap to edit this card." }
            val allNetworks = SocialNetwork.values()
            val network = allNetworks[(nextCardId % allNetworks.size).toInt()]
            val card = CardItem(
                id = nextCardId++,
                title = finalTitle,
                snippet = finalSnippet,
                network = network,
                updatedAt = System.currentTimeMillis()
            )
            flow.cards += card
            refreshFlow(flow, scrollToTop = true)
            saveState()
            snackbar("Added card")
        })
    }

    private fun editCard(flow: CardFlow, index: Int) {
        val card = flow.cards.getOrNull(index) ?: return
        showCardEditor(card.title, card.snippet, onSave = { newTitle, newSnippet ->
            val titleValue = newTitle.trim()
            val snippetValue = newSnippet.trim()
            if (titleValue.isNotEmpty()) card.title = titleValue
            if (snippetValue.isNotEmpty()) card.snippet = snippetValue
            card.updatedAt = System.currentTimeMillis()
            refreshFlow(flow, scrollToTop = true)
            saveState()
            snackbar("Card updated")
        }, onDelete = {
            flow.cards.remove(card)
            refreshFlow(flow, scrollToTop = true)
            saveState()
            snackbar(getString(R.string.snackbar_deleted_card))
        })
    }

    private fun showCardEditor(
        initialTitle: String,
        initialSnippet: String,
        onSave: (title: String, snippet: String) -> Unit,
        onDelete: (() -> Unit)? = null
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_card, null, false)
        val titleInput = dialogView.findViewById<EditText>(R.id.inputTitle)
        val snippetInput = dialogView.findViewById<EditText>(R.id.inputSnippet)
        titleInput.setText(initialTitle)
        snippetInput.setText(initialSnippet)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (initialTitle.isEmpty()) getString(R.string.dialog_add_card_title) else getString(R.string.dialog_edit_card_title))
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                onSave(titleInput.text.toString(), snippetInput.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (onDelete != null) {
            builder.setNeutralButton(R.string.dialog_delete) { _, _ -> onDelete() }
        }
        builder.show()
    }

    private fun launchPicker() {
        if (isPhotoPickerAvailable()) {
            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            openDocs.launch(arrayOf("image/*"))
        }
    }

    private fun syncFromProjectDrawables(announce: Boolean = true) {
        val before = selectedImages.toList()
        val res = resources
        val pkg = packageName
        val found = mutableListOf<BgImage.Res>()

        var gap = 0
        for (i in 0..199) {
            val id = res.getIdentifier("bg_$i", "drawable", pkg)
            if (id != 0) { found += BgImage.Res(id); gap = 0 } else { gap++; if (i > 20 && gap > 20) break }
        }
        if (found.isEmpty()) {
            val skip = listOf("gradient", "scrim", "placeholder")
            for (f in R.drawable::class.java.fields) {
                val n = f.name
                if (n.startsWith("bg_") && skip.none { word -> n.contains(word) }) {
                    try { found += BgImage.Res(f.getInt(null)) } catch (_: Exception) { }
                }
            }
        }

        val currentSet = selectedImages.toMutableSet()
        currentSet.addAll(found)
        selectedImages.clear()
        selectedImages.addAll(currentSet)
        selectedImages.sortBy { (it as? BgImage.Res)?.id ?: Int.MAX_VALUE }

        imagesAdapter.submit(selectedImages)
        refreshAllFlows()

        val changed = before != selectedImages
        if (changed) {
            saveState()
            if (announce) snackbar("Synced ${found.size} drawable image(s)")
        } else if (announce) {
            snackbar("Project images are already up to date")
        }
    }

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
    } catch (_: FileNotFoundException) {
        null
    }

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

    private fun snackbar(msg: String) {
        val snack = Snackbar.make(findViewById(R.id.content), msg, Snackbar.LENGTH_SHORT)
        if (flowBar.isVisible) snack.anchorView = flowBar
        snack.show()
    }

    private fun loadState() {
        flows.clear()
        selectedImages.clear()

        var highestFlowId = -1L
        var highestCardId = -1L

        val flowsJson = prefs.getString(KEY_FLOWS, null)
        if (!flowsJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(flowsJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    var flowId = obj.optLong("id", -1L)
                    if (flowId < 0) flowId = highestFlowId + 1
                    val nameRaw = obj.optString("name")
                    val flow = CardFlow(
                        id = flowId,
                        name = if (nameRaw.isNullOrBlank()) defaultFlowName(flows.size) else nameRaw
                    )
                    highestFlowId = max(highestFlowId, flowId)
                    val cardsArray = obj.optJSONArray("cards")
                    if (cardsArray != null) {
                        for (j in 0 until cardsArray.length()) {
                            val cardObj = cardsArray.optJSONObject(j) ?: continue
                            var cardId = cardObj.optLong("id", -1L)
                            if (cardId < 0) cardId = highestCardId + 1
                            highestCardId = max(highestCardId, cardId)
                            flow.cards += CardItem(
                                id = cardId,
                                title = cardObj.optString("title"),
                                snippet = cardObj.optString("snippet"),
                                network = SocialNetwork.fromRaw(cardObj.optString("network")),
                                updatedAt = cardObj.optLong("updatedAt", System.currentTimeMillis())
                            )
                        }
                    }
                    flows += flow
                }
            } catch (_: Exception) {
                flows.clear()
                highestFlowId = -1L
                highestCardId = -1L
            }
        }

        if (flows.isEmpty()) {
            val cardsJson = prefs.getString(KEY_CARDS, null)
            val legacyCards = mutableListOf<CardItem>()
            if (!cardsJson.isNullOrBlank()) {
                try {
                    val arr = JSONArray(cardsJson)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val id = obj.optLong("id", -1L)
                        if (id >= 0) highestCardId = max(highestCardId, id)
                        legacyCards += CardItem(
                            id = if (id >= 0) id else ++highestCardId,
                            title = obj.optString("title"),
                            snippet = obj.optString("snippet"),
                            network = SocialNetwork.fromRaw(obj.optString("network")),
                            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                        )
                    }
                } catch (_: Exception) {
                    legacyCards.clear()
                }
            }
            val flow = CardFlow(id = 0L, name = defaultFlowName(0))
            flow.cards.addAll(legacyCards)
            flows += flow
            highestFlowId = max(highestFlowId, flow.id)
        }

        if (flows.isEmpty()) {
            flows += CardFlow(id = 0L, name = defaultFlowName(0))
            highestFlowId = max(highestFlowId, 0L)
        }

        val savedNextCard = prefs.getLong(KEY_NEXT_CARD_ID, -1L)
        nextCardId = if (savedNextCard >= 0) savedNextCard else (highestCardId + 1).coerceAtLeast(0L)

        val savedNextFlow = prefs.getLong(KEY_NEXT_FLOW_ID, -1L)
        nextFlowId = if (savedNextFlow >= 0) savedNextFlow else (highestFlowId + 1).coerceAtLeast(0L)

        val seeded = ensureSeedCards(flows.first())
        flows.forEach { prepareFlowCards(it) }
        if (seeded) saveState()

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
    }

    private fun saveState() {
        val flowsArray = JSONArray()
        flows.forEach { flow ->
            val flowObj = JSONObject()
            flowObj.put("id", flow.id)
            flowObj.put("name", flow.name)
            val cardsArray = JSONArray()
            flow.cards.forEach { card ->
                val obj = JSONObject()
                obj.put("id", card.id)
                obj.put("title", card.title)
                obj.put("snippet", card.snippet)
                obj.put("network", card.network.name)
                obj.put("updatedAt", card.updatedAt)
                cardsArray.put(obj)
            }
            flowObj.put("cards", cardsArray)
            flowsArray.put(flowObj)
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

        with(prefs.edit()) {
            putString(KEY_FLOWS, flowsArray.toString())
            putString(KEY_IMAGES, imagesArray.toString())
            putLong(KEY_NEXT_CARD_ID, nextCardId)
            putLong(KEY_NEXT_FLOW_ID, nextFlowId)
            remove(KEY_CARDS)
            apply()
        }
    }

    override fun onStop() {
        super.onStop()
        saveState()
    }

    private fun defaultFlowName(index: Int): String =
        getString(R.string.default_flow_name, index + 1)

    private inner class FlowPagerAdapter : RecyclerView.Adapter<FlowPagerAdapter.FlowVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FlowVH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.page_card_flow, parent, false)
            val recycler = view.findViewById<RecyclerView>(R.id.recyclerFlowCards)
            val nameView = view.findViewById<TextView>(R.id.flowName)
            val layoutManager = createLayoutManager()
            recycler.layoutManager = layoutManager
            recycler.setHasFixedSize(true)
            recycler.overScrollMode = RecyclerView.OVER_SCROLL_NEVER

            lateinit var holder: FlowVH
            val adapter = CardsAdapter(
                cardTint,
                onItemClick = { index -> holder.onCardTapped(index) },
                onItemDoubleClick = { index -> holder.onCardDoubleTapped(index) }
            )
            recycler.adapter = adapter
            holder = FlowVH(view, recycler, nameView, layoutManager, adapter)
            return holder
        }

        override fun onBindViewHolder(holder: FlowVH, position: Int) {
            val flow = flows[position]
            holder.bind(flow)
        }

        override fun getItemCount(): Int = flows.size

        override fun onViewRecycled(holder: FlowVH) {
            holder.boundFlowId?.let { flowControllers.remove(it) }
            super.onViewRecycled(holder)
        }

        inner class FlowVH(
            view: View,
            val recycler: RecyclerView,
            private val nameView: TextView,
            val layoutManager: RightRailFlowLayoutManager,
            val adapter: CardsAdapter
        ) : RecyclerView.ViewHolder(view) {
            var boundFlowId: Long? = null

            fun bind(flow: CardFlow) {
                boundFlowId?.let { flowControllers.remove(it) }
                boundFlowId = flow.id
                nameView.text = flow.name
                flowControllers[flow.id] = FlowPageController(flow.id, recycler, layoutManager, adapter, nameView)
                adapter.submitList(flow.cards.toList())
                layoutManager.clearFocus()
            }

            fun onCardTapped(index: Int) {
                if (bindingAdapterPosition == RecyclerView.NO_POSITION) return
                if (layoutManager.isFocused(index)) {
                    layoutManager.clearFocus()
                } else {
                    val delta = layoutManager.offsetTo(index)
                    if (delta == 0) {
                        layoutManager.focus(index)
                    } else {
                        recycler.smoothScrollBy(0, delta)
                    }
                }
            }

            fun onCardDoubleTapped(index: Int) {
                val flow = flows.getOrNull(bindingAdapterPosition) ?: return
                editCard(flow, index)
            }
        }
    }

    private data class FlowPageController(
        val flowId: Long,
        val recycler: RecyclerView,
        val layoutManager: RightRailFlowLayoutManager,
        val adapter: CardsAdapter,
        val nameView: TextView
    )

    companion object {
        private const val PREFS_NAME = "timescape_state"
        private const val KEY_CARDS = "cards"
        private const val KEY_IMAGES = "images"
        private const val KEY_FLOWS = "flows"
        private const val KEY_NEXT_CARD_ID = "next_card_id"
        private const val KEY_NEXT_FLOW_ID = "next_flow_id"
    }
}
