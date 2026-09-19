package com.wearos.ancsbridge.ancs

/**
 * Cleans up notification text before it is shown on the watch.
 *
 * Marketing emails pad their preview ("preheader") with long runs of invisible
 * characters — combining grapheme joiner, zero-width non-joiner and zero-width
 * no-break space, separated by spaces — so the mail app doesn't pull body text into
 * the preview. On the watch those runs render as blank space after the real text.
 */
object MessageText {

    private const val FILLER = "\\u034F\\u200B\\u200C\\uFEFF\\u00AD"

    /**
     * Two or more filler characters separated only by spaces. A single zero-width
     * non-joiner is left alone: Bengali, Persian and other scripts use it inside words.
     */
    private val FILLER_RUN = Regex("[ \\u00A0]*[$FILLER](?:[ \\u00A0]*[$FILLER])+[ \\u00A0]*")

    private val HAS_FILLER = Regex("[$FILLER]")

    fun clean(text: String): String {
        if (!HAS_FILLER.containsMatchIn(text)) return text
        return text.replace(FILLER_RUN, " ").trimEnd()
    }
}
