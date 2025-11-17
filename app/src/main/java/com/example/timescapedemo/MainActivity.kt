package com.example.timescapedemo

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.activity.addCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.applyCanvas
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.CompositePageTransformer
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.slider.Slider
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var rootLayout: CoordinatorLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var flowPager: ViewPager2
    private lateinit var flowBar: View
    private lateinit var flowChipGroup: ChipGroup
    private lateinit var flowChipScroll: HorizontalScrollView

    private var searchMenuItem: MenuItem? = null
    private var searchView: SearchView? = null
    private var searchQueryText: String = ""
    private var searchQueryNormalized: String = ""

    private lateinit var drawerRecyclerImages: RecyclerView
    private lateinit var drawerAddImagesButton: MaterialButton
    private lateinit var drawerClearImagesButton: MaterialButton
    private lateinit var drawerPickAppBackgroundButton: MaterialButton
    private lateinit var drawerResetAppBackgroundButton: MaterialButton
    private lateinit var drawerExportNotesButton: MaterialButton
    private lateinit var drawerImportNotesButton: MaterialButton
    private lateinit var appBackgroundPreview: ImageView
    private lateinit var cardFontSizeSlider: Slider
    private lateinit var cardFontSizeValue: TextView

    private lateinit var imagesAdapter: SelectedImagesAdapter
    private lateinit var flowAdapter: FlowPagerAdapter

    private val selectedImages: MutableList<BgImage> = mutableListOf()
    private val flows: MutableList<CardFlow> = mutableListOf()
    private val flowControllers: MutableMap<Long, FlowPageController> = mutableMapOf()
    private val flowShuffleStates: MutableMap<Long, FlowShuffleState> = mutableMapOf()

    private val cardTint: TintStyle = TintStyle.MultiplyDark(color = Color.BLACK, alpha = 0.15f)

    private var appBackground: BgImage? = null

    private var nextCardId: Long = 0
    private var nextFlowId: Long = 0
    private var selectedFlowIndex: Int = 0
    private var cardFontSizeSp: Float = DEFAULT_CARD_FONT_SIZE_SP

    private var toolbarBasePaddingTop: Int = 0
    private var toolbarBasePaddingBottom: Int = 0
    private var toolbarBaseHeight: Int = 0
    private var pagerBasePaddingStart: Int = 0
    private var pagerBasePaddingTop: Int = 0
    private var pagerBasePaddingEnd: Int = 0
    private var pagerBasePaddingBottom: Int = 0
    private var flowBarBaseMarginBottom: Int = 0

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (!uris.isNullOrEmpty()) {
                uris.forEach(::persistReadPermission)
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
                uris.forEach(::persistReadPermission)
                selectedImages.addAll(uris.map { BgImage.UriRef(it) })
                selectedImages.shuffle()
                imagesAdapter.submit(selectedImages)
                refreshAllFlows()
                saveState()
                snackbar("Added ${uris.size} image(s)")
            } else snackbar("No photos selected")
        }

    private val pickAppBackground =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                handleAppBackgroundUri(uri)
            } else snackbar("No photos selected")
        }

    private val openAppBackground =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                handleAppBackgroundUri(uri)
            } else snackbar("No photos selected")
        }

    private val exportNotesLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                exportNotes(uri)
            } else snackbar(getString(R.string.snackbar_export_cancelled))
        }

    private val importNotesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importNotes(uri)
            } else snackbar(getString(R.string.snackbar_import_cancelled))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        cardFontSizeSp = prefs.getFloat(KEY_CARD_FONT_SIZE, DEFAULT_CARD_FONT_SIZE_SP)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawerLayout)
        val navigationView = findViewById<NavigationView>(R.id.navigationView)
        rootLayout = findViewById(R.id.rootLayout)
        toolbar = findViewById(R.id.toolbar)
        flowPager = findViewById(R.id.flowPager)
        flowBar = findViewById(R.id.flowBar)
        flowChipGroup = findViewById(R.id.flowChips)
        flowChipScroll = findViewById(R.id.flowChipScroll)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        WindowCompat.getInsetsController(window, rootLayout).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        toolbar.title = ""

        val header = navigationView.getHeaderView(0)
        drawerExportNotesButton = header.findViewById(R.id.buttonDrawerExportNotes)
        drawerImportNotesButton = header.findViewById(R.id.buttonDrawerImportNotes)
        drawerRecyclerImages = header.findViewById(R.id.drawerRecyclerImages)
        drawerAddImagesButton = header.findViewById(R.id.buttonDrawerAddImages)
        drawerClearImagesButton = header.findViewById(R.id.buttonDrawerClearImages)
        drawerPickAppBackgroundButton = header.findViewById(R.id.buttonDrawerPickAppBackground)
        drawerResetAppBackgroundButton = header.findViewById(R.id.buttonDrawerResetAppBackground)
        appBackgroundPreview = header.findViewById(R.id.imageAppBackgroundPreview)
        cardFontSizeSlider = header.findViewById(R.id.sliderCardFontSize)
        cardFontSizeValue = header.findViewById(R.id.textCardFontSizeValue)
        val sliderMin = cardFontSizeSlider.valueFrom
        val sliderMax = cardFontSizeSlider.valueTo
        val initialSliderValue = cardFontSizeSp.coerceIn(sliderMin, sliderMax)
        cardFontSizeSlider.value = initialSliderValue
        updateCardFontSize(initialSliderValue, fromUser = false)
        cardFontSizeSlider.addOnChangeListener { _, value, fromUser ->
            updateCardFontSize(value, fromUser)
        }

        toolbarBasePaddingTop = toolbar.paddingTop
        toolbarBasePaddingBottom = toolbar.paddingBottom
        toolbarBaseHeight = toolbar.layoutParams.height.takeIf { it > 0 }
            ?: toolbar.minimumHeight.takeIf { it > 0 }
            ?: (56 * resources.displayMetrics.density).roundToInt()
        pagerBasePaddingStart = flowPager.paddingStart
        pagerBasePaddingTop = flowPager.paddingTop
        pagerBasePaddingEnd = flowPager.paddingEnd
        pagerBasePaddingBottom = flowPager.paddingBottom
        flowBarBaseMarginBottom =
            (flowBar.layoutParams as? CoordinatorLayout.LayoutParams)?.bottomMargin ?: 0

        toolbar.navigationIcon = AppCompatResources.getDrawable(this, R.drawable.ic_menu_drawer)
        toolbar.setNavigationOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }

        setupToolbarActions()
        flowAdapter = FlowPagerAdapter()
        setupFlowPager()

        drawerRecyclerImages.layoutManager = GridLayoutManager(this, 3)
        imagesAdapter = SelectedImagesAdapter(onDelete = { img ->
            selectedImages.remove(img)
            imagesAdapter.submit(selectedImages)
            refreshAllFlows()
            val backgroundCleared = appBackground == img
            if (backgroundCleared) setAppBackground(null, announce = false, persist = false)
            saveState()
            val message = if (backgroundCleared) {
                getString(R.string.snackbar_removed_image_reset_app_background)
            } else getString(R.string.snackbar_removed_image)
            snackbar(message)
        }, onSelect = { img ->
            setAppBackground(img)
            drawerLayout.closeDrawer(GravityCompat.START)
        })
        drawerRecyclerImages.adapter = imagesAdapter

        drawerExportNotesButton.setOnClickListener {
            val defaultName = buildExportFileName()
            exportNotesLauncher.launch(defaultName)
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        drawerImportNotesButton.setOnClickListener {
            importNotesLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        drawerAddImagesButton.setOnClickListener { launchPicker() }
        drawerClearImagesButton.setOnClickListener {
            if (selectedImages.isEmpty()) {
                snackbar(getString(R.string.snackbar_no_images_to_clear))
            } else {
                selectedImages.clear()
                imagesAdapter.submit(selectedImages)
                refreshAllFlows()
                setAppBackground(null, announce = false, persist = false)
                saveState()
                snackbar(getString(R.string.snackbar_cleared_all_images))
            }
        }
        drawerPickAppBackgroundButton.setOnClickListener { launchAppBackgroundPicker() }
        drawerResetAppBackgroundButton.setOnClickListener {
            setAppBackground(null)
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        loadState()

        imagesAdapter.submit(selectedImages)
        refreshAllFlows()
        applyCardFontSizeToAdapters()

        applyAppBackground()

        flowAdapter.notifyDataSetChanged()
        val initialIndex = if (flows.isEmpty()) 0 else selectedFlowIndex.coerceIn(0, flows.lastIndex)
        if (flowPager.currentItem != initialIndex) {
            flowPager.setCurrentItem(initialIndex, false)
        }
        selectedFlowIndex = initialIndex
        renderFlowChips(initialIndex)
        updateChipSelection(initialIndex)
        updateToolbarSubtitle()

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.updateLayoutParams<ViewGroup.LayoutParams> {
                height = toolbarBaseHeight + systemBars.top
            }
            toolbar.updatePadding(
                top = toolbarBasePaddingTop + systemBars.top,
                bottom = toolbarBasePaddingBottom
            )
            flowPager.setPaddingRelative(
                pagerBasePaddingStart,
                pagerBasePaddingTop,
                pagerBasePaddingEnd,
                pagerBasePaddingBottom
            )
            flowBar.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                bottomMargin = flowBarBaseMarginBottom + systemBars.bottom
            }
            insets
        }
        ViewCompat.requestApplyInsets(rootLayout)

        onBackPressedDispatcher.addCallback(this) {
            if (searchMenuItem?.isActionViewExpanded == true) {
                searchMenuItem?.collapseActionView()
                return@addCallback
            }
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
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
                selectedFlowIndex = position
                prefs.edit().putInt(KEY_SELECTED_FLOW_INDEX, position).apply()
                updateChipSelection(position)
                updateToolbarSubtitle()
                updateShuffleMenuState()
            }
        })
    }

    private fun setupToolbarActions() {
        toolbar.menu.clear()
        menuInflater.inflate(R.menu.menu_main, toolbar.menu)
        setupSearchAction(toolbar.menu.findItem(R.id.action_search_cards))
        updateShuffleMenuState()
        toolbar.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                R.id.action_shuffle_cards -> { toggleShuffleCards(); true }
                R.id.action_add_card -> { showAddCardDialog(); true }
                R.id.action_add_handwriting -> { showAddHandwritingDialog(); true }
                R.id.action_add_flow -> { showAddFlowDialog(); true }
                else -> false
            }
        }
    }

    private fun setupSearchAction(searchItem: MenuItem?) {
        searchMenuItem = searchItem
        val view = searchItem?.actionView as? SearchView ?: run {
            searchView = null
            return
        }
        searchView = view
        view.queryHint = getString(R.string.search_cards_hint)
        view.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        view.imeOptions = EditorInfo.IME_ACTION_SEARCH
        view.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                setSearchQuery(query.orEmpty(), restoreStateWhenCleared = true)
                view.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                val value = newText.orEmpty()
                if (value == searchQueryText) return true
                setSearchQuery(value, restoreStateWhenCleared = true)
                return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                val flow = currentFlow()
                val controller = currentController()
                if (flow != null && controller != null) {
                    controller.captureState(flow)
                }
                view.setQuery(searchQueryText, false)
                view.post { view.requestFocus() }
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                view.clearFocus()
                if (searchQueryText.isNotEmpty()) {
                    view.setQuery("", false)
                }
                return true
            }
        })

        if (searchQueryText.isNotEmpty()) {
            searchItem.expandActionView()
            view.setQuery(searchQueryText, false)
            view.clearFocus()
        } else {
            view.setQuery("", false)
        }
    }

    private fun setSearchQuery(rawQuery: String, restoreStateWhenCleared: Boolean) {
        val normalized = rawQuery.trim()
        val rawChanged = rawQuery != searchQueryText
        val normalizedChanged = normalized != searchQueryNormalized
        if (!rawChanged && !normalizedChanged) return
        searchQueryText = rawQuery
        searchQueryNormalized = normalized
        updateSearchResults(restoreStateWhenCleared)
    }

    private fun updateSearchResults(restoreStateWhenCleared: Boolean) {
        val normalized = searchQueryNormalized
        flowControllers.forEach { (id, controller) ->
            val flow = flows.firstOrNull { it.id == id } ?: return@forEach
            val shouldRestoreState = normalized.isEmpty() && restoreStateWhenCleared
            val shouldScrollToTop = normalized.isNotEmpty()
            controller.updateDisplayedCards(
                flow = flow,
                query = normalized,
                shouldRestoreState = shouldRestoreState,
                shouldScrollToTop = shouldScrollToTop
            )
        }
    }

    private fun updateToolbarSubtitle() {
        toolbar.subtitle = ""
    }

    private fun updateShuffleMenuState() {
        val menuItem = toolbar.menu.findItem(R.id.action_shuffle_cards) ?: return
        val flow = currentFlow()
        val isShuffled = flow?.let { flowShuffleStates.containsKey(it.id) } == true
        menuItem.isCheckable = true
        menuItem.isChecked = isShuffled
        menuItem.title = getString(
            if (isShuffled) R.string.menu_unshuffle_cards else R.string.menu_shuffle_cards
        )
    }

    private fun updateChipSelection(position: Int) {
        for (i in 0 until flowChipGroup.childCount) {
            val chip = flowChipGroup.getChildAt(i) as? Chip ?: continue
            chip.isChecked = i == position
        }
        centerSelectedChip(position)
    }

    private fun renderFlowChips(selectedIndex: Int = selectedFlowIndex) {
        val safeIndex = selectedIndex.coerceIn(0, max(0, flows.lastIndex))
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
                isChecked = index == safeIndex
                setOnClickListener { flowPager.setCurrentItem(index, true) }
                setOnLongClickListener {
                    showDeleteFlowDialog(index)
                    true
                }
            }
            flowChipGroup.addView(chip)
        }
        centerSelectedChip(safeIndex)
    }

    private fun toggleShuffleCards() {
        val flow = currentFlow()
        if (flow == null) {
            snackbar(getString(R.string.snackbar_add_flow_first))
            return
        }
        val isCurrentlyShuffled = flowShuffleStates.containsKey(flow.id)
        if (!isCurrentlyShuffled && flow.cards.size < 2) {
            snackbar(getString(R.string.snackbar_not_enough_cards_to_shuffle))
            updateShuffleMenuState()
            return
        }

        val controller = currentController()
        controller?.captureState(flow)

        if (!isCurrentlyShuffled) {
            val originalOrder = flow.cards.map { it.id }.toMutableList()
            val shuffled = flow.cards.toMutableList().also { it.shuffle() }
            flow.cards.clear()
            flow.cards.addAll(shuffled)
            val state = FlowShuffleState(originalOrder)
            flowShuffleStates[flow.id] = state
            state.syncWith(flow)
        } else {
            val state = flowShuffleStates.remove(flow.id)
            if (state != null) {
                val orderMap = state.originalOrder.withIndex().associate { (index, id) -> id to index }
                flow.cards.sortWith(
                    compareBy<CardItem> { orderMap[it.id] ?: Int.MAX_VALUE }
                        .thenByDescending { it.updatedAt }
                )
            }
        }

        applyCardBackgrounds(flow)

        if (controller != null) {
            controller.updateDisplayedCards(
                flow = flow,
                query = searchQueryNormalized,
                shouldRestoreState = searchQueryNormalized.isEmpty(),
                shouldScrollToTop = searchQueryNormalized.isNotEmpty()
            )
        } else {
            val index = flows.indexOfFirst { it.id == flow.id }
            if (index >= 0) flowAdapter.notifyItemChanged(index)
        }

        updateShuffleMenuState()
        saveState()
    }

    private fun centerSelectedChip(position: Int) {
        if (position !in 0 until flowChipGroup.childCount) return
        val chip = flowChipGroup.getChildAt(position) ?: return
        flowChipScroll.post {
            if (chip.parent == null) return@post
            val scrollWidth = flowChipScroll.width
            val chipWidth = chip.width
            if (scrollWidth == 0 || chipWidth == 0) {
                flowChipScroll.post { centerSelectedChip(position) }
                return@post
            }
            val chipCenter = chip.left + chipWidth / 2
            val target = chipCenter - scrollWidth / 2
            val maxScroll = max(0, flowChipGroup.width - scrollWidth)
            flowChipScroll.smoothScrollTo(target.coerceIn(0, maxScroll), 0)
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
        selectedFlowIndex = flows.lastIndex
        renderFlowChips(selectedFlowIndex)
        flowPager.setCurrentItem(flows.lastIndex, true)
        updateToolbarSubtitle()
        saveState()
        snackbar(getString(R.string.snackbar_added_flow, name))
    }

    private fun showDeleteFlowDialog(index: Int) {
        if (flows.size <= 1) {
            snackbar(getString(R.string.snackbar_cannot_delete_last_flow))
            return
        }
        val flow = flows.getOrNull(index) ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_delete_flow_title, flow.name))
            .setMessage(getString(R.string.dialog_delete_flow_message, flow.name))
            .setPositiveButton(R.string.dialog_delete) { _, _ -> removeFlow(index) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeFlow(index: Int) {
        if (index !in flows.indices) return
        if (flows.size <= 1) {
            snackbar(getString(R.string.snackbar_cannot_delete_last_flow))
            return
        }
        val currentItem = flowPager.currentItem.coerceIn(0, max(0, flows.lastIndex))
        val removed = flows.removeAt(index)
        flowShuffleStates.remove(removed.id)
        removed.cards.forEach { card ->
            card.handwriting?.let { deleteHandwritingFiles(it) }
        }
        flowControllers.remove(removed.id)
        flowAdapter.notifyItemRemoved(index)
        val remainingLastIndex = flows.lastIndex
        if (remainingLastIndex < 0) {
            selectedFlowIndex = 0
            renderFlowChips(0)
            updateToolbarSubtitle()
            updateShuffleMenuState()
            saveState()
            return
        }
        val target = when {
            currentItem > remainingLastIndex -> remainingLastIndex
            index <= currentItem && currentItem > 0 -> currentItem - 1
            else -> currentItem.coerceIn(0, remainingLastIndex)
        }
        val safeTarget = target.coerceIn(0, remainingLastIndex)
        selectedFlowIndex = safeTarget
        renderFlowChips(safeTarget)
        flowPager.setCurrentItem(safeTarget, false)
        updateToolbarSubtitle()
        updateShuffleMenuState()
        saveState()
        snackbar(getString(R.string.snackbar_deleted_flow, removed.name))
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
        val baseSide = availableWidth
        val focusSide = availableWidth
        val pitch = (availableWidth * 0.26f).roundToInt()
        return RightRailFlowLayoutManager(
            baseSidePx = baseSide,
            focusSidePx = focusSide,
            itemPitchPx = pitch,
            rightInsetPx = (8 * density).roundToInt()
        )
    }

    private fun prepareFlowCards(flow: CardFlow) {
        if (!isFlowShuffled(flow.id)) {
            flow.cards.sortByDescending { it.updatedAt }
        } else {
            flowShuffleStates[flow.id]?.syncWith(flow)
        }
        applyCardBackgrounds(flow)
    }

    private fun filterCardsForSearch(cards: List<CardItem>, query: String): List<CardItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return cards.toList()
        return cards.filter { card ->
            card.title.contains(trimmed, ignoreCase = true) ||
                card.snippet.contains(trimmed, ignoreCase = true)
        }
    }

    private fun refreshFlow(flow: CardFlow, scrollToTop: Boolean = false) {
        prepareFlowCards(flow)
        val controller = flowControllers[flow.id]
        if (scrollToTop && searchQueryNormalized.isEmpty()) {
            flow.lastViewedCardIndex = 0
            flow.lastViewedCardId = flow.cards.firstOrNull()?.id
            flow.lastViewedCardFocused = false
        }
        if (controller != null) {
            val shouldRestoreState = searchQueryNormalized.isEmpty() && !scrollToTop
            val shouldScrollToTop = scrollToTop || searchQueryNormalized.isNotEmpty()
            controller.updateDisplayedCards(
                flow = flow,
                query = searchQueryNormalized,
                shouldRestoreState = shouldRestoreState,
                shouldScrollToTop = shouldScrollToTop
            )
        } else {
            val index = flows.indexOfFirst { it.id == flow.id }
            if (index >= 0) flowAdapter.notifyItemChanged(index)
        }
        if (flow == currentFlow()) {
            updateShuffleMenuState()
        }
    }

    private fun refreshAllFlows(scrollToTopCurrent: Boolean = false) {
        flows.forEach { flow ->
            val shouldScroll = scrollToTopCurrent && flow == currentFlow()
            refreshFlow(flow, shouldScroll)
        }
    }

    private fun isFlowShuffled(flowId: Long): Boolean = flowShuffleStates.containsKey(flowId)

    private fun applyCardBackgrounds(flow: CardFlow) {
        val fallback: BgImage = BgImage.Res(R.drawable.bg_placeholder)
        val backgrounds = if (selectedImages.isNotEmpty()) selectedImages else listOf(fallback)
        flow.cards.forEach { card ->
            val chosenBg = if (selectedImages.isEmpty()) {
                fallback
            } else {
                val seed = card.id * 31L + flow.id
                val random = Random(seed)
                backgrounds[random.nextInt(backgrounds.size)]
            }
            card.bg = chosenBg
        }
    }

    private fun cardsInOriginalOrder(flow: CardFlow, state: FlowShuffleState): List<CardItem> {
        val cardsById = flow.cards.associateBy { it.id }
        val ordered = state.originalOrder.mapNotNull { cardsById[it] }
        if (ordered.size == flow.cards.size) return ordered
        val knownIds = state.originalOrder.toHashSet()
        val remaining = flow.cards.filterNot { knownIds.contains(it.id) }
        return ordered + remaining
    }

    private fun persistControllerState(flowId: Long) {
        val flow = flows.firstOrNull { it.id == flowId } ?: return
        flowControllers[flowId]?.captureState(flow)
    }

    private fun captureVisibleFlowStates() {
        flowControllers.forEach { (id, controller) ->
            val flow = flows.firstOrNull { it.id == id } ?: return@forEach
            controller.captureState(flow)
        }
    }

    private fun ensureSeedCards(flow: CardFlow): Boolean {
        if (flow.cards.isNotEmpty()) return false
        val variants = listOf(
            "Ping me when you’re free.",
            "Grows toward center. Two neighbors overlap a bit.",
            "This is a slightly longer body so you can see the card stretch beyond a couple of lines.",
            "Here’s a longer description to show adaptive height. Tap to focus; tap again to defocus.",
            "Long body to approach the 2/3 screen-height cap.",
            "EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height."
        )
        repeat(30) { i ->
            flow.cards += CardItem(
                id = nextCardId++,
                title = "",
                snippet = variants[i % variants.size],
                bg = BgImage.Res(R.drawable.bg_placeholder),
                updatedAt = System.currentTimeMillis() - (i * 90L * 60L * 1000L)
            )
        }
        flow.lastViewedCardIndex = 0
        flow.lastViewedCardId = flow.cards.firstOrNull()?.id
        flow.lastViewedCardFocused = false
        return true
    }

    private fun showAddCardDialog() {
        val flow = currentFlow()
        if (flow == null) {
            snackbar(getString(R.string.snackbar_add_flow_first))
            return
        }
        showCardEditor(initialSnippet = "", isNew = true, onSave = { snippet ->
            val finalSnippet = snippet.trim().ifBlank { "Tap to edit this card." }
            val card = CardItem(
                id = nextCardId++,
                title = "",
                snippet = finalSnippet,
                updatedAt = System.currentTimeMillis()
            )
            flow.cards += card
            refreshFlow(flow, scrollToTop = true)
            saveState()
            snackbar("Added card")
        })
    }

    private fun showAddHandwritingDialog() {
        val flow = currentFlow()
        if (flow == null) {
            snackbar(getString(R.string.snackbar_add_flow_first))
            return
        }
        val defaults = defaultHandwritingOptions()
        showHandwritingDialog(
            titleRes = R.string.dialog_add_handwriting_title,
            existing = null,
            initialOptions = defaults,
            face = HandwritingFace.FRONT,
            onSave = { savedContent ->
                val content = savedContent ?: run {
                    snackbar(getString(R.string.snackbar_handwriting_required))
                    return@showHandwritingDialog
                }
                val card = CardItem(
                    id = nextCardId++,
                    title = "",
                    snippet = "",
                    handwriting = HandwritingContent(content.path, content.options),
                    updatedAt = System.currentTimeMillis()
                )
                flow.cards += card
                refreshFlow(flow, scrollToTop = true)
                saveState()
                snackbar(getString(R.string.snackbar_added_handwriting))
            }
        )
    }

    private fun editCard(flow: CardFlow, index: Int, face: HandwritingFace = HandwritingFace.FRONT) {
        flowControllers[flow.id]?.captureState(flow)
        val card = flow.cards.getOrNull(index) ?: return
        val handwritingContent = card.handwriting
        if (handwritingContent != null) {
            showHandwritingDialog(
                titleRes = R.string.dialog_edit_handwriting_title,
                existing = if (face == HandwritingFace.BACK) handwritingContent.back else HandwritingSide(handwritingContent.path, handwritingContent.options),
                initialOptions = if (face == HandwritingFace.BACK) {
                    handwritingContent.back?.options ?: handwritingContent.options
                } else handwritingContent.options,
                face = face,
                onSave = { savedContent ->
                    if (face == HandwritingFace.BACK) {
                        if (savedContent == null) {
                            card.handwriting?.back?.path?.let { deleteHandwritingFile(it) }
                            card.handwriting?.back = null
                        } else {
                            val frontOptions = card.handwriting?.options ?: savedContent.options
                            val normalizedOptions = synchronizeBackPaper(frontOptions, savedContent.options)
                            if (card.handwriting == null) {
                                card.handwriting = HandwritingContent(savedContent.path, normalizedOptions)
                            }
                            card.handwriting?.back = HandwritingSide(savedContent.path, normalizedOptions)
                        }
                    } else {
                        val content = savedContent ?: run {
                            snackbar(getString(R.string.snackbar_handwriting_required))
                            return@showHandwritingDialog
                        }
                        if (card.handwriting == null) {
                            card.handwriting = HandwritingContent(content.path, content.options)
                        } else {
                            card.handwriting?.path = content.path
                            card.handwriting?.options = content.options
                            card.handwriting?.back?.let { backSide ->
                                backSide.options = synchronizeBackPaper(content.options, backSide.options)
                            }
                        }
                    }
                    card.updatedAt = System.currentTimeMillis()
                    refreshFlow(flow, scrollToTop = false)
                    saveState()
                    snackbar("Card updated")
                },
                onDelete = {
                    card.handwriting?.let { deleteHandwritingFiles(it) }
                    flow.cards.removeAt(index)
                    refreshFlow(flow, scrollToTop = true)
                    saveState()
                    snackbar(getString(R.string.snackbar_deleted_card))
                }
            )
        } else {
            showCardEditor(initialSnippet = card.snippet, isNew = false, onSave = { newSnippet ->
                val snippetValue = newSnippet.trim()
                if (snippetValue.isNotEmpty()) card.snippet = snippetValue
                card.updatedAt = System.currentTimeMillis()
                refreshFlow(flow, scrollToTop = true)
                saveState()
                snackbar("Card updated")
            }, onDelete = {
                card.handwriting?.let { deleteHandwritingFiles(it) }
                flow.cards.remove(card)
                refreshFlow(flow, scrollToTop = true)
                saveState()
                snackbar(getString(R.string.snackbar_deleted_card))
            })
        }
    }

    private fun showCardEditor(
        initialSnippet: String,
        isNew: Boolean,
        onSave: (snippet: String) -> Unit,
        onDelete: (() -> Unit)? = null
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_card, null, false)
        val snippetInput = dialogView.findViewById<EditText>(R.id.inputSnippet)
        snippetInput.setText(initialSnippet)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (isNew) getString(R.string.dialog_add_card_title) else getString(R.string.dialog_edit_card_title))
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                onSave(snippetInput.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (onDelete != null) {
            builder.setNeutralButton(R.string.dialog_delete) { _, _ -> onDelete() }
        }
        builder.show()
    }

    private fun showHandwritingDialog(
        titleRes: Int,
        existing: HandwritingSide?,
        initialOptions: HandwritingOptions,
        face: HandwritingFace,
        onSave: (HandwritingSide?) -> Unit,
        onDelete: (() -> Unit)? = null
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_handwriting, null, false)
        val handwritingView = dialogView.findViewById<HandwritingView>(R.id.handwritingView)
        val handwritingCard = dialogView.findViewById<MaterialCardView>(R.id.cardHandwritingCanvas)
        val undoButton = dialogView.findViewById<MaterialButton>(R.id.buttonUndoHandwriting)
        val clearButton = dialogView.findViewById<MaterialButton>(R.id.buttonClearHandwriting)
        val paletteCard = dialogView.findViewById<MaterialCardView>(R.id.cardHandwritingOptions)
        val penButton = dialogView.findViewById<MaterialButton>(R.id.buttonPenOptions)
        val eraserButton = dialogView.findViewById<MaterialButton>(R.id.buttonEraserOptions)
        val canvasButton = dialogView.findViewById<MaterialButton>(R.id.buttonCanvasOptions)
        val penOptionsContainer = dialogView.findViewById<ViewGroup>(R.id.containerPenOptions)
        val eraserOptionsContainer = dialogView.findViewById<ViewGroup>(R.id.containerEraserOptions)
        val canvasOptionsContainer = dialogView.findViewById<ViewGroup>(R.id.containerCanvasOptions)
        val brushColorGroup = dialogView.findViewById<ChipGroup>(R.id.groupBrushColors)
        val penTypeGroup = dialogView.findViewById<ChipGroup>(R.id.groupPenTypes)
        val brushSizeValue = dialogView.findViewById<TextView>(R.id.textBrushSizeValue)
        val brushSizeSlider = dialogView.findViewById<Slider>(R.id.sliderBrushSize)
        val eraserSizeValue = dialogView.findViewById<TextView>(R.id.textEraserSizeValue)
        val eraserSizeSlider = dialogView.findViewById<Slider>(R.id.sliderEraserSize)
        val eraserTypeGroup = dialogView.findViewById<ChipGroup>(R.id.groupEraserTypes)
        val paperStyleGroup = dialogView.findViewById<ChipGroup>(R.id.groupPaperStyles)
        val paperColorGroup = dialogView.findViewById<ChipGroup>(R.id.groupPaperColors)
        val canvasSizeGroup = dialogView.findViewById<ChipGroup>(R.id.groupCanvasSizes)
        val formatGroup = dialogView.findViewById<ChipGroup>(R.id.groupExportFormats)

        val (maxCanvasWidth, maxCanvasHeight) = cardCanvasBounds()
        data class CanvasSizeOption(val key: String, val label: String, val width: Int, val height: Int)
        data class NamedColor(val color: Int, val label: String)
        data class PaperStyleOption(val style: HandwritingPaperStyle, val label: String, val iconRes: Int)

        fun computeSizeForRatio(key: String, labelRes: Int, ratio: Float): CanvasSizeOption {
            val (width, height) = computeCanvasSizeForRatio(ratio, maxCanvasWidth, maxCanvasHeight)
            return CanvasSizeOption(key, getString(labelRes), width, height)
        }

        val sizeOptions = mutableListOf(
            CanvasSizeOption(
                key = "full",
                label = getString(R.string.handwriting_size_full),
                width = maxCanvasWidth,
                height = maxCanvasHeight
            ),
            computeSizeForRatio("square", R.string.handwriting_size_square, 1f),
            computeSizeForRatio("landscape", R.string.handwriting_size_landscape, 0.75f),
            computeSizeForRatio("portrait", R.string.handwriting_size_portrait, 4f / 3f)
        ).distinctBy { it.width to it.height }.toMutableList()

        var selectedSize = sizeOptions.firstOrNull { it.width == initialOptions.canvasWidth && it.height == initialOptions.canvasHeight }
        if (selectedSize == null) {
            selectedSize = CanvasSizeOption(
                key = "custom",
                label = getString(R.string.handwriting_size_custom, initialOptions.canvasWidth, initialOptions.canvasHeight),
                width = initialOptions.canvasWidth,
                height = initialOptions.canvasHeight
            )
            sizeOptions.add(0, selectedSize)
        }

        val formatOptions = HandwritingFormat.values().toList()
        fun formatLabel(format: HandwritingFormat): String = when (format) {
            HandwritingFormat.PNG -> getString(R.string.handwriting_format_png)
            HandwritingFormat.JPEG -> getString(R.string.handwriting_format_jpeg)
            HandwritingFormat.WEBP -> getString(R.string.handwriting_format_webp)
        }
        var selectedFormat = existing?.options?.format ?: initialOptions.format
        if (selectedFormat !in formatOptions) selectedFormat = HandwritingFormat.PNG

        val paperColorOptions = mutableListOf(
            NamedColor(Color.WHITE, getString(R.string.handwriting_color_white)),
            NamedColor(Color.parseColor("#FFF4E0"), getString(R.string.handwriting_color_cream)),
            NamedColor(Color.parseColor("#FFF1CC"), getString(R.string.handwriting_color_parchment)),
            NamedColor(Color.parseColor("#ECEFF1"), getString(R.string.handwriting_color_fog)),
            NamedColor(Color.parseColor("#E3F2FD"), getString(R.string.handwriting_color_sky)),
            NamedColor(Color.parseColor("#E8F5E9"), getString(R.string.handwriting_color_mint)),
            NamedColor(Color.parseColor("#F3E5F5"), getString(R.string.handwriting_color_lavender)),
            NamedColor(Color.parseColor("#101820"), getString(R.string.handwriting_color_midnight))
        )
        if (paperColorOptions.none { it.color == initialOptions.backgroundColor }) {
            paperColorOptions.add(0, NamedColor(initialOptions.backgroundColor, getString(R.string.handwriting_color_custom)))
        }
        var selectedPaperColor = paperColorOptions.firstOrNull { it.color == initialOptions.backgroundColor }
            ?: paperColorOptions.first()

        val brushColorOptions = mutableListOf(
            NamedColor(Color.BLACK, getString(R.string.handwriting_color_black)),
            NamedColor(Color.parseColor("#424242"), getString(R.string.handwriting_color_graphite)),
            NamedColor(Color.parseColor("#0D47A1"), getString(R.string.handwriting_color_navy)),
            NamedColor(Color.parseColor("#2962FF"), getString(R.string.handwriting_color_cobalt)),
            NamedColor(Color.parseColor("#00897B"), getString(R.string.handwriting_color_teal)),
            NamedColor(Color.parseColor("#2E7D32"), getString(R.string.handwriting_color_green)),
            NamedColor(Color.parseColor("#F9A825"), getString(R.string.handwriting_color_amber)),
            NamedColor(Color.parseColor("#C62828"), getString(R.string.handwriting_color_red)),
            NamedColor(Color.parseColor("#AD1457"), getString(R.string.handwriting_color_magenta)),
            NamedColor(Color.parseColor("#6A1B9A"), getString(R.string.handwriting_color_violet))
        )
        if (brushColorOptions.none { it.color == initialOptions.brushColor }) {
            brushColorOptions.add(0, NamedColor(initialOptions.brushColor, getString(R.string.handwriting_color_custom)))
        }
        var selectedBrushColor = brushColorOptions.firstOrNull { it.color == initialOptions.brushColor }
            ?: brushColorOptions.first()

        val paperStyleOptions = listOf(
            PaperStyleOption(HandwritingPaperStyle.PLAIN, getString(R.string.handwriting_paper_plain), R.drawable.ic_handwriting_paper_plain),
            PaperStyleOption(HandwritingPaperStyle.RULED, getString(R.string.handwriting_paper_ruled), R.drawable.ic_handwriting_paper_ruled),
            PaperStyleOption(HandwritingPaperStyle.GRID, getString(R.string.handwriting_paper_grid), R.drawable.ic_handwriting_paper_grid)
        )
        var selectedPaperStyle = initialOptions.paperStyle.takeIf { option ->
            paperStyleOptions.any { it.style == option }
        } ?: HandwritingPaperStyle.PLAIN

        val penTypeOptions = listOf(
            HandwritingPenType.ROUND to getString(R.string.handwriting_pen_type_round),
            HandwritingPenType.MARKER to getString(R.string.handwriting_pen_type_marker),
            HandwritingPenType.CALLIGRAPHY to getString(R.string.handwriting_pen_type_calligraphy),
            HandwritingPenType.HIGHLIGHTER to getString(R.string.handwriting_pen_type_highlighter)
        )
        var selectedPenType = initialOptions.penType.takeIf { pen -> penTypeOptions.any { it.first == pen } }
            ?: HandwritingPenType.ROUND

        brushSizeSlider.valueFrom = MIN_HANDWRITING_BRUSH_SIZE_DP
        brushSizeSlider.valueTo = MAX_HANDWRITING_BRUSH_SIZE_DP
        brushSizeSlider.stepSize = 0.25f

        val minBrush = brushSizeSlider.valueFrom
        val maxBrush = brushSizeSlider.valueTo
        var selectedBrushSize = initialOptions.brushSizeDp.coerceIn(minBrush, maxBrush)

        fun updateBrushSizeLabel() {
            brushSizeValue.text = getString(R.string.handwriting_brush_size_value, selectedBrushSize)
        }

        val eraserTypeOptions = listOf(
            HandwritingEraserType.ROUND to getString(R.string.handwriting_eraser_type_round),
            HandwritingEraserType.BLOCK to getString(R.string.handwriting_eraser_type_block)
        )
        var selectedEraserType = initialOptions.eraserType.takeIf { type ->
            eraserTypeOptions.any { it.first == type }
        } ?: HandwritingEraserType.ROUND

        eraserSizeSlider.valueFrom = MIN_HANDWRITING_ERASER_SIZE_DP
        eraserSizeSlider.valueTo = MAX_HANDWRITING_ERASER_SIZE_DP
        eraserSizeSlider.stepSize = 0.5f

        val minEraser = eraserSizeSlider.valueFrom
        val maxEraser = eraserSizeSlider.valueTo
        var selectedEraserSize = initialOptions.eraserSizeDp.coerceIn(minEraser, maxEraser)

        fun updateEraserSizeLabel() {
            eraserSizeValue.text = getString(R.string.handwriting_eraser_size_value, selectedEraserSize)
        }

        var selectedPalette = loadHandwritingPaletteSection()
        var selectedDrawingTool = when (selectedPalette) {
            HandwritingPaletteSection.PEN -> HandwritingDrawingTool.PEN
            HandwritingPaletteSection.ERASER -> HandwritingDrawingTool.ERASER
            HandwritingPaletteSection.CANVAS -> loadHandwritingDrawingTool()
        }

        fun applyCanvasCardDisplaySize(contentWidthPx: Int, contentHeightPx: Int) {
            if (contentWidthPx <= 0 || contentHeightPx <= 0) return
            val widthScale = maxCanvasWidth.toFloat() / contentWidthPx.toFloat()
            val heightScale = maxCanvasHeight.toFloat() / contentHeightPx.toFloat()
            val scale = listOf(widthScale, heightScale)
                .filter { it.isFinite() && it > 0f }
                .minOrNull()
                ?: 1f
            val targetWidth = (contentWidthPx * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (contentHeightPx * scale).roundToInt().coerceAtLeast(1)
            handwritingCard.updateLayoutParams<ViewGroup.LayoutParams> {
                if (width != targetWidth) {
                    width = targetWidth
                }
            }
            handwritingView.minimumHeight = targetHeight
        }

        fun createChoiceChip(
            text: String,
            iconRes: Int? = null,
            iconTint: Int? = null,
            showLabel: Boolean = true,
            fillColor: Int? = null
        ): Chip {
            val themedContext = ContextThemeWrapper(
                this,
                com.google.android.material.R.style.Widget_MaterialComponents_Chip_Choice
            )
            return Chip(themedContext).apply {
                id = View.generateViewId()
                this.text = if (showLabel) text else ""
                isCheckable = true
                isClickable = true
                isFocusable = true
                isCheckedIconVisible = true
                if (iconRes != null) {
                    setChipIconResource(iconRes)
                    isChipIconVisible = true
                    iconTint?.let { tintColor -> chipIconTint = ColorStateList.valueOf(tintColor) }
                }
                if (!showLabel) {
                    contentDescription = text
                }
                fillColor?.let { colorInt ->
                    val density = themedContext.resources.displayMetrics.density
                    val onColor = if (ColorUtils.calculateLuminance(colorInt) < 0.5f) {
                        Color.WHITE
                    } else {
                        Color.BLACK
                    }
                    chipBackgroundColor = ColorStateList.valueOf(colorInt)
                    checkedIconTint = ColorStateList.valueOf(onColor)
                    val uncheckedStroke = ColorUtils.setAlphaComponent(onColor, (0.28f * 255).roundToInt())
                    val checkedStroke = ColorUtils.setAlphaComponent(onColor, (0.54f * 255).roundToInt())
                    chipStrokeColor = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(checkedStroke, uncheckedStroke)
                    )
                    chipStrokeWidth = (1.5f * density)
                    rippleColor = ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(onColor, (0.16f * 255).roundToInt())
                    )
                    setTextColor(onColor)
                    isChipIconVisible = false
                }
            }
        }

        brushColorGroup.removeAllViews()
        brushColorOptions.forEach { option ->
            val chip = createChoiceChip(
                option.label,
                showLabel = false,
                fillColor = option.color
            ).apply {
                tag = option
                isChecked = option == selectedBrushColor
            }
            brushColorGroup.addView(chip)
        }

        penTypeGroup.removeAllViews()
        penTypeOptions.forEach { (penType, label) ->
            val chip = createChoiceChip(label).apply {
                tag = penType
                isChecked = penType == selectedPenType
            }
            penTypeGroup.addView(chip)
        }

        eraserTypeGroup.removeAllViews()
        eraserTypeOptions.forEach { (eraserType, label) ->
            val chip = createChoiceChip(label).apply {
                tag = eraserType
                isChecked = eraserType == selectedEraserType
            }
            eraserTypeGroup.addView(chip)
        }

        paperStyleGroup.removeAllViews()
        paperStyleOptions.forEach { option ->
            val chip = createChoiceChip(option.label, option.iconRes).apply {
                tag = option
                isChecked = option.style == selectedPaperStyle
            }
            paperStyleGroup.addView(chip)
        }

        paperColorGroup.removeAllViews()
        paperColorOptions.forEach { option ->
            val chip = createChoiceChip(
                option.label,
                showLabel = false,
                fillColor = option.color
            ).apply {
                tag = option
                isChecked = option == selectedPaperColor
            }
            paperColorGroup.addView(chip)
        }

        canvasSizeGroup.removeAllViews()
        sizeOptions.forEach { option ->
            val chip = createChoiceChip(option.label).apply {
                tag = option
                isChecked = option == selectedSize
            }
            canvasSizeGroup.addView(chip)
        }

        formatGroup.removeAllViews()
        formatOptions.forEach { option ->
            val chip = createChoiceChip(formatLabel(option)).apply {
                tag = option
                isChecked = option == selectedFormat
            }
            formatGroup.addView(chip)
        }

        fun updateHistoryButtons() {
            undoButton.isEnabled = handwritingView.canUndo()
            clearButton.isEnabled = handwritingView.hasDrawing()
        }

        applyCanvasCardDisplaySize(selectedSize.width, selectedSize.height)
        handwritingView.setCanvasSize(selectedSize.width, selectedSize.height)
        handwritingView.setCanvasBackgroundColor(selectedPaperColor.color)
        handwritingView.setPaperStyle(selectedPaperStyle)
        handwritingView.setBrushColor(selectedBrushColor.color)
        handwritingView.setBrushSizeDp(selectedBrushSize)
        handwritingView.setPenType(selectedPenType)
        handwritingView.setEraserType(selectedEraserType)
        handwritingView.setEraserSizeDp(selectedEraserSize)
        handwritingView.setOnContentChangedListener { updateHistoryButtons() }
        existing?.path?.let { path ->
            loadHandwritingBitmap(path)?.let { handwritingView.setBitmap(it) }
        }

        undoButton.setOnClickListener {
            if (handwritingView.undo()) updateHistoryButtons()
        }
        clearButton.setOnClickListener {
            handwritingView.clear()
            updateHistoryButtons()
        }

        brushColorGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val option = group.findViewById<Chip>(checkedId)?.tag as? NamedColor ?: return@setOnCheckedStateChangeListener
            selectedBrushColor = option
            handwritingView.setBrushColor(option.color)
        }

        penTypeGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val option = group.findViewById<Chip>(checkedId)?.tag as? HandwritingPenType ?: return@setOnCheckedStateChangeListener
            selectedPenType = option
            handwritingView.setPenType(option)
        }

        eraserTypeGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val option = group.findViewById<Chip>(checkedId)?.tag as? HandwritingEraserType
                ?: return@setOnCheckedStateChangeListener
            selectedEraserType = option
            handwritingView.setEraserType(option)
        }

        paperStyleGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val option = group.findViewById<Chip>(checkedId)?.tag as? PaperStyleOption ?: return@setOnCheckedStateChangeListener
            selectedPaperStyle = option.style
            handwritingView.setPaperStyle(option.style)
        }

        paperColorGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val option = group.findViewById<Chip>(checkedId)?.tag as? NamedColor ?: return@setOnCheckedStateChangeListener
            selectedPaperColor = option
            handwritingView.setCanvasBackgroundColor(option.color)
        }

        canvasSizeGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val option = group.findViewById<Chip>(checkedId)?.tag as? CanvasSizeOption ?: return@setOnCheckedStateChangeListener
            selectedSize = option
            applyCanvasCardDisplaySize(option.width, option.height)
            handwritingView.setCanvasSize(option.width, option.height)
        }

        formatGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val option = group.findViewById<Chip>(checkedId)?.tag as? HandwritingFormat ?: return@setOnCheckedStateChangeListener
            selectedFormat = option
        }

        brushSizeSlider.value = selectedBrushSize
        updateBrushSizeLabel()
        brushSizeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                selectedBrushSize = value
                handwritingView.setBrushSizeDp(value)
                updateBrushSizeLabel()
            }
        }

        eraserSizeSlider.value = selectedEraserSize
        updateEraserSizeLabel()
        eraserSizeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                selectedEraserSize = value
                handwritingView.setEraserSizeDp(value)
                updateEraserSizeLabel()
            }
        }

        var visiblePalette: HandwritingPaletteSection? = null

        fun updateToolButtons() {
            penButton.isChecked = selectedDrawingTool == HandwritingDrawingTool.PEN
            eraserButton.isChecked = selectedDrawingTool == HandwritingDrawingTool.ERASER
            canvasButton.isChecked = visiblePalette == HandwritingPaletteSection.CANVAS
        }

        fun setDrawingTool(tool: HandwritingDrawingTool) {
            selectedDrawingTool = tool
            handwritingView.setDrawingTool(tool)
            updateToolButtons()
        }

        fun showPalette(section: HandwritingPaletteSection) {
            visiblePalette = section
            selectedPalette = section
            paletteCard.isVisible = true
            penOptionsContainer.isVisible = section == HandwritingPaletteSection.PEN
            eraserOptionsContainer.isVisible = section == HandwritingPaletteSection.ERASER
            canvasOptionsContainer.isVisible = section == HandwritingPaletteSection.CANVAS
            updateToolButtons()
        }

        fun hidePalette() {
            visiblePalette = null
            paletteCard.isGone = true
            penOptionsContainer.isGone = true
            eraserOptionsContainer.isGone = true
            canvasOptionsContainer.isGone = true
            updateToolButtons()
        }

        setDrawingTool(selectedDrawingTool)
        hidePalette()

        penButton.setOnClickListener {
            setDrawingTool(HandwritingDrawingTool.PEN)
            if (visiblePalette == HandwritingPaletteSection.PEN) {
                hidePalette()
            } else {
                showPalette(HandwritingPaletteSection.PEN)
            }
        }

        eraserButton.setOnClickListener {
            setDrawingTool(HandwritingDrawingTool.ERASER)
            if (visiblePalette == HandwritingPaletteSection.ERASER) {
                hidePalette()
            } else {
                showPalette(HandwritingPaletteSection.ERASER)
            }
        }

        canvasButton.setOnClickListener {
            if (visiblePalette == HandwritingPaletteSection.CANVAS) {
                hidePalette()
            } else {
                showPalette(HandwritingPaletteSection.CANVAS)
            }
        }
        updateHistoryButtons()

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(titleRes))
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .apply {
                if (onDelete != null) {
                    setNeutralButton(R.string.dialog_delete, null)
                }
            }
            .create()

        dialog.setOnShowListener {
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val exportBitmap = handwritingView.exportBitmap()
                if (exportBitmap == null) {
                    snackbar(getString(R.string.snackbar_handwriting_save_failed))
                    return@setOnClickListener
                }
                val options = HandwritingOptions(
                    backgroundColor = selectedPaperColor.color,
                    brushColor = selectedBrushColor.color,
                    brushSizeDp = selectedBrushSize,
                    canvasWidth = selectedSize.width,
                    canvasHeight = selectedSize.height,
                    format = selectedFormat,
                    paperStyle = selectedPaperStyle,
                    penType = selectedPenType,
                    eraserSizeDp = selectedEraserSize,
                    eraserType = selectedEraserType
                )
                val saved = saveHandwritingContent(exportBitmap, options, existing)
                exportBitmap.recycle()
                if (saved == null) {
                    snackbar(getString(R.string.snackbar_handwriting_save_failed))
                    return@setOnClickListener
                }
                persistHandwritingDefaults(options, selectedPalette, selectedDrawingTool)
                onSave(saved)
                dialog.dismiss()
            }
            onDelete?.let { deleteCallback ->
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                    deleteCallback()
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    private fun launchPicker() {
        if (isPhotoPickerAvailable()) {
            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            openDocs.launch(arrayOf("image/*"))
        }
    }

    private fun launchAppBackgroundPicker() {
        if (isPhotoPickerAvailable()) {
            pickAppBackground.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            openAppBackground.launch(arrayOf("image/*"))
        }
    }

    private fun handleAppBackgroundUri(uri: Uri) {
        persistReadPermission(uri)
        setAppBackground(BgImage.UriRef(uri))
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Provider did not grant persistable permissions (e.g. Photo Picker on API 34+).
        } catch (_: IllegalArgumentException) {
            // Uri cannot persist permissions; ignore since the grant isn't required in that case.
        }
    }

    private fun setAppBackground(image: BgImage?, announce: Boolean = true, persist: Boolean = true) {
        appBackground = image
        val applied = applyAppBackground()
        if (announce) {
            val message = when {
                image == null -> getString(R.string.snackbar_app_background_reset)
                applied -> getString(R.string.snackbar_app_background_updated)
                else -> getString(R.string.snackbar_app_background_failed)
            }
            snackbar(message)
        }
        if (persist) saveState()
    }

    private fun applyAppBackground(): Boolean {
        val defaultRes = R.drawable.bg_app_light_optimistic
        val bg = appBackground
        if (bg == null) {
            rootLayout.setBackgroundResource(defaultRes)
            appBackgroundPreview.setImageResource(defaultRes)
            return true
        }
        when (bg) {
            is BgImage.Res -> {
                rootLayout.setBackgroundResource(bg.id)
                appBackgroundPreview.setImageResource(bg.id)
                return true
            }
            is BgImage.UriRef -> {
                val metrics = resources.displayMetrics
                val target = max(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(720)
                val bitmap = decodeBitmapSafe(bg, target) ?: run {
                    appBackground = null
                    rootLayout.setBackgroundResource(defaultRes)
                    appBackgroundPreview.setImageResource(defaultRes)
                    return false
                }
                rootLayout.background = BitmapDrawable(resources, bitmap)
                appBackgroundPreview.setImageBitmap(bitmap)
                return true
            }
        }
        return true
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

    private fun saveHandwritingContent(
        bitmap: Bitmap,
        options: HandwritingOptions,
        existing: HandwritingSide?
    ): HandwritingSide? {
        val reuseExisting = existing?.takeIf { it.options.format == options.format }
        val filename = reuseExisting?.path ?: "handwriting_${System.currentTimeMillis()}.${options.format.extension}"
        val result = runCatching {
            openFileOutput(filename, MODE_PRIVATE).use { out ->
                val quality = when (options.format) {
                    HandwritingFormat.PNG -> 100
                    HandwritingFormat.JPEG -> 95
                    HandwritingFormat.WEBP -> 100
                }
                bitmap.compress(options.format.compressFormat, quality, out)
            }
            HandwritingSide(filename, options)
        }.getOrElse {
            if (reuseExisting == null) {
                runCatching { deleteFile(filename) }
            }
            null
        }
        if (result != null) {
            HandwritingBitmapLoader.invalidate(result.path)
        }
        if (result != null && existing != null && reuseExisting == null) {
            if (existing.path != result.path) {
                deleteHandwritingFile(existing.path)
            }
        }
        return result
    }

    private fun loadHandwritingBitmap(path: String): Bitmap? =
        runCatching {
            openFileInput(path).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()

    private fun deleteHandwritingFile(path: String) {
        HandwritingBitmapLoader.invalidate(path)
        runCatching { deleteFile(path) }
    }

    private fun deleteHandwritingFiles(content: HandwritingContent) {
        deleteHandwritingFile(content.path)
        content.back?.path?.let { deleteHandwritingFile(it) }
    }

    private fun cardCanvasBounds(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        val density = metrics.density
        val horizontalInsetPx = (32 * density).roundToInt()
        val minSidePx = (320 * density).roundToInt()
        val availableWidth = (metrics.widthPixels - horizontalInsetPx).coerceAtLeast(minSidePx).coerceAtLeast(1)
        val maxHeight = (metrics.heightPixels * 2f / 3f).roundToInt().coerceAtLeast(1)
        return availableWidth to maxHeight
    }

    private fun computeCanvasSizeForRatio(ratio: Float, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        var width = maxWidth
        var height = (width * ratio).roundToInt().coerceAtLeast(1)
        if (height > maxHeight) {
            val scale = maxHeight.toFloat() / height
            width = (width * scale).roundToInt().coerceAtLeast(1)
            height = maxHeight
        }
        return width to height
    }

    private fun clampCanvasSize(width: Int, height: Int): Pair<Int, Int> {
        val (maxWidth, maxHeight) = cardCanvasBounds()
        var w = width.coerceAtLeast(1)
        var h = height.coerceAtLeast(1)
        val widthScale = maxWidth.toFloat() / w
        val heightScale = maxHeight.toFloat() / h
        val scale = min(1f, min(widthScale, heightScale))
        if (scale < 1f) {
            w = (w * scale).roundToInt().coerceAtLeast(1)
            h = (h * scale).roundToInt().coerceAtLeast(1)
        }
        return w to h
    }

    private fun synchronizeBackPaper(
        front: HandwritingOptions,
        back: HandwritingOptions?
    ): HandwritingOptions {
        val base = back ?: front
        return base.copy(
            backgroundColor = front.backgroundColor,
            paperStyle = front.paperStyle,
            canvasWidth = front.canvasWidth,
            canvasHeight = front.canvasHeight,
            format = front.format
        )
    }

    private fun defaultHandwritingOptions(): HandwritingOptions {
        val (maxWidth, maxHeight) = cardCanvasBounds()
        val (fallbackWidth, fallbackHeight) = computeCanvasSizeForRatio(DEFAULT_CANVAS_RATIO, maxWidth, maxHeight)
        val storedBackground = parseColorString(prefs.getString(KEY_HANDWRITING_DEFAULT_BACKGROUND, null))
        val storedBrush = parseColorString(prefs.getString(KEY_HANDWRITING_DEFAULT_BRUSH, null))
        val storedBrushSize = prefs.getFloat(KEY_HANDWRITING_DEFAULT_BRUSH_SIZE_DP, Float.NaN)
        val storedEraserSize = prefs.getFloat(KEY_HANDWRITING_DEFAULT_ERASER_SIZE_DP, Float.NaN)
        val storedWidth = prefs.getInt(KEY_HANDWRITING_DEFAULT_CANVAS_WIDTH, -1).takeIf { it > 0 }
        val storedHeight = prefs.getInt(KEY_HANDWRITING_DEFAULT_CANVAS_HEIGHT, -1).takeIf { it > 0 }
        val baseWidth = storedWidth ?: fallbackWidth
        val baseHeight = storedHeight ?: fallbackHeight
        val (canvasWidth, canvasHeight) = clampCanvasSize(baseWidth, baseHeight)
        val brushSize = if (storedBrushSize.isNaN()) DEFAULT_HANDWRITING_BRUSH_SIZE_DP
        else storedBrushSize.coerceIn(MIN_HANDWRITING_BRUSH_SIZE_DP, MAX_HANDWRITING_BRUSH_SIZE_DP)
        val eraserSize = if (storedEraserSize.isNaN()) {
            DEFAULT_HANDWRITING_ERASER_SIZE_DP
        } else {
            storedEraserSize.coerceIn(MIN_HANDWRITING_ERASER_SIZE_DP, MAX_HANDWRITING_ERASER_SIZE_DP)
        }
        val formatName = prefs.getString(KEY_HANDWRITING_DEFAULT_FORMAT, null)
        val paperStyleName = prefs.getString(KEY_HANDWRITING_DEFAULT_PAPER_STYLE, null)
        val penTypeName = prefs.getString(KEY_HANDWRITING_DEFAULT_PEN_TYPE, null)
        val eraserTypeName = prefs.getString(KEY_HANDWRITING_DEFAULT_ERASER_TYPE, null)
        return HandwritingOptions(
            backgroundColor = storedBackground ?: DEFAULT_HANDWRITING_BACKGROUND,
            brushColor = storedBrush ?: DEFAULT_HANDWRITING_BRUSH,
            brushSizeDp = brushSize,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            format = HandwritingFormat.fromName(formatName) ?: HandwritingFormat.PNG,
            paperStyle = HandwritingPaperStyle.fromName(paperStyleName) ?: DEFAULT_HANDWRITING_PAPER_STYLE,
            penType = HandwritingPenType.fromName(penTypeName) ?: DEFAULT_HANDWRITING_PEN_TYPE,
            eraserSizeDp = eraserSize,
            eraserType = HandwritingEraserType.fromName(eraserTypeName) ?: DEFAULT_HANDWRITING_ERASER_TYPE
        )
    }

    private fun persistHandwritingDefaults(
        options: HandwritingOptions,
        palette: HandwritingPaletteSection,
        drawingTool: HandwritingDrawingTool
    ) {
        val (width, height) = clampCanvasSize(options.canvasWidth, options.canvasHeight)
        prefs.edit().apply {
            putString(KEY_HANDWRITING_DEFAULT_BACKGROUND, colorToString(options.backgroundColor))
            putString(KEY_HANDWRITING_DEFAULT_BRUSH, colorToString(options.brushColor))
            putFloat(
                KEY_HANDWRITING_DEFAULT_BRUSH_SIZE_DP,
                options.brushSizeDp.coerceIn(MIN_HANDWRITING_BRUSH_SIZE_DP, MAX_HANDWRITING_BRUSH_SIZE_DP)
            )
            putFloat(
                KEY_HANDWRITING_DEFAULT_ERASER_SIZE_DP,
                options.eraserSizeDp.coerceIn(MIN_HANDWRITING_ERASER_SIZE_DP, MAX_HANDWRITING_ERASER_SIZE_DP)
            )
            putInt(KEY_HANDWRITING_DEFAULT_CANVAS_WIDTH, width)
            putInt(KEY_HANDWRITING_DEFAULT_CANVAS_HEIGHT, height)
            putString(KEY_HANDWRITING_DEFAULT_FORMAT, options.format.name)
            putString(KEY_HANDWRITING_DEFAULT_PAPER_STYLE, options.paperStyle.name)
            putString(KEY_HANDWRITING_DEFAULT_PEN_TYPE, options.penType.name)
            putString(KEY_HANDWRITING_DEFAULT_ERASER_TYPE, options.eraserType.name)
            putString(KEY_HANDWRITING_LAST_PALETTE_SECTION, palette.name)
            putString(KEY_HANDWRITING_LAST_DRAWING_TOOL, drawingTool.name)
        }.apply()
    }

    private fun loadHandwritingPaletteSection(): HandwritingPaletteSection =
        HandwritingPaletteSection.fromName(prefs.getString(KEY_HANDWRITING_LAST_PALETTE_SECTION, null))
            ?: HandwritingPaletteSection.PEN

    private fun loadHandwritingDrawingTool(): HandwritingDrawingTool =
        HandwritingDrawingTool.fromName(prefs.getString(KEY_HANDWRITING_LAST_DRAWING_TOOL, null))
            ?: HandwritingDrawingTool.PEN

    private fun parseHandwritingContent(cardObj: JSONObject): HandwritingContent? {
        val handwritingObj = cardObj.optJSONObject("handwriting")
        val baseOptions = defaultHandwritingOptions()
        if (handwritingObj != null) {
            val front = parseHandwritingSide(handwritingObj, baseOptions) ?: return null
            val back = handwritingObj.optJSONObject("back")?.let { parseHandwritingSide(it, baseOptions) }
            return HandwritingContent(
                path = front.path,
                options = front.options,
                back = back
            )
        }

        val legacyPath = cardObj.optString("handwritingPath", "").takeIf { it.isNotBlank() } ?: return null
        val extension = legacyPath.substringAfterLast('.', "")
        val format = HandwritingFormat.fromExtension(extension) ?: baseOptions.format
        val legacySize = sniffHandwritingDimensions(legacyPath)
        val (canvasWidth, canvasHeight) = legacySize?.let { clampCanvasSize(it.first, it.second) }
            ?: (baseOptions.canvasWidth to baseOptions.canvasHeight)
        return HandwritingContent(
            path = legacyPath,
            options = baseOptions.copy(
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                format = format
            )
        )
    }

    private fun parseHandwritingSide(
        obj: JSONObject,
        baseOptions: HandwritingOptions
    ): HandwritingSide? {
        val path = obj.optString("path")
        if (path.isNullOrBlank()) return null
        val optionsObj = obj.optJSONObject("options")
        val background = parseColorString(optionsObj?.optString("backgroundColor")) ?: baseOptions.backgroundColor
        val brushColor = parseColorString(optionsObj?.optString("brushColor")) ?: baseOptions.brushColor
        val brushSize = optionsObj?.optDouble("brushSizeDp", Double.NaN)?.takeIf { !it.isNaN() }?.toFloat()
            ?.coerceIn(MIN_HANDWRITING_BRUSH_SIZE_DP, MAX_HANDWRITING_BRUSH_SIZE_DP)
            ?: baseOptions.brushSizeDp
        val rawWidth = optionsObj?.optInt("canvasWidth", baseOptions.canvasWidth) ?: baseOptions.canvasWidth
        val rawHeight = optionsObj?.optInt("canvasHeight", baseOptions.canvasHeight) ?: baseOptions.canvasHeight
        val (canvasWidth, canvasHeight) = clampCanvasSize(rawWidth, rawHeight)
        val formatName = optionsObj?.optString("format")
        val format = HandwritingFormat.fromName(formatName) ?: baseOptions.format
        val paperStyleName = optionsObj?.optString("paperStyle")
        val paperStyle = HandwritingPaperStyle.fromName(paperStyleName) ?: baseOptions.paperStyle
        val penTypeName = optionsObj?.optString("penType")
        val penType = HandwritingPenType.fromName(penTypeName) ?: baseOptions.penType
        val eraserSize = optionsObj?.optDouble("eraserSizeDp", Double.NaN)?.takeIf { !it.isNaN() }?.toFloat()
            ?.coerceIn(MIN_HANDWRITING_ERASER_SIZE_DP, MAX_HANDWRITING_ERASER_SIZE_DP)
            ?: baseOptions.eraserSizeDp
        val eraserTypeName = optionsObj?.optString("eraserType")
        val eraserType = HandwritingEraserType.fromName(eraserTypeName) ?: baseOptions.eraserType
        return HandwritingSide(
            path = path,
            options = HandwritingOptions(
                backgroundColor = background,
                brushColor = brushColor,
                brushSizeDp = brushSize,
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                format = format,
                paperStyle = paperStyle,
                penType = penType,
                eraserSizeDp = eraserSize,
                eraserType = eraserType
            )
        )
    }

    private fun parseColorString(value: String?): Int? = try {
        if (value.isNullOrBlank()) null else Color.parseColor(value)
    } catch (_: IllegalArgumentException) { null }

    private fun sniffHandwritingDimensions(path: String): Pair<Int, Int>? =
        runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openFileInput(path).use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
        }.getOrNull()

    private fun colorToString(color: Int): String = String.format("#%08X", color)

    private fun isPhotoPickerAvailable(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) true
        else ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)

    private fun snackbar(msg: String) {
        val snack = Snackbar.make(findViewById(R.id.content), msg, Snackbar.LENGTH_SHORT)
        if (flowBar.isVisible) snack.anchorView = flowBar
        snack.show()
    }

    private fun buildExportFileName(): String {
        val formatter = SimpleDateFormat(EXPORT_FILE_DATE_PATTERN, Locale.US)
        val timestamp = formatter.format(Date())
        return "timescape_notes_${'$'}timestamp.json"
    }

    private fun exportNotes(uri: Uri) {
        captureVisibleFlowStates()
        lifecycleScope.launch {
            val payload = withContext(Dispatchers.IO) {
                runCatching {
                    val exportPayload = buildNotesExportPayload()
                    contentResolver.openOutputStream(uri, "w")?.use { stream ->
                        OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                            writer.write(exportPayload.json)
                        }
                    } ?: error("Unable to open output stream")
                    exportPayload
                }
            }.getOrElse {
                snackbar(getString(R.string.snackbar_export_failed))
                return@launch
            }
            val notesText = resources.getQuantityString(R.plurals.count_notes, payload.cardCount, payload.cardCount)
            val flowsText = resources.getQuantityString(R.plurals.count_flows, payload.flowCount, payload.flowCount)
            snackbar(getString(R.string.snackbar_export_success, notesText, flowsText))
        }
    }

    private fun buildNotesExportPayload(): ExportPayload {
        val root = JSONObject()
        root.put("version", NOTES_EXPORT_VERSION)
        root.put("generatedAt", System.currentTimeMillis())
        val flowsArray = JSONArray()
        var cardCount = 0
        flows.forEach { flow ->
            val flowObj = JSONObject()
            flowObj.put("name", flow.name)
            val cardsArray = JSONArray()
            val shuffleState = flowShuffleStates[flow.id]?.also { it.syncWith(flow) }
            val cardsForExport = shuffleState?.let { cardsInOriginalOrder(flow, it) } ?: flow.cards
            cardsForExport.forEach { card ->
                val cardObj = JSONObject()
                cardObj.put("title", card.title)
                cardObj.put("snippet", card.snippet)
                cardObj.put("updatedAt", card.updatedAt)
                card.handwriting?.let { handwriting ->
                    handwritingToJson(handwriting)?.let { cardObj.put("handwriting", it) }
                }
                cardsArray.put(cardObj)
                cardCount++
            }
            flowObj.put("cards", cardsArray)
            flowsArray.put(flowObj)
        }
        root.put("flows", flowsArray)
        return ExportPayload(root.toString(2), flows.size, cardCount)
    }

    private fun handwritingOptionsToJson(options: HandwritingOptions): JSONObject = JSONObject().apply {
        put("backgroundColor", colorToString(options.backgroundColor))
        put("brushColor", colorToString(options.brushColor))
        put("brushSizeDp", options.brushSizeDp.toDouble())
        put("canvasWidth", options.canvasWidth)
        put("canvasHeight", options.canvasHeight)
        put("format", options.format.name)
        put("paperStyle", options.paperStyle.name)
        put("penType", options.penType.name)
        put("eraserSizeDp", options.eraserSizeDp.toDouble())
        put("eraserType", options.eraserType.name)
    }

    private fun handwritingToJson(content: HandwritingContent): JSONObject? {
        val bytes = readHandwritingBytes(content.path) ?: return null
        val optionsObj = handwritingOptionsToJson(content.options)
        val root = JSONObject().apply {
            put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("options", optionsObj)
        }
        content.back?.let { back ->
            val backBytes = readHandwritingBytes(back.path)
            if (backBytes != null) {
                val backOptions = handwritingOptionsToJson(back.options)
                val backObj = JSONObject().apply {
                    put("data", Base64.encodeToString(backBytes, Base64.NO_WRAP))
                    put("options", backOptions)
                }
                root.put("back", backObj)
            }
        }
        return root
    }

    private fun readHandwritingBytes(path: String): ByteArray? =
        runCatching { openFileInput(path).use { it.readBytes() } }.getOrNull()

    private fun importNotes(uri: Uri) {
        lifecycleScope.launch {
            val payload = withContext(Dispatchers.IO) {
                runCatching { readImportPayload(uri) }
            }.getOrElse {
                snackbar(getString(R.string.snackbar_import_failed))
                return@launch
            }
            if (payload.flows.isEmpty()) {
                snackbar(getString(R.string.snackbar_import_empty))
                return@launch
            }
            val baseIndex = flows.size
            var insertedCards = 0
            payload.flows.forEachIndexed { index, importedFlow ->
                val flowId = nextFlowId++
                val flowName = importedFlow.name.takeIf { it.isNotBlank() }
                    ?: defaultFlowName(baseIndex + index)
                val newFlow = CardFlow(id = flowId, name = flowName)
                importedFlow.cards.forEach { importedCard ->
                    val cardId = nextCardId++
                    val card = CardItem(
                        id = cardId,
                        title = importedCard.title,
                        snippet = importedCard.snippet,
                        updatedAt = importedCard.updatedAt,
                        handwriting = importedCard.handwriting
                    )
                    newFlow.cards += card
                }
                insertedCards += newFlow.cards.size
                newFlow.lastViewedCardIndex = 0
                newFlow.lastViewedCardId = newFlow.cards.firstOrNull()?.id
                newFlow.lastViewedCardFocused = false
                prepareFlowCards(newFlow)
                flows += newFlow
            }
            val insertedFlows = payload.flows.size
            flowAdapter.notifyItemRangeInserted(baseIndex, insertedFlows)
            renderFlowChips(selectedFlowIndex.coerceIn(0, flows.lastIndex))
            updateToolbarSubtitle()
            updateShuffleMenuState()
            saveState()
            val notesText = resources.getQuantityString(R.plurals.count_notes, insertedCards, insertedCards)
            val flowsText = resources.getQuantityString(R.plurals.count_flows, insertedFlows, insertedFlows)
            snackbar(getString(R.string.snackbar_import_success, notesText, flowsText))
        }
    }

    private fun readImportPayload(uri: Uri): ImportPayload {
        val createdFiles = mutableListOf<String>()
        return try {
            val jsonText = contentResolver.openInputStream(uri)?.bufferedReader(StandardCharsets.UTF_8)?.use { reader ->
                reader.readText()
            } ?: throw IllegalStateException("Unable to open input stream")
            val root = JSONObject(jsonText)
            val flowsArray = root.optJSONArray("flows") ?: JSONArray()
            val importedFlows = mutableListOf<ImportedFlow>()
            var totalCards = 0
            for (i in 0 until flowsArray.length()) {
                val flowObj = flowsArray.optJSONObject(i) ?: continue
                val flowName = flowObj.optString("name")
                val cardsArray = flowObj.optJSONArray("cards") ?: JSONArray()
                val cards = mutableListOf<ImportedCard>()
                for (j in 0 until cardsArray.length()) {
                    val cardObj = cardsArray.optJSONObject(j) ?: continue
                    val title = cardObj.optString("title")
                    val snippet = cardObj.optString("snippet")
                    val updatedAt = cardObj.optLong("updatedAt", System.currentTimeMillis())
                    val handwriting = decodeHandwritingFromExport(cardObj.optJSONObject("handwriting"), createdFiles)
                    cards += ImportedCard(title, snippet, updatedAt, handwriting)
                }
                totalCards += cards.size
                importedFlows += ImportedFlow(flowName, cards)
            }
            ImportPayload(importedFlows, totalCards)
        } catch (e: Exception) {
            createdFiles.forEach { deleteHandwritingFile(it) }
            throw e
        }
    }

    private fun decodeHandwritingFromExport(
        handwritingObj: JSONObject?,
        createdFiles: MutableList<String>
    ): HandwritingContent? {
        if (handwritingObj == null) return null
        val front = decodeHandwritingSideFromExport(handwritingObj, createdFiles) ?: return null
        val back = handwritingObj.optJSONObject("back")?.let { decodeHandwritingSideFromExport(it, createdFiles) }
        return HandwritingContent(front.path, front.options, back)
    }

    private fun decodeHandwritingSideFromExport(
        sideObj: JSONObject,
        createdFiles: MutableList<String>
    ): HandwritingSide? {
        val data = sideObj.optString("data").takeIf { it.isNotBlank() } ?: return null
        val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull() ?: return null
        val options = parseHandwritingOptionsFromExport(sideObj.optJSONObject("options")) ?: return null
        val filename = "handwriting_${'$'}{System.currentTimeMillis()}_${'$'}{UUID.randomUUID()}.${'$'}{options.format.extension}"
        return runCatching {
            openFileOutput(filename, MODE_PRIVATE).use { it.write(bytes) }
            createdFiles += filename
            HandwritingSide(filename, options)
        }.getOrElse {
            createdFiles.remove(filename)
            null
        }
    }

    private fun parseHandwritingOptionsFromExport(optionsObj: JSONObject?): HandwritingOptions? {
        val defaults = defaultHandwritingOptions()
        if (optionsObj == null) return defaults
        val background = parseColorString(optionsObj.optString("backgroundColor")) ?: defaults.backgroundColor
        val brush = parseColorString(optionsObj.optString("brushColor")) ?: defaults.brushColor
        val brushSize = optionsObj.optDouble("brushSizeDp", defaults.brushSizeDp.toDouble()).toFloat()
        val eraserSize = optionsObj.optDouble("eraserSizeDp", defaults.eraserSizeDp.toDouble()).toFloat()
        val width = optionsObj.optInt("canvasWidth", defaults.canvasWidth)
        val height = optionsObj.optInt("canvasHeight", defaults.canvasHeight)
        val (clampedWidth, clampedHeight) = clampCanvasSize(width, height)
        val format = HandwritingFormat.fromName(optionsObj.optString("format")) ?: defaults.format
        val paperStyle = HandwritingPaperStyle.fromName(optionsObj.optString("paperStyle")) ?: defaults.paperStyle
        val penType = HandwritingPenType.fromName(optionsObj.optString("penType")) ?: defaults.penType
        val eraserType = HandwritingEraserType.fromName(optionsObj.optString("eraserType")) ?: defaults.eraserType
        return HandwritingOptions(
            backgroundColor = background,
            brushColor = brush,
            brushSizeDp = brushSize.coerceIn(MIN_HANDWRITING_BRUSH_SIZE_DP, MAX_HANDWRITING_BRUSH_SIZE_DP),
            canvasWidth = clampedWidth,
            canvasHeight = clampedHeight,
            format = format,
            paperStyle = paperStyle,
            penType = penType,
            eraserSizeDp = eraserSize.coerceIn(MIN_HANDWRITING_ERASER_SIZE_DP, MAX_HANDWRITING_ERASER_SIZE_DP),
            eraserType = eraserType
        )
    }

    private fun updateCardFontSize(value: Float, fromUser: Boolean) {
        val clamped = value.coerceIn(cardFontSizeSlider.valueFrom, cardFontSizeSlider.valueTo)
        val changed = abs(cardFontSizeSp - clamped) >= 0.01f
        cardFontSizeSp = clamped
        updateCardFontSizeLabel()
        if (changed) {
            applyCardFontSizeToAdapters()
            prefs.edit().putFloat(KEY_CARD_FONT_SIZE, cardFontSizeSp).apply()
        }
        if (!fromUser && cardFontSizeSlider.value != clamped) {
            cardFontSizeSlider.value = clamped
        }
    }

    private fun updateCardFontSizeLabel() {
        if (::cardFontSizeValue.isInitialized) {
            cardFontSizeValue.text = getString(R.string.drawer_card_font_size_value, cardFontSizeSp)
        }
    }

    private fun applyCardFontSizeToAdapters() {
        flowControllers.forEach { (_, controller) ->
            controller.adapter.setBodyTextSize(cardFontSizeSp)
        }
    }

    private fun loadState() {
        flows.clear()
        selectedImages.clear()
        flowShuffleStates.clear()

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
                            val handwriting = parseHandwritingContent(cardObj)
                            flow.cards += CardItem(
                                id = cardId,
                                title = cardObj.optString("title"),
                                snippet = cardObj.optString("snippet"),
                                updatedAt = cardObj.optLong("updatedAt", System.currentTimeMillis()),
                                handwriting = handwriting
                            )
                        }
                    }
                    val hasLastId = obj.has("lastViewedCardId") && !obj.isNull("lastViewedCardId")
                    flow.lastViewedCardId = if (hasLastId) {
                        obj.optLong("lastViewedCardId", -1L).takeIf { it >= 0 }
                    } else null
                    val savedIndex = obj.optInt("lastViewedCardIndex", 0)
                    flow.lastViewedCardIndex = savedIndex.coerceIn(0, max(0, flow.cards.lastIndex))
                    flow.lastViewedCardFocused = obj.optBoolean("lastViewedCardFocused", false)
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
                            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                            handwriting = null
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

        val appBackgroundJson = prefs.getString(KEY_APP_BACKGROUND, null)
        appBackground = if (!appBackgroundJson.isNullOrBlank()) {
            try {
                val obj = JSONObject(appBackgroundJson)
                when (obj.optString("type")) {
                    "res" -> {
                        val id = obj.optInt("id")
                        val resolvedId = if (id != 0) id else {
                            val name = obj.optString("name")
                            if (name.isNullOrBlank()) 0 else resources.getIdentifier(name, "drawable", packageName)
                        }
                        if (resolvedId != 0) BgImage.Res(resolvedId) else null
                    }
                    "uri" -> {
                        val value = obj.optString("value")
                        if (!value.isNullOrBlank()) BgImage.UriRef(Uri.parse(value)) else null
                    }
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        } else null

        selectedFlowIndex = if (flows.isEmpty()) {
            0
        } else {
            prefs.getInt(KEY_SELECTED_FLOW_INDEX, 0).coerceIn(0, flows.lastIndex)
        }
    }

    private fun saveState() {
        captureVisibleFlowStates()
        val flowsArray = JSONArray()
        flows.forEach { flow ->
            val flowObj = JSONObject()
            flowObj.put("id", flow.id)
            flowObj.put("name", flow.name)
            val cardsArray = JSONArray()
            val shuffleState = flowShuffleStates[flow.id]?.also { it.syncWith(flow) }
            val cardsForPersistence = shuffleState?.let { cardsInOriginalOrder(flow, it) } ?: flow.cards
            cardsForPersistence.forEach { card ->
                val obj = JSONObject()
                obj.put("id", card.id)
                obj.put("title", card.title)
                obj.put("snippet", card.snippet)
                obj.put("updatedAt", card.updatedAt)
                card.handwriting?.let { content ->
                    val handwritingObj = JSONObject().apply {
                        put("path", content.path)
                        put("options", handwritingOptionsToJson(content.options))
                        content.back?.let { backSide ->
                            put("back", JSONObject().apply {
                                put("path", backSide.path)
                                put("options", handwritingOptionsToJson(backSide.options))
                            })
                        }
                    }
                    obj.put("handwriting", handwritingObj)
                    obj.put("handwritingPath", content.path)
                    content.back?.path?.let { obj.put("handwritingBackPath", it) }
                }
                cardsArray.put(obj)
            }
            flowObj.put("cards", cardsArray)
            if (flow.lastViewedCardId != null) {
                flowObj.put("lastViewedCardId", flow.lastViewedCardId)
            } else {
                flowObj.put("lastViewedCardId", JSONObject.NULL)
            }
            flowObj.put("lastViewedCardIndex", flow.lastViewedCardIndex)
            flowObj.put("lastViewedCardFocused", flow.lastViewedCardFocused)
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

        val appBackgroundObj = JSONObject()
        when (val bg = appBackground) {
            is BgImage.Res -> {
                appBackgroundObj.put("type", "res")
                appBackgroundObj.put("id", bg.id)
                runCatching { resources.getResourceEntryName(bg.id) }
                    .getOrNull()?.let { appBackgroundObj.put("name", it) }
            }
            is BgImage.UriRef -> {
                appBackgroundObj.put("type", "uri")
                appBackgroundObj.put("value", bg.uri.toString())
            }
            null -> {
                // no-op: app background is cleared
            }
        }

        with(prefs.edit()) {
            putString(KEY_FLOWS, flowsArray.toString())
            putString(KEY_IMAGES, imagesArray.toString())
            if (appBackground != null) putString(KEY_APP_BACKGROUND, appBackgroundObj.toString())
            else remove(KEY_APP_BACKGROUND)
            putLong(KEY_NEXT_CARD_ID, nextCardId)
            putLong(KEY_NEXT_FLOW_ID, nextFlowId)
            val currentIndex = if (flows.isEmpty()) 0 else flowPager.currentItem.coerceIn(0, flows.lastIndex)
            putInt(KEY_SELECTED_FLOW_INDEX, currentIndex)
            putFloat(KEY_CARD_FONT_SIZE, cardFontSizeSp)
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
            adapter.setBodyTextSize(cardFontSizeSp)
            recycler.adapter = adapter
            holder = FlowVH(view, recycler, layoutManager, adapter)
            return holder
        }

        override fun onBindViewHolder(holder: FlowVH, position: Int) {
            val flow = flows[position]
            holder.bind(flow)
        }

        override fun getItemCount(): Int = flows.size

        override fun onViewRecycled(holder: FlowVH) {
            holder.boundFlowId?.let {
                persistControllerState(it)
                flowControllers.remove(it)?.dispose()
            }
            super.onViewRecycled(holder)
        }

        inner class FlowVH(
            view: View,
            val recycler: RecyclerView,
            val layoutManager: RightRailFlowLayoutManager,
            val adapter: CardsAdapter
        ) : RecyclerView.ViewHolder(view) {
            var boundFlowId: Long? = null

            fun bind(flow: CardFlow) {
                boundFlowId?.let {
                    persistControllerState(it)
                    flowControllers.remove(it)?.dispose()
                }
                boundFlowId = flow.id
                val controller = FlowPageController(flow.id, recycler, layoutManager, adapter)
                flowControllers[flow.id] = controller
                adapter.setBodyTextSize(cardFontSizeSp)
                controller.updateDisplayedCards(
                    flow = flow,
                    query = searchQueryNormalized,
                    shouldRestoreState = searchQueryNormalized.isEmpty(),
                    shouldScrollToTop = searchQueryNormalized.isNotEmpty()
                )
            }

            fun onCardTapped(index: Int) {
                if (bindingAdapterPosition == RecyclerView.NO_POSITION) return
                val card = adapter.getItemAt(index) ?: return
                if (layoutManager.isFocused(index)) {
                    val handwritingContent = card.handwriting
                    if (handwritingContent != null) {
                        val vh = recycler.findViewHolderForAdapterPosition(index) as? CardsAdapter.VH
                        if (vh != null && adapter.toggleHandwriting(vh) != null) {
                            return
                        }
                    }
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
                val face = adapter.currentHandwritingFaceFor(index)
                editCard(flow, index, face)
            }
        }
    }

    private inner class FlowPageController(
        val flowId: Long,
        val recycler: RecyclerView,
        val layoutManager: RightRailFlowLayoutManager,
        val adapter: CardsAdapter
    ) {
        private var activeQuery: String = ""
        private val scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    owningFlow()?.let { ensureMainCard(it) }
                }
            }
        }

        init {
            recycler.addOnScrollListener(scrollListener)
        }

        fun updateDisplayedCards(
            flow: CardFlow,
            query: String,
            shouldRestoreState: Boolean,
            shouldScrollToTop: Boolean
        ) {
            activeQuery = query
            val displayed = filterCardsForSearch(flow.cards, query)
            adapter.submitList(displayed) {
                handleListCommitted(flow, displayed, shouldRestoreState, shouldScrollToTop)
            }
        }

        fun restoreState(flow: CardFlow) {
            if (activeQuery.isNotBlank()) {
                layoutManager.clearFocus()
                layoutManager.restoreState(0, false)
                return
            }
            if (adapter.itemCount == 0) {
                layoutManager.restoreState(0, false)
                flow.lastViewedCardIndex = 0
                flow.lastViewedCardId = null
                flow.lastViewedCardFocused = false
                return
            }
            val indexById = flow.lastViewedCardId?.let { id ->
                flow.cards.indexOfFirst { it.id == id }.takeIf { it >= 0 }
            }
            val resolvedIndex = indexById ?: flow.lastViewedCardIndex
            val clampedIndex = resolvedIndex.coerceIn(0, adapter.itemCount - 1)
            val cardId = adapter.getItemAt(clampedIndex)?.id
            flow.lastViewedCardIndex = clampedIndex
            flow.lastViewedCardId = cardId
            val shouldFocus = flow.lastViewedCardFocused && indexById != null
            layoutManager.restoreState(clampedIndex, shouldFocus)
            flow.lastViewedCardFocused = shouldFocus
        }

        fun captureState(flow: CardFlow) {
            if (adapter.itemCount == 0) {
                flow.lastViewedCardIndex = 0
                flow.lastViewedCardId = null
                flow.lastViewedCardFocused = false
                return
            }
            val selectedIndex = layoutManager.currentSelectionIndex()
            val nearest = layoutManager.nearestIndex().coerceIn(0, adapter.itemCount - 1)
            val resolved = selectedIndex?.coerceIn(0, adapter.itemCount - 1) ?: nearest
            flow.lastViewedCardIndex = resolved
            flow.lastViewedCardId = adapter.getItemAt(resolved)?.id
            flow.lastViewedCardFocused = layoutManager.hasSelection()
        }

        fun dispose() {
            recycler.removeOnScrollListener(scrollListener)
        }

        private fun handleListCommitted(
            flow: CardFlow,
            displayed: List<CardItem>,
            shouldRestoreState: Boolean,
            shouldScrollToTop: Boolean
        ) {
            if (displayed.isEmpty()) {
                layoutManager.clearFocus()
                layoutManager.restoreState(0, false)
                return
            }
            if (activeQuery.isBlank()) {
                when {
                    shouldRestoreState -> restoreState(flow)
                    shouldScrollToTop -> {
                        layoutManager.clearFocus()
                        layoutManager.restoreState(0, false)
                    }
                }
            } else {
                layoutManager.clearFocus()
                if (shouldScrollToTop) {
                    layoutManager.restoreState(0, false)
                }
            }
            ensureMainCard(flow)
        }

        private fun ensureMainCard(flow: CardFlow) {
            if (adapter.itemCount == 0) return
            val selection = layoutManager.currentSelectionIndex()?.coerceIn(0, adapter.itemCount - 1)
            val target = selection ?: layoutManager.nearestIndex().coerceIn(0, adapter.itemCount - 1)
            layoutManager.focus(target)
            captureState(flow)
        }

        private fun owningFlow(): CardFlow? = flows.firstOrNull { it.id == flowId }
    }
    }

    private data class FlowShuffleState(val originalOrder: MutableList<Long>) {
        fun syncWith(flow: CardFlow) {
            val currentIds = flow.cards.map { it.id }
            val currentSet = currentIds.toMutableSet()
            originalOrder.retainAll(currentSet)
            currentIds.forEach { id ->
                if (!originalOrder.contains(id)) originalOrder.add(id)
            }
        }
    }

    private data class ExportPayload(val json: String, val flowCount: Int, val cardCount: Int)

    private data class ImportPayload(val flows: List<ImportedFlow>, val cardCount: Int)

    private data class ImportedFlow(val name: String, val cards: List<ImportedCard>)

    private data class ImportedCard(
        val title: String,
        val snippet: String,
        val updatedAt: Long,
        val handwriting: HandwritingContent?
    )

    companion object {
        private const val PREFS_NAME = "timescape_state"
        private const val KEY_CARDS = "cards"
        private const val KEY_IMAGES = "images"
        private const val KEY_FLOWS = "flows"
        private const val KEY_SELECTED_FLOW_INDEX = "selected_flow_index"
        private const val KEY_APP_BACKGROUND = "app_background"
        private const val KEY_NEXT_CARD_ID = "next_card_id"
        private const val KEY_NEXT_FLOW_ID = "next_flow_id"
        private const val KEY_CARD_FONT_SIZE = "card_font_size_sp"
        private const val KEY_HANDWRITING_DEFAULT_BACKGROUND = "handwriting/default_background"
        private const val KEY_HANDWRITING_DEFAULT_BRUSH = "handwriting/default_brush"
        private const val KEY_HANDWRITING_DEFAULT_BRUSH_SIZE_DP = "handwriting/default_brush_size_dp"
        private const val KEY_HANDWRITING_DEFAULT_ERASER_SIZE_DP = "handwriting/default_eraser_size_dp"
        private const val KEY_HANDWRITING_DEFAULT_CANVAS_WIDTH = "handwriting/default_canvas_width"
        private const val KEY_HANDWRITING_DEFAULT_CANVAS_HEIGHT = "handwriting/default_canvas_height"
        private const val KEY_HANDWRITING_DEFAULT_FORMAT = "handwriting/default_format"
        private const val KEY_HANDWRITING_DEFAULT_PAPER_STYLE = "handwriting/default_paper_style"
        private const val KEY_HANDWRITING_DEFAULT_PEN_TYPE = "handwriting/default_pen_type"
        private const val KEY_HANDWRITING_DEFAULT_ERASER_TYPE = "handwriting/default_eraser_type"
        private const val KEY_HANDWRITING_LAST_PALETTE_SECTION = "handwriting/last_palette_section"
        private const val KEY_HANDWRITING_LAST_DRAWING_TOOL = "handwriting/last_drawing_tool"
        private const val DEFAULT_CARD_FONT_SIZE_SP = 18f
        private const val MIN_HANDWRITING_BRUSH_SIZE_DP = 0.75f
        private const val MAX_HANDWRITING_BRUSH_SIZE_DP = 12f
        private const val DEFAULT_HANDWRITING_BRUSH_SIZE_DP = 3.5f
        private const val MIN_HANDWRITING_ERASER_SIZE_DP = 4f
        private const val MAX_HANDWRITING_ERASER_SIZE_DP = 48f
        private const val DEFAULT_HANDWRITING_ERASER_SIZE_DP = 16f
        private const val DEFAULT_CANVAS_RATIO = 0.75f
        private const val DEFAULT_HANDWRITING_BACKGROUND = -0x1
        private const val DEFAULT_HANDWRITING_BRUSH = -0x1000000
        private val DEFAULT_HANDWRITING_PAPER_STYLE = HandwritingPaperStyle.PLAIN
        private val DEFAULT_HANDWRITING_PEN_TYPE = HandwritingPenType.ROUND
        private val DEFAULT_HANDWRITING_ERASER_TYPE = HandwritingEraserType.ROUND
        private const val EXPORT_FILE_DATE_PATTERN = "yyyyMMdd_HHmmss"
        private const val NOTES_EXPORT_VERSION = 1
    }
}
