package com.example.timescapedemo

import android.util.JsonWriter
import org.json.JSONObject

internal data class CardCollectionState(
    val isCollected: Boolean,
    val originalIndex: Int?
) {
    fun writeTo(writer: JsonWriter) {
        writer.name("isCollected").value(isCollected)
        originalIndex?.let { writer.name("collectionOriginalIndex").value(it.toLong()) }
    }

    companion object {
        fun fromJson(json: JSONObject) = CardCollectionState(
            json.optBoolean("isCollected", false),
            json.optInt("collectionOriginalIndex", -1).takeIf { it >= 0 }
        )
    }
}
