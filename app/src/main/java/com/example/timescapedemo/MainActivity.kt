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
        // Width sizing (height is now text-driven + capped at 2/3 screen)
        val baseSide = (380 * d).toInt()          // non-focused near-center width
        val focusSide = (380 * d).toInt()         // focused main width
        // Neighbor spacing (lower => closer; slight overlap)
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

        // --------- Mixed-length demo texts (short -> very long) ----------
        val oneLiner = "Ping me when you’re free."
        val short = "Grows toward center. Two neighbors overlap a bit."
        val medium = """
            This is a slightly longer body so you can see the card stretch
            beyond a couple of lines. It should be comfortably taller than the short one.
        """.trimIndent()
        val longish = """
            Here’s a longer description to show adaptive height.
            • Cards come in from the right rail.
            • Center card nudges left.
            • No rotation, only translation + alpha/scale.
            Try tapping to focus; tap again to defocus.
        """.trimIndent()
        val veryLong = """
            Long body to approach the 2/3 screen-height cap:
            Timescape-like flows feel natural when size responds to content.
            The layout uses wrap-content height but enforces a maximum so
            cards never dominate the screen. This paragraph keeps going to
            demonstrate truncation by the parent’s height cap. You should see
            this one taller than the previous items, yet it won’t exceed two-thirds
            of the viewport height even on devices with smaller screens.
        """.trimIndent()
        val extremelyLong = """
            EXTREMELY LONG CONTENT — intended to exceed the allowed maximum so the
            card is visibly capped at about two-thirds of the screen height. The
            text keeps going to guarantee the cap is hit. Imagine patch notes,
            chat transcripts, or multi-paragraph summaries flowing here. The
            MaxHeightLinearLayout constrains height while the LayoutManager fixes
            width and preserves the right-rail motion. Near center, text size bumps
            a bit; away from center, it recedes. The aim is readable density without
            the tallest card overwhelming the viewport. Keep scrolling to see how
            different lengths produce different card heights across the list. If you
            tap a card that’s already centered, it will enter the focused state.
            Tap again to return.
        """.trimIndent()

        // Cycle the variants so every few items have different heights.
        val variants = listOf(oneLiner, short, medium, longish, veryLong, extremelyLong)

        val items = List(30) { i ->
            CardItem(
                title = "Contact $i",
                snippet = variants[i % variants.size],
                time = "${(i % 12) + 1}h ago",
                imageRes = R.drawable.img_1
            )
        }

        var pendingFocus: Int? = null
        recycler.adapter = CardsAdapter(items) { index ->
            if (lm.isFocused(index)) { lm.clearFocus(); return@CardsAdapter }
            val delta = lm.offsetTo(index)         // centers to screen middle
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
