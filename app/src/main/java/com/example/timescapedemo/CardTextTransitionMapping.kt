package com.example.timescapedemo

internal data class NormalizedCardText(val text: String, val offsets: IntArray)

private val cardTextWhitespace = Regex("\\s+")

internal fun normalizeCardText(source: CharSequence): NormalizedCardText {
    val text = StringBuilder()
    val offsets = ArrayList<Int>()
    var cursor = 0
    for (space in cardTextWhitespace.findAll(source)) {
        for (index in cursor until space.range.first) {
            text.append(source[index])
            offsets += index
        }
        text.append(' ')
        offsets += space.range.first
        cursor = space.range.last + 1
    }
    for (index in cursor until source.length) {
        text.append(source[index])
        offsets += index
    }
    val start = text.indexOfFirst { !it.isWhitespace() }
    if (start < 0) return NormalizedCardText("", IntArray(0))
    val end = text.indexOfLast { !it.isWhitespace() } + 1
    return NormalizedCardText(text.substring(start, end),
        IntArray(end - start) { offsets[start + it] })
}

internal fun matchedCardTextOffsets(
    source: CharSequence,
    target: CharSequence,
    targetVisibleEnd: Int
): IntArray {
    val from = normalizeCardText(source)
    val to = normalizeCardText(target)
    if (from.text.isEmpty()) return IntArray(0)
    val prefix = to.text.indexOf(from.text)
    if (prefix < 0) return IntArray(0)
    val matches = ArrayList<Int>()
    for (index in from.text.indices) {
        val targetOffset = to.offsets[prefix + index]
        if (targetOffset >= targetVisibleEnd) break
        if (from.text[index].isWhitespace()) continue
        matches += from.offsets[index]
        matches += targetOffset
    }
    return matches.toIntArray()
}
