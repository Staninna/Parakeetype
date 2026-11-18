package org.schabi.parakeetype.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePlacementTest {

    private val screen = 2000
    private val size = 72
    private val gap = 16

    @Test
    fun `without a keyboard the bubble stays at its remembered height`() {
        assertEquals(700, bubbleTop(savedY = 700, screenHeight = screen, windowSize = size, keyboardTop = null, keyboardGap = gap))
    }

    @Test
    fun `the remembered height is kept on screen`() {
        assertEquals(0, bubbleTop(savedY = -50, screenHeight = screen, windowSize = size, keyboardTop = null, keyboardGap = gap))
        assertEquals(screen - size, bubbleTop(savedY = 5000, screenHeight = screen, windowSize = size, keyboardTop = null, keyboardGap = gap))
    }

    @Test
    fun `a keyboard below the bubble leaves it in place`() {
        assertEquals(700, bubbleTop(savedY = 700, screenHeight = screen, windowSize = size, keyboardTop = 1300, keyboardGap = gap))
    }

    @Test
    fun `a keyboard that would cover the bubble pushes it up above the gap`() {
        assertEquals(1300 - size - gap, bubbleTop(savedY = 1500, screenHeight = screen, windowSize = size, keyboardTop = 1300, keyboardGap = gap))
    }

    @Test
    fun `a keyboard taller than the screen leaves the bubble at the top, not off screen`() {
        assertEquals(0, bubbleTop(savedY = 500, screenHeight = screen, windowSize = size, keyboardTop = 40, keyboardGap = gap))
    }

    @Test
    fun `a drop onto the keyboard is remembered just above it`() {
        assertEquals(1300 - size - gap, droppedTop(droppedY = 1500, windowSize = size, keyboardTop = 1300, keyboardGap = gap))
        assertEquals(900, droppedTop(droppedY = 900, windowSize = size, keyboardTop = 1300, keyboardGap = gap))
        assertEquals(1500, droppedTop(droppedY = 1500, windowSize = size, keyboardTop = null, keyboardGap = gap))
    }

    @Test
    fun `a drop height is never negative`() {
        assertEquals(0, droppedTop(droppedY = 10, windowSize = size, keyboardTop = 50, keyboardGap = gap))
    }

    @Test
    fun `the bubble docks to the side its centre was dropped on`() {
        assertTrue(docksRight(x = 600, windowWidth = 72, screenWidth = 1080))
        assertFalse(docksRight(x = 100, windowWidth = 72, screenWidth = 1080))
        assertTrue(docksRight(x = 504, windowWidth = 72, screenWidth = 1080))
        assertFalse(docksRight(x = 503, windowWidth = 72, screenWidth = 1080))
    }
}
