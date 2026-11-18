package org.schabi.parakeetype.bubble

/**
 * Where the bubble's window sits vertically: the remembered height [savedY], kept on screen,
 * and pushed up above an open keyboard (top edge [keyboardTop]) when it would cover the bubble.
 * The same height applies with and without a keyboard.
 */
fun bubbleTop(savedY: Int, screenHeight: Int, windowSize: Int, keyboardTop: Int?, keyboardGap: Int): Int {
    val maxY = (screenHeight - windowSize).coerceAtLeast(0)
    val y = savedY.coerceIn(0, maxY)
    return aboveKeyboard(y, windowSize, keyboardTop, keyboardGap).coerceIn(0, maxY)
}

/**
 * The height to remember after a drop at [droppedY]: a drop onto the keyboard lands just above
 * it, so the bubble does not jump back down once the keyboard closes.
 */
fun droppedTop(droppedY: Int, windowSize: Int, keyboardTop: Int?, keyboardGap: Int): Int =
    aboveKeyboard(droppedY, windowSize, keyboardTop, keyboardGap).coerceAtLeast(0)

/** Whether a bubble dropped with its left edge at [x] docks to the right screen edge. */
fun docksRight(x: Int, windowWidth: Int, screenWidth: Int): Boolean = x + windowWidth / 2 >= screenWidth / 2

private fun aboveKeyboard(y: Int, windowSize: Int, keyboardTop: Int?, keyboardGap: Int): Int =
    if (keyboardTop == null) y else minOf(y, keyboardTop - windowSize - keyboardGap)
