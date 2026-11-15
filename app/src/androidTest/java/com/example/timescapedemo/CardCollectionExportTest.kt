package com.example.timescapedemo

import android.util.JsonWriter
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.StringWriter

@RunWith(AndroidJUnit4::class)
class CardCollectionExportTest {
    @Test
    fun collectionStateRoundTripsThroughTheExportFields() {
        for (state in listOf(
            CardCollectionState(false, null),
            CardCollectionState(true, null),
            CardCollectionState(true, 0),
            CardCollectionState(true, 7)
        )) {
            val json = export(state)
            assertTrue(json.has("isCollected"))
            assertEquals(state.isCollected, json.getBoolean("isCollected"))
            assertEquals(state.originalIndex != null, json.has("collectionOriginalIndex"))
            state.originalIndex?.let { assertEquals(it, json.getInt("collectionOriginalIndex")) }
            assertEquals(state, CardCollectionState.fromJson(json))
        }
    }

    @Test
    fun legacyCardsWithoutCollectionFieldsRemainUnstarred() {
        assertEquals(CardCollectionState(false, null),
            CardCollectionState.fromJson(JSONObject("""{"title":"Legacy card","snippet":"Body"}""")))
    }

    @Test
    fun absentOrNegativeOriginalPositionDoesNotBecomeARestoreTarget() {
        for (json in listOf(
            JSONObject("""{"isCollected":true}"""),
            JSONObject("""{"isCollected":true,"collectionOriginalIndex":-1}"""),
            JSONObject("""{"isCollected":true,"collectionOriginalIndex":null}""")
        )) {
            assertEquals(CardCollectionState(true, null), CardCollectionState.fromJson(json))
        }
    }

    @Test
    fun importedStarsKeepTheirStateAndStableFrontOrderWithNewCardIds() {
        val states = listOf(
            CardCollectionState(false, null),
            CardCollectionState(true, 1),
            CardCollectionState(false, null),
            CardCollectionState(true, 3)
        )
        val imported = states.mapIndexed { index, original ->
            val state = CardCollectionState.fromJson(export(original))
            CardItem(100L + index, "Card $index", "Body",
                isCollected = state.isCollected, collectionOriginalIndex = state.originalIndex)
        }
        val ordered = collectedCardsFirst(imported)
        assertEquals(listOf(101L, 103L, 100L, 102L), ordered.map { it.id })
        assertEquals(listOf(true, true, false, false), ordered.map { it.isCollected })
        assertEquals(listOf(1, 3, null, null), ordered.map { it.collectionOriginalIndex })
    }

    private fun export(state: CardCollectionState): JSONObject {
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.beginObject()
            state.writeTo(writer)
            writer.endObject()
        }
        return JSONObject(output.toString())
    }
}
