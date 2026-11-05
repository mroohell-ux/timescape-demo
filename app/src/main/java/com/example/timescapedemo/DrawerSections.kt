package com.example.timescapedemo

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

internal class DrawerSections(root: View, savedState: Bundle?) {
    private class Section(
        val key: String,
        val header: View,
        val content: View,
        val title: Int,
        val summary: Int
    ) {
        fun setExpanded(expanded: Boolean) {
            header.isSelected = expanded
            content.visibility = if (expanded) View.VISIBLE else View.GONE
            header.findViewById<ImageView>(R.id.drawerSectionArrow).rotation =
                if (expanded) 0f else 180f
            ViewCompat.setStateDescription(
                header,
                header.context.getString(
                    if (expanded) R.string.card_state_expanded else R.string.card_state_collapsed
                )
            )
        }
    }

    private val sections = listOf(
        Section(
            "appearance", root.findViewById(R.id.drawerAppearanceHeader),
            root.findViewById(R.id.drawerAppearanceContent),
            R.string.drawer_section_appearance, R.string.drawer_section_appearance_summary
        ),
        Section(
            "reading", root.findViewById(R.id.drawerReadingHeader),
            root.findViewById(R.id.drawerReadingContent),
            R.string.drawer_section_reading, R.string.drawer_section_reading_summary
        ),
        Section(
            "sync", root.findViewById(R.id.drawerSyncHeader),
            root.findViewById(R.id.drawerSyncContent),
            R.string.drawer_section_sync, R.string.drawer_section_sync_summary
        ),
        Section(
            "data", root.findViewById(R.id.drawerDataHeader),
            root.findViewById(R.id.drawerDataContent),
            R.string.drawer_section_data, R.string.drawer_section_data_summary
        )
    )

    init {
        val expandedKeys = savedState?.getStringArrayList(STATE_EXPANDED_SECTIONS).orEmpty()
        sections.forEach { section ->
            val header = section.header
            header.findViewById<TextView>(R.id.drawerSectionTitle).setText(section.title)
            header.findViewById<TextView>(R.id.drawerSectionSummary).setText(section.summary)
            header.contentDescription = header.context.getString(
                R.string.drawer_section_description,
                header.context.getString(section.title), header.context.getString(section.summary)
            )
            section.setExpanded(section.key in expandedKeys)
            header.setOnClickListener { section.setExpanded(!header.isSelected) }
            ViewCompat.setAccessibilityDelegate(header, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                    info.addAction(
                        if (host.isSelected) {
                            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_COLLAPSE
                        } else {
                            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_EXPAND
                        }
                    )
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    when (action) {
                        AccessibilityNodeInfoCompat.ACTION_EXPAND -> section.setExpanded(true)
                        AccessibilityNodeInfoCompat.ACTION_COLLAPSE -> section.setExpanded(false)
                        else -> return super.performAccessibilityAction(host, action, args)
                    }
                    return true
                }
            })
        }
    }

    fun saveState(outState: Bundle) {
        outState.putStringArrayList(
            STATE_EXPANDED_SECTIONS,
            ArrayList(sections.filter { it.header.isSelected }.map { it.key })
        )
    }

    private companion object {
        const val STATE_EXPANDED_SECTIONS = "drawer_expanded_sections"
    }
}
