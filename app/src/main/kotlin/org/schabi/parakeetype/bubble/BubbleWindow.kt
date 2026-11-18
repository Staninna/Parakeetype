package org.schabi.parakeetype.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.Composable

private const val TAG = "BubbleWindow"

private const val PREFS = "bubble"
private const val PREF_RIGHT = "docked_right"
/** Legacy: the old keyboard-relative height, replaced by the single [PREF_Y]. */
private const val PREF_LIFT_LEGACY = "lift_px"
private const val PREF_Y = "free_y"

private const val EDGE_MARGIN_DP = 4

/** Minimum gap between the bubble and the keyboard's top edge when the keyboard pushes it. */
private const val KEYBOARD_GAP_DP = 8

/** Default height of the bubble's top edge, as a fraction of the screen: above most keyboards. */
private const val DEFAULT_Y_FRACTION = 0.35f

private const val MOVE_ANIMATION_MS = 180L

/**
 * The bubble's overlay window: a [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY], so no
 * "display over other apps" permission is needed.
 *
 * It docks to a screen side at a remembered height (a drag changes the side and the height; an
 * open keyboard only pushes it up when it would cover it, see [bubbleTop]). Height changes
 * slide instead of jumping.
 */
class BubbleWindow(
    context: Context,
    private val ui: BubbleUiState,
    content: @Composable () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density
    private val owner = OverlayOwner()

    val view = BubbleComposeView(context, onDrag = ::moveBy, onDragEnd = ::drop, content = content)
        .also { owner.attachTo(it) }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    var attached = false
        private set

    /** Top edge of the visible keyboard in screen pixels, or `null` when none is seen. */
    var keyboardTop: Int? = null

    private var dragging = false
    private var moveAnimator: ValueAnimator? = null
    private var moveTarget = 0

    init {
        ui.dockedRight = prefs.getBoolean(PREF_RIGHT, true)
        prefs.edit().remove(PREF_LIFT_LEGACY).apply()
    }

    /** Adds the window at its computed place. `false` if the system refused it. */
    fun attach(): Boolean {
        check(!attached) { "The bubble is already shown" }
        computePosition()
        return try {
            windowManager.addView(view, params)
            attached = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not add the bubble", e)
            false
        }
    }

    fun remove() {
        moveAnimator?.cancel()
        // A drag in progress gets no end event once the window is gone.
        dragging = false
        if (!attached) return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove the bubble", e)
        }
        attached = false
    }

    fun destroy() {
        remove()
        owner.destroy()
    }

    /**
     * Moves the bubble to its computed place (e.g. the keyboard opened or closed), unless the
     * user is dragging it.
     */
    fun place() {
        if (!attached || dragging) return
        val running = moveAnimator?.isRunning == true
        val fromY = if (running) moveAnimator?.animatedValue as Int else params.y
        val oldX = params.x
        val oldGravity = params.gravity
        computePosition()
        val toY = params.y
        val sameSide = params.x == oldX && params.gravity == oldGravity
        if (sameSide && (toY == fromY || (running && toY == moveTarget))) {
            // Already there, or already sliding there: let the running slide finish.
            if (running) params.y = fromY
            return
        }
        moveAnimator?.cancel()
        if (!sameSide || toY == fromY) {
            windowManager.updateViewLayout(view, params)
            return
        }
        params.y = fromY
        moveTarget = toY
        moveAnimator = ValueAnimator.ofInt(fromY, toY).apply {
            duration = MOVE_ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                if (!attached || dragging) return@addUpdateListener
                params.y = it.animatedValue as Int
                windowManager.updateViewLayout(view, params)
            }
            start()
        }
    }

    private fun computePosition() {
        val bounds = windowManager.currentWindowMetrics.bounds
        // Physical sides: START/END would flip in RTL locales and break the drag maths.
        params.gravity = Gravity.TOP or if (ui.dockedRight) Gravity.RIGHT else Gravity.LEFT
        params.x = dp(EDGE_MARGIN_DP)
        params.y = bubbleTop(
            savedY = prefs.getInt(PREF_Y, (bounds.height() * DEFAULT_Y_FRACTION).toInt()),
            screenHeight = bounds.height(),
            windowSize = windowSizePx(),
            keyboardTop = keyboardTop,
            keyboardGap = dp(KEYBOARD_GAP_DP),
        )
    }

    private fun moveBy(dx: Int, dy: Int) {
        if (!attached) return
        val bounds = windowManager.currentWindowMetrics.bounds
        if (!dragging) {
            dragging = true
            moveAnimator?.cancel()
            // Switch to absolute left-based coordinates for the drag.
            if (params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.RIGHT) {
                params.x = bounds.width() - params.x - view.width
            }
            params.gravity = Gravity.TOP or Gravity.LEFT
        }
        params.x = (params.x + dx).coerceIn(0, (bounds.width() - view.width).coerceAtLeast(0))
        params.y = (params.y + dy).coerceIn(0, (bounds.height() - view.height).coerceAtLeast(0))
        windowManager.updateViewLayout(view, params)
    }

    /** Snaps to the nearer side and remembers the side and the height. */
    private fun drop() {
        dragging = false
        val width = windowManager.currentWindowMetrics.bounds.width()
        ui.dockedRight = docksRight(params.x, view.width, width)
        prefs.edit()
            .putBoolean(PREF_RIGHT, ui.dockedRight)
            .putInt(PREF_Y, droppedTop(params.y, windowSizePx(), keyboardTop, dp(KEYBOARD_GAP_DP)))
            .apply()
        view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
        computePosition()
        if (attached) windowManager.updateViewLayout(view, params)
    }

    /** Height (and idle width) of the overlay window: the bubble plus its shadow padding. */
    private fun windowSizePx(): Int = view.height.takeIf { it > 0 }
        ?: dp((BUBBLE_SIZE + BUBBLE_WINDOW_PADDING * 2).value.toInt())

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
