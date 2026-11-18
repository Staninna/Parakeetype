package org.schabi.parakeetype.bubble

/** The field text after inserting a transcript, and where the cursor goes. */
data class Splice(val text: String, val cursor: Int)

/** Punctuation that attaches to the previous word, so no space goes in front of it. */
private const val CLOSING_PUNCTUATION = ".,;:!?)]}…'\"%"

/** Characters after which a new word follows without a space. */
private const val OPENING_PUNCTUATION = "([{\"'/@#"

/**
 * The selection [selStart]..[selEnd] of a [length]-character text as an ordered range. A
 * missing or invalid selection (negative, as reported by fields without a cursor, or out of
 * range) is the end of the text.
 */
private fun selectionRange(length: Int, selStart: Int, selEnd: Int): IntRange =
    if (selStart !in 0..length || selEnd !in 0..length) {
        length..length
    } else {
        minOf(selStart, selEnd)..maxOf(selStart, selEnd)
    }

/**
 * Inserts [insert] into [current] in place of the selection [selStart]..[selEnd], adding a
 * space before or after it where it would otherwise run into the neighbouring word.
 *
 * A missing or invalid selection appends at the end. The cursor ends up right after the
 * inserted text (and its trailing space).
 */
fun spliceTranscript(current: CharSequence, selStart: Int, selEnd: Int, insert: String): Splice {
    val text = current.toString()
    val trimmed = insert.trim()
    if (trimmed.isEmpty()) {
        val at = if (selStart in 0..text.length) selStart else text.length
        return Splice(text, at)
    }

    val selection = selectionRange(text.length, selStart, selEnd)
    val before = text.substring(0, selection.first)
    val after = text.substring(selection.last)

    val needsLeadingSpace = before.isNotEmpty() &&
            !before.last().isWhitespace() &&
            before.last() !in OPENING_PUNCTUATION &&
            trimmed.first() !in CLOSING_PUNCTUATION
    val needsTrailingSpace = after.isNotEmpty() &&
            !after.first().isWhitespace() &&
            after.first() !in CLOSING_PUNCTUATION

    val piece = buildString {
        if (needsLeadingSpace) append(' ')
        append(trimmed)
        if (needsTrailingSpace) append(' ')
    }
    return Splice(before + piece + after, before.length + piece.length)
}

/**
 * The text to put on the clipboard when the field only accepts a paste, which replaces the
 * selection [selStart]..[selEnd]: exactly what [spliceTranscript] would insert there, spaces
 * included. An unknown selection is taken as the end of the text, like in [spliceTranscript].
 */
fun pasteText(current: CharSequence, selStart: Int, selEnd: Int, insert: String): String {
    if (insert.isBlank()) return ""
    val splice = spliceTranscript(current, selStart, selEnd, insert)
    return splice.text.substring(selectionRange(current.length, selStart, selEnd).first, splice.cursor)
}

/**
 * A field's real text, with a placeholder read as empty. Some apps surface their placeholder
 * through `AccessibilityNodeInfo.getText` while the field is empty, without setting
 * `isShowingHintText`, so it would otherwise be spliced in as existing text. WhatsApp's empty
 * compose box, for instance, reports `text="Message"` with no hint and no caret, and a
 * transcript came out as "Message hello".
 *
 * A focused editable field with real content always reports a caret, so non-empty text with no
 * text selection is a leaked placeholder, not content. Hint equality is deliberately not used:
 * a field may legitimately hold text equal to its hint (e.g. a typed "Search").
 */
fun fieldText(text: CharSequence?, showingHint: Boolean, selStart: Int, selEnd: Int): CharSequence =
    when {
        showingHint || text.isNullOrEmpty() -> ""
        selStart < 0 && selEnd < 0 -> ""
        else -> text
    }
