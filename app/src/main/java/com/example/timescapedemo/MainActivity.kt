package com.example.timescapedemo

import android.os.Bundle
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerCards: RecyclerView
    private lateinit var lm: RightRailFlowLayoutManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        toolbar.title = getString(R.string.app_name)

        recyclerCards = findViewById(R.id.recyclerCards)

        val density = resources.displayMetrics.density
        val baseSide = (380 * density).toInt()
        val focusSide = (380 * density).toInt()
        val pitch = (focusSide * 0.26f).toInt()
        lm = RightRailFlowLayoutManager(
            baseSidePx = baseSide,
            focusSidePx = focusSide,
            itemPitchPx = pitch,
            rightInsetPx = (16 * density).toInt()
        )
        recyclerCards.layoutManager = lm

        recyclerCards.adapter = CardsAdapter(buildCards()) { index ->
            if (lm.isFocused(index)) {
                lm.clearFocus()
                return@CardsAdapter
            }
            val delta = lm.offsetTo(index)
            if (delta == 0) lm.focus(index) else recyclerCards.smoothScrollBy(0, delta)
        }

        onBackPressedDispatcher.addCallback(this) {
            val idx = lm.nearestIndex()
            if (lm.isFocused(idx)) {
                lm.clearFocus()
            } else {
                remove()
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun buildCards(): List<CardItem> {
        val snippets = listOf(
            "Ping me when you’re free.",
            "Grows toward center. Two neighbors overlap a bit.",
            "This is a slightly longer body so you can see the card stretch beyond a couple of lines.",
            "Here’s a longer description to show adaptive height. Tap to focus; tap again to defocus.",
            "Long body to approach the 2/3 screen-height cap.",
            "EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height.EXTREMELY LONG CONTENT — intentionally capped by parent height."
        )

        val ambientCycle = listOf(0.25f, 0.4f, 0.55f, 0.7f, 0.85f)

        return List(30) { index ->
            CardItem(
                title = "Contact $index",
                snippet = snippets[index % snippets.size],
                time = "${(index % 12) + 1}h ago",
                ambientLuminance = ambientCycle[index % ambientCycle.size]
            )
        }
    }
}
