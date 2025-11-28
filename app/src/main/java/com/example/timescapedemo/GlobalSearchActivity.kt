package com.example.timescapedemo

import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

class GlobalSearchActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recycler: RecyclerView
    private lateinit var layoutManager: RightRailFlowLayoutManager
    private lateinit var adapter: CardsAdapter
    private lateinit var cardCountView: TextView

    private var searchMenuItem: MenuItem? = null
    private var searchView: SearchView? = null
    private var allCards: List<CardItem> = emptyList()
    private var currentQuery: String = ""
    private var displayedCards: List<CardItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_global_search)

        toolbar = findViewById(R.id.toolbarGlobalSearch)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.navigationIcon = AppCompatResources.getDrawable(this, R.drawable.ic_back_arrow)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        recycler = findViewById(R.id.recyclerSearchResults)
        cardCountView = findViewById(R.id.cardCountIndicator)

        layoutManager = FlowLayoutManagerFactory.create(this)
        recycler.layoutManager = layoutManager
        recycler.setHasFixedSize(true)
        recycler.overScrollMode = RecyclerView.OVER_SCROLL_NEVER

        adapter = CardsAdapter(
            tint = TintStyle.MultiplyDark(color = Color.BLACK, alpha = 0.15f),
            onItemClick = { index -> handleCardTap(index) },
            onItemDoubleClick = { _ -> },
            onItemLongPress = { _, _ -> false },
            onTitleSpeakClick = null
        )
        recycler.adapter = adapter

        layoutManager.selectionListener = { updateCardCounter(it) }
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateCardCounter(layoutManager.currentSelectionIndex())
            }
        })

        val payload = GlobalSearchCache.consume()
        if (payload == null) {
            finish()
            return
        }

        allCards = payload.cards
        currentQuery = payload.query
        toolbar.title = getString(R.string.global_search_title)
        toolbar.subtitle = currentQuery.ifEmpty { getString(R.string.search_cards_hint) }

        submitResults(currentQuery) {
            recycler.post { focusInitialCard() }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_global_search, menu)
        searchMenuItem = menu.findItem(R.id.action_search_global)
        val view = searchMenuItem?.actionView as? SearchView ?: return true
        searchView = view
        view.queryHint = getString(R.string.search_cards_hint)
        view.maxWidth = resources.displayMetrics.widthPixels
        view.setQuery(currentQuery, false)
        view.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                applyQuery(query.orEmpty())
                view.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                applyQuery(newText.orEmpty())
                return true
            }
        })
        searchMenuItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                view.setQuery(currentQuery, false)
                view.post { view.requestFocus() }
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                view.clearFocus()
                return true
            }
        })
        searchMenuItem?.expandActionView()
        view.clearFocus()
        return true
    }

    private fun applyQuery(raw: String) {
        val normalized = raw.trim()
        if (normalized == currentQuery) return
        currentQuery = normalized
        toolbar.subtitle = normalized.ifEmpty { getString(R.string.search_cards_hint) }
        submitResults(normalized) { focusInitialCard() }
    }

    private fun submitResults(query: String, onCommitted: (() -> Unit)? = null) {
        displayedCards = CardSearch.filter(allCards, query)
        adapter.submitList(displayedCards) {
            onCommitted?.invoke()
            updateCardCounter(layoutManager.currentSelectionIndex())
            if (displayedCards.isEmpty()) {
                layoutManager.clearFocus()
            }
        }
    }

    private fun handleCardTap(index: Int) {
        adapter.getItemAt(index) ?: return
        if (layoutManager.isFocused(index)) {
            if (adapter.canFlipCardAt(index)) {
                val vh = recycler.findViewHolderForAdapterPosition(index) as? CardsAdapter.VH
                if (vh != null && adapter.toggleCardFace(vh) != null) {
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

    private fun focusInitialCard() {
        if (displayedCards.isEmpty()) return
        val target = layoutManager.currentSelectionIndex()?.coerceIn(0, displayedCards.lastIndex) ?: 0
        layoutManager.focus(target)
        updateCardCounter(layoutManager.currentSelectionIndex())
    }

    private fun updateCardCounter(selectionIndex: Int?) {
        val total = displayedCards.size
        if (total <= 0) {
            cardCountView.isVisible = false
            cardCountView.text = ""
            return
        }
        val safeIndex = (selectionIndex ?: layoutManager.nearestIndex()).coerceIn(0, total - 1)
        cardCountView.text = getString(R.string.card_counter_format, safeIndex + 1, total)
        cardCountView.isVisible = true
    }
}
