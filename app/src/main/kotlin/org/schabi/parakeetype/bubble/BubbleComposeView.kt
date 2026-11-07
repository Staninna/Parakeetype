package org.schabi.parakeetype.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AbstractComposeView
import kotlin.math.abs

/**
 * Hosts [BubbleContent] in the overlay window and turns a drag anywhere on it into window
 * moves.
 *
 * Dragging is detected here on raw screen coordinates rather than in Compose: the window moves
 * under the finger while dragging, so window-local pointer positions would fight the move.
 * Touches that stay within the touch slop go to Compose unchanged (taps, long-presses,
 * ripples); once a touch turns into a drag, Compose gets an ACTION_CANCEL so no click fires.
 */
@SuppressLint("ViewConstructor")
class BubbleComposeView(
    context: Context,
    private val onDrag: (dx: Int, dy: Int) -> Unit,
    private val onDragEnd: () -> Unit,
    private val content: @Composable () -> Unit,
) : AbstractComposeView(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    @Composable
    override fun Content() = content()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY
                lastX = downX; lastY = downY
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop)) {
                    dragging = true
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
                if (dragging) {
                    val dx = (event.rawX - lastX).toInt()
                    val dy = (event.rawY - lastY).toInt()
                    // Only consume what was applied, so rounding never drifts the bubble.
                    lastX += dx
                    lastY += dy
                    if (dx != 0 || dy != 0) onDrag(dx, dy)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    onDragEnd()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }
}

/**
 * Hosts the bubble's menu card in its own overlay window (with
 * `FLAG_WATCH_OUTSIDE_TOUCH`). A touch outside it closes the menu through [onOutside] — but only
 * one in another app: Android zeroes the coordinates of an outside touch in a window of another
 * UID, while a touch on the bubble's own mic window keeps them; the mic handles that tap itself
 * (it closes the menu instead of starting a dictation).
 */
@SuppressLint("ViewConstructor")
class OutsideTouchComposeView(
    context: Context,
    private val onOutside: () -> Unit,
    private val content: @Composable () -> Unit,
) : AbstractComposeView(context) {

    @Composable
    override fun Content() = content()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            if (event.x == 0f && event.y == 0f) onOutside()
            return true
        }
        return super.dispatchTouchEvent(event)
    }
}
