package com.example.timescapedemo

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

class ShuffledFlowActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var layoutManager: RightRailFlowLayoutManager
    private lateinit var adapter: CardsAdapter
    private lateinit var cardCountView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shuffled_flow)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarShuffledFlow)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.navigationIcon = AppCompatResources.getDrawable(this, R.drawable.ic_back_arrow)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        recycler = findViewById(R.id.recyclerShuffledCards)
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

        val cards = ShuffledFlowCache.consume()
        if (cards.isNullOrEmpty()) {
            finish()
            return
        }

        adapter.submitList(cards) {
            recycler.post {
                layoutManager.focus(0)
                updateCardCounter(layoutManager.currentSelectionIndex())
            }
        }
    }

    private fun handleCardTap(index: Int) {
        if (index !in 0 until adapter.itemCount) return
        val delta = layoutManager.offsetTo(index)
        if (layoutManager.isFocused(index)) {
            layoutManager.clearFocus()
        } else if (delta == 0) {
            layoutManager.focus(index)
        } else {
            recycler.smoothScrollBy(0, delta)
        }
    }

    private fun updateCardCounter(selectionIndex: Int?) {
        val total = adapter.itemCount
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
