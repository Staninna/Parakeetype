package org.schabi.parakeetype.bubble

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

private const val TAG = "BubbleFieldFinder"

/** The on-screen keyboard as the bubble sees it. */
class Keyboard(
    /** Top edge of the keyboard window in screen pixels, or `null` when no keyboard is shown. */
    val top: Int?,
    /** The keyboard is Parakeetype's own, which has its own talk button. */
    val isOwn: Boolean,
)

/**
 * Answers the bubble's questions about the screen: where the keyboard is, and which text field
 * of another app has the input focus. Reads window structure and focus only, never field text.
 *
 * Accessibility queries go to other processes and fail while windows change; every query here
 * treats a failure as "not found" (logged) and the next event asks again.
 */
class FieldFinder(private val service: AccessibilityService) {

    /**
     * The last editable field an event came from (focus, click, cursor). Apps whose text fields
     * are virtual views (Compose, WebView, GeckoView) do not always answer an input-focus query
     * with the field itself, so this is the fallback for finding it.
     */
    private var lastEditable: AccessibilityNodeInfo? = null

    fun remember(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED -> Unit
            // Fires on every keystroke: only fetch the source while no field is known.
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> if (lastEditable != null) return
            else -> return
        }
        val source = query("the event source") { event.source } ?: return
        if (isUsable(source)) lastEditable = source
    }

    fun keyboard(): Keyboard {
        val ime = query("the windows") {
            service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } ?: return Keyboard(top = null, isOwn = false)
        val bounds = Rect().also { ime.getBoundsInScreen(it) }
        return Keyboard(
            top = bounds.top.takeIf { bounds.height() > 0 },
            isOwn = query("the keyboard window") { ime.root?.packageName?.toString() } == service.packageName,
        )
    }

    /**
     * The focused, editable, non-password field of another app, or `null`. Asks the system's
     * input focus, then every window, then falls back to the last field an event came from if
     * it still has focus.
     */
    fun editableFocus(): AccessibilityNodeInfo? =
        focusCandidates().firstOrNull { isUsable(it) }
            ?: lastEditable?.takeIf { isStillVisible(it) && it.isFocused && isUsable(it) }

    /**
     * Where to dictate into when [editableFocus] finds nothing although a keyboard is up: the
     * last field an event came from, if it is still on screen (virtual text fields, see
     * [lastEditable]).
     */
    fun recentEditable(): AccessibilityNodeInfo? =
        lastEditable?.takeIf { isStillVisible(it) && isUsable(it) }

    /** Whether the input focus is on a password field or anywhere in Parakeetype itself. */
    fun focusIsPasswordOrOwn(): Boolean {
        val focused = focusCandidates().firstOrNull() ?: return false
        return focused.isPassword || focused.packageName?.toString() == service.packageName
    }

    fun isStillVisible(node: AccessibilityNodeInfo): Boolean =
        query("a field") { node.refresh() && node.isVisibleToUser } == true

    /** Editable, not a password, not in Parakeetype itself. */
    private fun isUsable(node: AccessibilityNodeInfo): Boolean =
        node.isEditable && !node.isPassword && node.packageName?.toString() != service.packageName

    /** The input-focused nodes, most authoritative first: the system's, then each window's. */
    private fun focusCandidates(): Sequence<AccessibilityNodeInfo> {
        val global = sequenceOf(
            { service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) },
            { service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) },
        )
        // The system's input-focus lookup follows a single "focused window", which is briefly
        // wrong while windows change; ask every app window itself as well.
        val perWindow = sequence {
            val appWindows = query("the windows") { service.windows }.orEmpty()
                .filter {
                    it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                        it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                }
                .sortedByDescending { (if (it.isFocused) 2 else 0) + (if (it.isActive) 1 else 0) }
            for (window in appWindows) yield { window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
        }
        return (global + perWindow).mapNotNull { lookup -> query("the input focus", lookup) }
    }

    private inline fun <T> query(what: String, block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "Could not read $what", e)
        null
    }
}
