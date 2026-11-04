package com.example.timescapedemo

import org.junit.Assert.*
import org.junit.Test

class CardTextTransitionMappingTest {
    @Test fun normalizationPreservesTheOriginalCharacterOffsets() {
        val source = "  First\n\nsecond\tthird  "
        val normalized = normalizeCardText(source)
        assertEquals("First second third", normalized.text)
        for (index in normalized.text.indices) {
            if (normalized.text[index] != ' ') {
                assertEquals(normalized.text[index], source[normalized.offsets[index]])
            }
        }
        assertEquals("", normalizeCardText(" \n\t ").text)
    }

    @Test fun bodyMatchesPastAnInlineDateWithoutMatchingThatDate() {
        val source = "A quiet\nmoment above the clouds."
        val target = "1 hr. ago · A quiet moment above the clouds."
        val matches = matchedCardTextOffsets(source, target, target.length)
        assertTrue(matches.isNotEmpty())
        assertEquals(0, matches[0])
        assertEquals(target.indexOf("A quiet"), matches[1])
        for (index in matches.indices step 2) {
            assertEquals(source[matches[index]], target[matches[index + 1]])
        }
    }

    @Test fun ellipsizedTargetStopsMappingBeforeHiddenCharacters() {
        val source = "First second third fourth"
        val matches = matchedCardTextOffsets(source, source, 12)
        assertTrue(matches.isNotEmpty())
        assertTrue((1 until matches.size step 2).all { matches[it] < 12 })
        assertEquals(11, matches.last())
    }

    @Test fun unicodeAndEmojiKeepTheirExactUtf16Offsets() {
        val source = "山间清晨 🌄\nمرحبا بالعالم"
        val target = normalizeCardText(source).text
        val matches = matchedCardTextOffsets(source, target, target.length)
        for (index in matches.indices step 2) {
            assertEquals(source[matches[index]], target[matches[index + 1]])
        }
        val emoji = source.indexOf("🌄")
        assertTrue((matches.indices step 2).any { matches[it] == emoji })
        assertTrue((matches.indices step 2).any { matches[it] == emoji + 1 })
    }

    @Test fun unrelatedOrEmptyTextDoesNotProduceFalseSharedGlyphs() {
        assertEquals(0, matchedCardTextOffsets("Old body", "New body", 8).size)
        assertEquals(0, matchedCardTextOffsets("", "Date only", 9).size)
    }
}
