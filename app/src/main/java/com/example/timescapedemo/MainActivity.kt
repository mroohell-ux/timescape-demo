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
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import androidx.core.graphics.applyCanvas
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.CompositePageTransformer
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.slider.Slider
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var rootLayout: CoordinatorLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var flowPager: ViewPager2
    private lateinit var flowBar: View
    private lateinit var flowChipGroup: ChipGroup
    private lateinit var flowChipScroll: HorizontalScrollView

    private lateinit var drawerRecyclerImages: RecyclerView
    private lateinit var drawerAddImagesButton: MaterialButton
    private lateinit var drawerClearImagesButton: MaterialButton
    private lateinit var drawerPickAppBackgroundButton: MaterialButton
    private lateinit var drawerResetAppBackgroundButton: MaterialButton
    private lateinit var appBackgroundPreview: ImageView
    private lateinit var cardFontSizeSlider: Slider
    private lateinit var cardFontSizeValue: TextView

    private lateinit var imagesAdapter: SelectedImagesAdapter
    private lateinit var flowAdapter: FlowPagerAdapter

    private val selectedImages: MutableList<BgImage> = mutableListOf()
    private val flows: MutableList<CardFlow> = mutableListOf()
    private val flowControllers: MutableMap<Long, FlowPageController> = mutableMapOf()

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
            }
        })
    }

    private fun setupToolbarActions() {
        toolbar.menu.clear()
        menuInflater.inflate(R.menu.menu_main, toolbar.menu)
        toolbar.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                R.id.action_add_card -> { showAddCardDialog(); true }
                R.id.action_add_handwriting -> { showAddHandwritingDialog(); true }
                R.id.action_add_flow -> { showAddFlowDialog(); true }
                else -> false
            }
        }
    }

    private fun updateToolbarSubtitle() {
        toolbar.subtitle = ""
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
        removed.cards.forEach { card ->
            card.handwriting?.path?.let { deleteHandwritingFile(it) }
        }
        flowControllers.remove(removed.id)
        flowAdapter.notifyItemRemoved(index)
        val remainingLastIndex = flows.lastIndex
        if (remainingLastIndex < 0) {
            selectedFlowIndex = 0
            renderFlowChips(0)
            updateToolbarSubtitle()
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
        flow.cards.sortByDescending { it.updatedAt }
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

    private fun refreshFlow(flow: CardFlow, scrollToTop: Boolean = false) {
        prepareFlowCards(flow)
        val controller = flowControllers[flow.id]
        if (scrollToTop) {
            flow.lastViewedCardIndex = 0
            flow.lastViewedCardId = flow.cards.firstOrNull()?.id
            flow.lastViewedCardFocused = false
        }
        if (controller != null) {
            controller.adapter.submitList(flow.cards.toList())
            controller.restoreState(flow)
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
            onSave = { savedContent ->
                val card = CardItem(
                    id = nextCardId++,
                    title = "",
                    snippet = "",
                    handwriting = savedContent,
                    updatedAt = System.currentTimeMillis()
                )
                flow.cards += card
                refreshFlow(flow, scrollToTop = true)
                saveState()
                snackbar(getString(R.string.snackbar_added_handwriting))
            }
        )
    }

    private fun editCard(flow: CardFlow, index: Int) {
        val card = flow.cards.getOrNull(index) ?: return
        val handwritingContent = card.handwriting
        if (handwritingContent != null) {
            showHandwritingDialog(
                titleRes = R.string.dialog_edit_handwriting_title,
                existing = handwritingContent,
                initialOptions = handwritingContent.options,
                onSave = { savedContent ->
                    card.handwriting = savedContent
                    card.updatedAt = System.currentTimeMillis()
                    refreshFlow(flow, scrollToTop = false)
                    saveState()
                    snackbar("Card updated")
                },
                onDelete = {
                    card.handwriting?.path?.let { deleteHandwritingFile(it) }
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
                card.handwriting?.path?.let { deleteHandwritingFile(it) }
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
        existing: HandwritingContent?,
        initialOptions: HandwritingOptions,
        onSave: (HandwritingContent) -> Unit,
        onDelete: (() -> Unit)? = null
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_handwriting, null, false)
        val handwritingView = dialogView.findViewById<HandwritingView>(R.id.handwritingView)
        val undoButton = dialogView.findViewById<MaterialButton>(R.id.buttonUndoHandwriting)
        val clearButton = dialogView.findViewById<MaterialButton>(R.id.buttonClearHandwriting)
        val toolToggle = dialogView.findViewById<MaterialButtonToggleGroup>(R.id.toggleHandwritingTools)
        val penButton = dialogView.findViewById<MaterialButton>(R.id.buttonPenOptions)
        val paperButton = dialogView.findViewById<MaterialButton>(R.id.buttonPaperOptions)
        val penOptionsContainer = dialogView.findViewById<ViewGroup>(R.id.containerPenOptions)
        val paperOptionsContainer = dialogView.findViewById<ViewGroup>(R.id.containerPaperOptions)
        val brushColorGroup = dialogView.findViewById<ChipGroup>(R.id.groupBrushColors)
        val penTypeGroup = dialogView.findViewById<ChipGroup>(R.id.groupPenTypes)
        val brushSizeValue = dialogView.findViewById<TextView>(R.id.textBrushSizeValue)
        val brushSizeSlider = dialogView.findViewById<Slider>(R.id.sliderBrushSize)
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

        val paperColorOptions = listOf(
            NamedColor(Color.WHITE, getString(R.string.handwriting_color_white)),
            NamedColor(Color.parseColor("#FFF4E0"), getString(R.string.handwriting_color_cream)),
            NamedColor(Color.parseColor("#101820"), getString(R.string.handwriting_color_midnight)),
            NamedColor(Color.parseColor("#E3F2FD"), getString(R.string.handwriting_color_sky))
        )
        var selectedPaperColor = paperColorOptions.firstOrNull { it.color == initialOptions.backgroundColor }
            ?: paperColorOptions.first()

        val brushColorOptions = listOf(
            NamedColor(Color.BLACK, getString(R.string.handwriting_color_black)),
            NamedColor(Color.parseColor("#1565C0"), getString(R.string.handwriting_color_blue)),
            NamedColor(Color.parseColor("#D32F2F"), getString(R.string.handwriting_color_red)),
            NamedColor(Color.parseColor("#2E7D32"), getString(R.string.handwriting_color_green))
        )
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
            HandwritingPenType.CALLIGRAPHY to getString(R.string.handwriting_pen_type_calligraphy)
        )
        var selectedPenType = initialOptions.penType.takeIf { pen -> penTypeOptions.any { it.first == pen } }
            ?: HandwritingPenType.ROUND

        brushSizeSlider.valueFrom = MIN_HANDWRITING_BRUSH_SIZE_DP
        brushSizeSlider.valueTo = MAX_HANDWRITING_BRUSH_SIZE_DP
        brushSizeSlider.stepSize = 0.5f

        val minBrush = brushSizeSlider.valueFrom
        val maxBrush = brushSizeSlider.valueTo
        var selectedBrushSize = initialOptions.brushSizeDp.coerceIn(minBrush, maxBrush)

        fun updateBrushSizeLabel() {
            brushSizeValue.text = getString(R.string.handwriting_brush_size_value, selectedBrushSize)
        }

        fun createChoiceChip(text: String, iconRes: Int? = null, iconTint: Int? = null): Chip {
            val themedContext = ContextThemeWrapper(this, R.style.Widget_MaterialComponents_Chip_Choice)
            return Chip(themedContext).apply {
                id = View.generateViewId()
                this.text = text
                isCheckable = true
                isClickable = true
                isFocusable = true
                isCheckedIconVisible = true
                if (iconRes != null) {
                    setChipIconResource(iconRes)
                    isChipIconVisible = true
                    iconTint?.let { tintColor -> chipIconTint = ColorStateList.valueOf(tintColor) }
                }
            }
        }

        brushColorGroup.removeAllViews()
        brushColorOptions.forEach { option ->
            val chip = createChoiceChip(option.label, R.drawable.ic_handwriting_color, option.color).apply {
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
            val chip = createChoiceChip(option.label, R.drawable.ic_handwriting_color, option.color).apply {
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

        handwritingView.setCanvasSize(selectedSize.width, selectedSize.height)
        handwritingView.setCanvasBackgroundColor(selectedPaperColor.color)
        handwritingView.setPaperStyle(selectedPaperStyle)
        handwritingView.setBrushColor(selectedBrushColor.color)
        handwritingView.setBrushSizeDp(selectedBrushSize)
        handwritingView.setPenType(selectedPenType)
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

        fun showTool(buttonId: Int) {
            val showingPen = buttonId == penButton.id
            penOptionsContainer.isVisible = showingPen
            paperOptionsContainer.isVisible = !showingPen
        }

        toolToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) showTool(checkedId)
        }
        toolToggle.check(penButton.id)
        showTool(penButton.id)
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
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                if (!handwritingView.hasDrawing()) {
                    snackbar(getString(R.string.snackbar_handwriting_required))
                    return@setOnClickListener
                }
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
                    penType = selectedPenType
                )
                val saved = saveHandwritingContent(exportBitmap, options, existing)
                exportBitmap.recycle()
                if (saved == null) {
                    snackbar(getString(R.string.snackbar_handwriting_save_failed))
                    return@setOnClickListener
                }
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
        existing: HandwritingContent?
    ): HandwritingContent? {
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
            HandwritingContent(filename, options)
        }.getOrElse {
            if (reuseExisting == null) {
                runCatching { deleteFile(filename) }
            }
            null
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
        runCatching { deleteFile(path) }
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

    private fun defaultHandwritingOptions(): HandwritingOptions {
        val (maxWidth, maxHeight) = cardCanvasBounds()
        val (canvasWidth, canvasHeight) = computeCanvasSizeForRatio(DEFAULT_CANVAS_RATIO, maxWidth, maxHeight)
        return HandwritingOptions(
            backgroundColor = DEFAULT_HANDWRITING_BACKGROUND,
            brushColor = DEFAULT_HANDWRITING_BRUSH,
            brushSizeDp = DEFAULT_HANDWRITING_BRUSH_SIZE_DP,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            format = HandwritingFormat.PNG,
            paperStyle = DEFAULT_HANDWRITING_PAPER_STYLE,
            penType = DEFAULT_HANDWRITING_PEN_TYPE
        )
    }

    private fun parseHandwritingContent(cardObj: JSONObject): HandwritingContent? {
        val handwritingObj = cardObj.optJSONObject("handwriting")
        val baseOptions = defaultHandwritingOptions()
        if (handwritingObj != null) {
            val path = handwritingObj.optString("path")
            if (path.isNullOrBlank()) return null
            val optionsObj = handwritingObj.optJSONObject("options")
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
            return HandwritingContent(
                path = path,
                options = HandwritingOptions(
                    backgroundColor = background,
                    brushColor = brushColor,
                    brushSizeDp = brushSize,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    format = format,
                    paperStyle = paperStyle,
                    penType = penType
                )
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
            flow.cards.forEach { card ->
                val obj = JSONObject()
                obj.put("id", card.id)
                obj.put("title", card.title)
                obj.put("snippet", card.snippet)
                obj.put("updatedAt", card.updatedAt)
                card.handwriting?.let { content ->
                    val options = content.options
                    val optionsObj = JSONObject().apply {
                        put("backgroundColor", colorToString(options.backgroundColor))
                        put("brushColor", colorToString(options.brushColor))
                        put("brushSizeDp", options.brushSizeDp.toDouble())
                        put("canvasWidth", options.canvasWidth)
                        put("canvasHeight", options.canvasHeight)
                        put("format", options.format.name)
                        put("paperStyle", options.paperStyle.name)
                        put("penType", options.penType.name)
                    }
                    val handwritingObj = JSONObject().apply {
                        put("path", content.path)
                        put("options", optionsObj)
                    }
                    obj.put("handwriting", handwritingObj)
                    obj.put("handwritingPath", content.path)
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
                flowControllers.remove(it)
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
                    flowControllers.remove(it)
                }
                boundFlowId = flow.id
                val controller = FlowPageController(flow.id, recycler, layoutManager, adapter)
                flowControllers[flow.id] = controller
                adapter.setBodyTextSize(cardFontSizeSp)
                adapter.submitList(flow.cards.toList())
                controller.restoreState(flow)
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

    private inner class FlowPageController(
        val flowId: Long,
        val recycler: RecyclerView,
        val layoutManager: RightRailFlowLayoutManager,
        val adapter: CardsAdapter
    ) {
        fun restoreState(flow: CardFlow) {
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
            val cardId = adapter.getItem(clampedIndex)?.id
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
            val nearest = layoutManager.nearestIndex().coerceIn(0, adapter.itemCount - 1)
            flow.lastViewedCardIndex = nearest
            flow.lastViewedCardId = adapter.getItem(nearest)?.id
            flow.lastViewedCardFocused = layoutManager.isFocused(nearest)
        }
    }

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
        private const val DEFAULT_CARD_FONT_SIZE_SP = 18f
        private const val MIN_HANDWRITING_BRUSH_SIZE_DP = 2f
        private const val MAX_HANDWRITING_BRUSH_SIZE_DP = 18f
        private const val DEFAULT_HANDWRITING_BRUSH_SIZE_DP = 6f
        private const val DEFAULT_CANVAS_RATIO = 0.75f
        private const val DEFAULT_HANDWRITING_BACKGROUND = -0x1
        private const val DEFAULT_HANDWRITING_BRUSH = -0x1000000
        private val DEFAULT_HANDWRITING_PAPER_STYLE = HandwritingPaperStyle.PLAIN
        private val DEFAULT_HANDWRITING_PEN_TYPE = HandwritingPenType.ROUND
    }
}
