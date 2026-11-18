package org.schabi.parakeetype.bubble

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSpliceTest {

    @Test
    fun `empty field takes the transcript as is`() {
        assertEquals(Splice("Hello there.", 12), spliceTranscript("", 0, 0, " Hello there. "))
    }

    @Test
    fun `appends with a space after a word`() {
        assertEquals(Splice("Hi Stan. How are you?", 21), spliceTranscript("Hi Stan.", 8, 8, "How are you?"))
    }

    @Test
    fun `no extra space after existing whitespace`() {
        assertEquals(Splice("Hi Stan. How", 12), spliceTranscript("Hi Stan. ", 9, 9, "How"))
    }

    @Test
    fun `inserting in the middle adds spaces on both sides`() {
        assertEquals(Splice("one two three", 7), spliceTranscript("one three", 3, 3, "two"))
    }

    @Test
    fun `replaces the selection`() {
        assertEquals(Splice("one two three", 7), spliceTranscript("one XXX three", 4, 7, "two"))
    }

    @Test
    fun `reversed selection is normalised`() {
        assertEquals(Splice("one two three", 7), spliceTranscript("one XXX three", 7, 4, "two"))
    }

    @Test
    fun `invalid selection appends at the end`() {
        assertEquals(Splice("abc def", 7), spliceTranscript("abc", -1, -1, "def"))
        assertEquals(Splice("abc def", 7), spliceTranscript("abc", 10, 10, "def"))
    }

    @Test
    fun `punctuation attaches to the previous word`() {
        assertEquals(Splice("wait, what", 5), spliceTranscript("wait what", 4, 4, ","))
    }

    @Test
    fun `no space after an opening bracket`() {
        assertEquals(Splice("(note)", 5), spliceTranscript("()", 1, 1, "note"))
    }

    @Test
    fun `blank transcript leaves the field unchanged`() {
        assertEquals(Splice("abc", 1), spliceTranscript("abc", 1, 2, "   "))
    }

    @Test
    fun `paste text without a cursor gets a leading space only when the field has text`() {
        assertEquals("Hello", pasteText("", -1, -1, " Hello "))
        assertEquals(" Hello", pasteText("world", -1, -1, "Hello"))
        assertEquals(".", pasteText("world", -1, -1, "."))
    }

    @Test
    fun `paste text follows the cursor`() {
        assertEquals("Hello ", pasteText("world", 0, 0, "Hello"))
        assertEquals(" there", pasteText("Hi", 2, 2, "there"))
        assertEquals("Hello", pasteText("", 0, 0, "Hello"))
    }

    @Test
    fun `paste text replacing the whole selection has no extra spaces`() {
        assertEquals("Hello", pasteText("world", 0, 5, "Hello"))
    }

    @Test
    fun `paste text without a cursor follows the same spacing as a splice at the end`() {
        assertEquals("Hello", pasteText("world ", -1, -1, "Hello"))
        assertEquals("note", pasteText("(", -1, -1, "note"))
    }

    @Test
    fun `blank paste text is empty`() {
        assertEquals("", pasteText("world", 2, 1, "  "))
    }

    @Test
    fun `field text is the text while the field has a caret`() {
        assertEquals("Hello", fieldText("Hello", showingHint = false, selStart = 5, selEnd = 5))
    }

    @Test
    fun `field text of a field showing its hint is empty`() {
        assertEquals("", fieldText("Message", showingHint = true, selStart = 0, selEnd = 0))
    }

    @Test
    fun `text without a caret is a leaked placeholder`() {
        assertEquals("", fieldText("Message", showingHint = false, selStart = -1, selEnd = -1))
    }

    @Test
    fun `text equal to a hint still counts when it has a caret`() {
        assertEquals("Search", fieldText("Search", showingHint = false, selStart = 6, selEnd = 6))
    }

    @Test
    fun `missing text is empty`() {
        assertEquals("", fieldText(null, showingHint = false, selStart = 0, selEnd = 0))
    }

    @Test
    fun `a leaked placeholder is not spliced into the transcript`() {
        val current = fieldText("Message", showingHint = false, selStart = -1, selEnd = -1)
        assertEquals(Splice("hello", 5), spliceTranscript(current, -1, -1, "hello"))
    }
}
