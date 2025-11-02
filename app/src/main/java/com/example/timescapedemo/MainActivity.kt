package com.example.timescapedemo

import android.os.Bundle
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.setHasFixedSize(true)
        recycler.clipChildren = false
        recycler.clipToPadding = false

        val d = resources.displayMetrics.density
        val baseSide = (380 * d).toInt()           // non-focused near-center size
        val focusSide = (380 * d).toInt()          // focused main card size
        // Tighter spacing (neighbors overlap a little). Lower => closer.
        val pitch = (focusSide * 0.32f).toInt()

        val lm = RightRailFlowLayoutManager(
            baseSidePx = baseSide,
            focusSidePx = focusSide,
            itemPitchPx = pitch,
            rightInsetPx = (16 * d).toInt(),
            topInsetPx = 0,
            bottomInsetPx = 0,
            minEdgeScale = 0.66f,
            edgeAlphaMin = 0.30f,
            depthScaleDrop = 0.06f
        )
        recycler.layoutManager = lm

        val items = List(30) { i ->
            CardItem(
                title = "Contact $i",
                snippet = "Grows toward true center. Only two neighbors overlap a bit. Tap to focus; tap again to return.",
                time = "${(i % 12) + 1}h ago",
                imageRes = R.drawable.bg_placeholder
            )
        }

        var pendingFocus: Int? = null
        recycler.adapter = CardsAdapter(items) { index ->
            if (lm.isFocused(index)) { lm.clearFocus(); return@CardsAdapter }
            val delta = lm.offsetTo(index)         // <-- centers to screen middle now
            if (delta == 0) lm.focus(index) else {
                pendingFocus = index
                recycler.smoothScrollBy(0, delta)
            }
        }
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    pendingFocus?.let { lm.focus(it); pendingFocus = null }
                }
            }
        })

        // Back: defocus first if any
        onBackPressedDispatcher.addCallback(this) {
            val idx = lm.nearestIndex()
            if (lm.isFocused(idx)) lm.clearFocus() else {
                remove(); onBackPressedDispatcher.onBackPressed()
            }
        }
    }
}
