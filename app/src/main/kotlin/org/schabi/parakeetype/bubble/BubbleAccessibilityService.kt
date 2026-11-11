package org.schabi.parakeetype.bubble

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.schabi.parakeetype.R
import org.schabi.parakeetype.audio.PermissionHelper
import org.schabi.parakeetype.inference.InferenceService
import org.schabi.parakeetype.inference.WordTiming
import org.schabi.parakeetype.recognition.InferenceConnection
import org.schabi.parakeetype.recognition.LanguageDetection
import org.schabi.parakeetype.recognition.RecognitionOptions
import org.schabi.parakeetype.recognition.RecognitionSession
import org.schabi.parakeetype.settings.SettingsActivity
import org.schabi.parakeetype.settings.preferences.AppPreferences
import org.schabi.parakeetype.ui.theme.ParakeetypeKeyboardTheme

private const val TAG = "BubbleService"

/**
 * Delay before re-checking the bubble after an accessibility event. Further events do not push
 * the check back (a plain debounce never ran while the user kept typing), so this is the
 * longest the bubble lags behind.
 */
private const val UPDATE_DELAY_MS = 100L

/**
 * A second check after the last event: window and focus changes (the keyboard sliding in, an
 * activity starting) often settle without a further event, and the first check saw them midway.
 */
private const val SETTLE_DELAY_MS = 450L

/**
 * How long "nothing to show on" must persist before the bubble is removed. A single check can
 * miss the focused field while windows change (the field's window is replaced, the focus moves
 * through a container, our own overlay is added); removing the bubble on it made it flash and
 * vanish while the keyboard stayed up.
 */
private const val HIDE_CONFIRM_MS = 300L

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
 * Shows a floating microphone bubble on focused text fields while another keyboard is in use,
 * so dictation works without switching to the Parakeetype keyboard.
 *
 * The bubble docks to a screen side at a remembered height (a drag changes the side
 * and the height; an open keyboard only pushes it up when it would cover it). Tapping it starts a segmented [RecognitionSession] — the
 * same pipeline and loaded model as the keyboard and the system recognizer — and turns it into
 * a recording pill with a live waveform, cancel and done. Each finished utterance is inserted
 * at the cursor of the field that was focused when dictation started.
 *
 * Insertion uses [AccessibilityNodeInfo.ACTION_SET_TEXT] (splicing at the selection), and
 * falls back to the clipboard plus [AccessibilityNodeInfo.ACTION_PASTE]. The bubble is a
 * [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY], so no "display over other apps"
 * permission is needed. Nothing leaves the device.
 */
class BubbleAccessibilityService : AccessibilityService() {

    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = Runnable { updateBubble() }
    private val settleRunnable = Runnable { updateBubble() }

    /** Since when the checks have said "hide" (uptime ms), or `null` while they say "show". */
    private var hideSince: Long? = null

    /**
     * The last editable field an event came from (focus, click, cursor). Apps whose text fields
     * are virtual views (Compose, WebView, GeckoView) do not always answer an input-focus query
     * with the field itself, so this is the fallback for finding it.
     */
    private var lastEditable: AccessibilityNodeInfo? = null

    private lateinit var windowManager: WindowManager
    private lateinit var inference: InferenceConnection
    private val ui = BubbleUiState()
    private val owner = OverlayOwner()
    private var view: BubbleComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    private var session: RecognitionSession? = null

    /** The field dictation started in; utterances go here even if focus moves meanwhile. */
    private var target: AccessibilityNodeInfo? = null

    /** Top edge of the visible keyboard in screen pixels, or `null` when none is seen. */
    private var keyboardTop: Int? = null

    private var keepModelLoaded = true

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        inference = InferenceConnection(this)
        ui.dockedRight = prefs().getBoolean(PREF_RIGHT, true)
        prefs().edit().remove(PREF_LIFT_LEGACY).apply()
        scope.launch {
            AppPreferences(applicationContext).keepModelLoaded.collect {
                keepModelLoaded = it
                // Turned off while the bubble is hidden: release the model now, not on next hide.
                if (!it && !attached && session == null) inference.unbind()
            }
        }
        updateBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) rememberEditable(event)
        scheduleUpdate()
    }

    private fun scheduleUpdate() {
        if (!handler.hasCallbacks(updateRunnable)) handler.postDelayed(updateRunnable, UPDATE_DELAY_MS)
        handler.removeCallbacks(settleRunnable)
        handler.postDelayed(settleRunnable, SETTLE_DELAY_MS)
    }

    private fun rememberEditable(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED -> Unit
            // Fires on every keystroke: only fetch the source while no field is known.
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> if (lastEditable != null) return
            else -> return
        }
        val source = try {
            event.source
        } catch (e: Exception) {
            null
        } ?: return
        if (isUsableField(source)) lastEditable = source
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session?.cancel()
        session = null
        removeBubble()
        owner.destroy()
        if (::inference.isInitialized) inference.unbind()
        scope.cancel()
        super.onDestroy()
    }

    // ── Visibility and placement ──────────────────────────────────────────────────────────────

    private fun updateBubble() {
        val keyboard = findKeyboard()
        keyboardTop = keyboard.top
        if (session != null) {
            // Navigating away mid-dictation (no text field focused any more) would otherwise
            // leave this segmented session recording with no silence timeout; cancel it.
            // Otherwise keep the bubble and follow the keyboard (if any) while dictating.
            val stillThere = findEditableFocus() != null || target?.let { isStillVisible(it) } == true
            if (stillThere) {
                hideSince = null
                placeBubble()
            } else if (confirmHide()) {
                cancelDictation()
            }
            return
        }
        val show = when {
            // Our own keyboard has its own talk button.
            keyboard.isOwn -> false
            // Never offer dictation into a password field, nor over Parakeetype itself.
            focusIsPasswordOrOwn() -> false
            // Show on any focused field, also when no keyboard is up (e.g. after Back dismissed
            // it); without a keyboard the bubble sits at its remembered free height.
            findEditableFocus() != null -> true
            // Another keyboard is up, so the user is typing somewhere, even when the field
            // could not be found (yet): the bubble belongs there.
            else -> keyboard.top != null
        }
        if (show) {
            hideSince = null
            showBubble()
        } else if (!attached || keyboard.isOwn || confirmHide()) {
            hideSince = null
            hideBubble()
        }
    }

    /**
     * `true` once "hide" has been seen for [HIDE_CONFIRM_MS]; until then it keeps the bubble and
     * schedules the confirming check.
     */
    private fun confirmHide(): Boolean {
        val now = SystemClock.uptimeMillis()
        val since = hideSince ?: now.also { hideSince = it }
        if (now - since >= HIDE_CONFIRM_MS) {
            hideSince = null
            return true
        }
        handler.removeCallbacks(updateRunnable)
        handler.postDelayed(updateRunnable, HIDE_CONFIRM_MS - (now - since))
        return false
    }

    private class Keyboard(val top: Int?, val isOwn: Boolean)

    /** The on-screen keyboard window, if any. */
    private fun findKeyboard(): Keyboard = try {
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (ime == null) {
            Keyboard(null, false)
        } else {
            val bounds = Rect().also { ime.getBoundsInScreen(it) }
            val pkg = ime.root?.packageName?.toString()
            Keyboard(
                top = bounds.top.takeIf { bounds.height() > 0 },
                isOwn = pkg == packageName,
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read windows", e)
        Keyboard(null, false)
    }

    /** Editable, not a password, not in Parakeetype itself. */
    private fun isUsableField(node: AccessibilityNodeInfo): Boolean =
        node.isEditable && !node.isPassword && node.packageName?.toString() != packageName

    /** The input-focused nodes, most authoritative first: the system's, then each window's. */
    private fun focusCandidates(): Sequence<AccessibilityNodeInfo> {
        val global = sequenceOf(
            { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) },
            { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) },
        )
        // The system's input-focus lookup follows a single "focused window", which is briefly
        // wrong while windows change; ask every app window itself as well.
        val perWindow = sequence {
            val appWindows = (try { windows } catch (e: Exception) { emptyList() })
                .filter {
                    it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                        it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                }
                .sortedByDescending { (if (it.isFocused) 2 else 0) + (if (it.isActive) 1 else 0) }
            for (window in appWindows) yield { window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
        }
        return (global + perWindow).mapNotNull { lookup ->
            try {
                lookup()
            } catch (e: Exception) {
                Log.w(TAG, "Could not query the input focus", e)
                null
            }
        }
    }

    /**
     * The focused, editable, non-password field of another app, or `null`. Asks the system's
     * input focus, then every window, then falls back to the last field an event came from if
     * it still has focus.
     */
    private fun findEditableFocus(): AccessibilityNodeInfo? =
        focusCandidates().firstOrNull { isUsableField(it) }
            ?: lastEditable?.takeIf { isStillFocused(it) }

    /** Whether the input focus is on a password field or anywhere in Parakeetype itself. */
    private fun focusIsPasswordOrOwn(): Boolean {
        val focused = focusCandidates().firstOrNull() ?: return false
        return focused.isPassword || focused.packageName?.toString() == packageName
    }

    private fun isStillVisible(node: AccessibilityNodeInfo): Boolean = try {
        node.refresh() && node.isVisibleToUser
    } catch (e: Exception) {
        false
    }

    private fun isStillFocused(node: AccessibilityNodeInfo): Boolean =
        isStillVisible(node) && node.isFocused && isUsableField(node)

    private fun showBubble() {
        if (!attached) {
            val v = view ?: BubbleComposeView(
                this,
                onDrag = ::moveBy,
                onDragEnd = ::dropBubble,
            ) {
                ParakeetypeKeyboardTheme {
                    BubbleContent(
                        state = ui,
                        onStart = ::startDictation,
                        onFinish = ::finishDictation,
                        onCancel = ::cancelDictation,
                    )
                }
            }.also {
                owner.attachTo(it)
                view = it
            }
            val lp = params ?: WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).also { params = it }
            computePosition(lp)
            try {
                windowManager.addView(v, lp)
                attached = true
                // Start loading the model now, so it is ready (or nearly) by the time of the tap.
                inference.bind()
            } catch (e: Exception) {
                Log.e(TAG, "Could not add the bubble", e)
            }
        } else {
            placeBubble()
        }
    }

    private fun hideBubble() {
        if (!attached) return
        removeBubble()
        // Without "keep model loaded" the model is released together with the bubble.
        if (!keepModelLoaded) inference.unbind()
    }

    private fun removeBubble() {
        moveAnimator?.cancel()
        val v = view ?: return
        if (!attached) return
        try {
            windowManager.removeView(v)
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove the bubble", e)
        }
        attached = false
    }

    private var dragging = false

    private var moveAnimator: ValueAnimator? = null
    private var moveTarget = 0

    /**
     * Moves the bubble to its computed place (e.g. the keyboard opened or closed), unless the
     * user is dragging it. Height changes slide instead of jumping.
     */
    private fun placeBubble() {
        val lp = params ?: return
        if (!attached || dragging) return
        val fromY = moveAnimator?.takeIf { it.isRunning }?.animatedValue as? Int ?: lp.y
        val oldX = lp.x; val oldGravity = lp.gravity
        computePosition(lp)
        val toY = lp.y
        val running = moveAnimator?.isRunning == true
        if (lp.x == oldX && lp.gravity == oldGravity && (toY == fromY || (running && toY == moveTarget))) {
            // Already there, or already sliding there: let the running slide finish.
            if (running) lp.y = fromY
            return
        }
        moveAnimator?.cancel()
        if (lp.x != oldX || lp.gravity != oldGravity || toY == fromY) {
            windowManager.updateViewLayout(view, lp)
            return
        }
        lp.y = fromY
        moveTarget = toY
        moveAnimator = ValueAnimator.ofInt(fromY, toY).apply {
            duration = MOVE_ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                if (!attached || dragging) return@addUpdateListener
                lp.y = it.animatedValue as Int
                windowManager.updateViewLayout(view, lp)
            }
            start()
        }
    }

    /**
     * Docked side → gravity. Height: the one remembered position, the same with or without a
     * keyboard; an open keyboard only pushes the bubble up when it would cover it.
     */
    private fun computePosition(lp: WindowManager.LayoutParams) {
        val bounds = windowManager.currentWindowMetrics.bounds
        val windowSize = windowSizePx()
        // Physical sides: START/END would flip in RTL locales and break the drag maths.
        lp.gravity = Gravity.TOP or if (ui.dockedRight) Gravity.RIGHT else Gravity.LEFT
        lp.x = dp(EDGE_MARGIN_DP)
        val maxY = (bounds.height() - windowSize).coerceAtLeast(0)
        var y = prefs().getInt(PREF_Y, (bounds.height() * DEFAULT_Y_FRACTION).toInt()).coerceIn(0, maxY)
        val kbTop = keyboardTop
        if (kbTop != null) y = minOf(y, kbTop - windowSize - dp(KEYBOARD_GAP_DP))
        lp.y = y.coerceIn(0, maxY)
    }

    private fun moveBy(dx: Int, dy: Int) {
        val lp = params ?: return
        if (!attached) return
        if (!dragging) {
            dragging = true
            moveAnimator?.cancel()
            // Switch to absolute left-based coordinates for the drag.
            val width = windowManager.currentWindowMetrics.bounds.width()
            val v = view ?: return
            if (lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.RIGHT) lp.x = width - lp.x - v.width
            lp.gravity = Gravity.TOP or Gravity.LEFT
        }
        val bounds = windowManager.currentWindowMetrics.bounds
        lp.x = (lp.x + dx).coerceIn(0, (bounds.width() - (view?.width ?: 0)).coerceAtLeast(0))
        lp.y = (lp.y + dy).coerceIn(0, (bounds.height() - (view?.height ?: 0)).coerceAtLeast(0))
        windowManager.updateViewLayout(view, lp)
    }

    /** Snaps to the nearer side and remembers the side and the height. */
    private fun dropBubble() {
        val lp = params ?: return
        val v = view ?: return
        dragging = false
        val width = windowManager.currentWindowMetrics.bounds.width()
        ui.dockedRight = lp.x + v.width / 2 >= width / 2
        val editor = prefs().edit().putBoolean(PREF_RIGHT, ui.dockedRight)
        // One position for both states; a drop onto the keyboard lands just above it.
        val kbTop = keyboardTop
        val y = if (kbTop != null) minOf(lp.y, kbTop - windowSizePx() - dp(KEYBOARD_GAP_DP)) else lp.y
        editor.putInt(PREF_Y, y.coerceAtLeast(0))
        editor.apply()
        v.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
        computePosition(lp)
        if (attached) windowManager.updateViewLayout(v, lp)
    }

    /** Height (and idle width) of the overlay window: the bubble plus its shadow padding. */
    private fun windowSizePx(): Int = view?.height?.takeIf { it > 0 }
        ?: dp((BUBBLE_SIZE + BUBBLE_WINDOW_PADDING * 2).value.toInt())

    // ── Dictation ─────────────────────────────────────────────────────────────────────────────

    private fun startDictation() {
        if (session != null) return
        if (!PermissionHelper.hasRecordPermission(this)) {
            toast(R.string.bubble_need_permission)
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        // Right after the keyboard opened, the last field an event came from is the best guess
        // when the focus query does not return it (virtual text fields, see [lastEditable]).
        val field = findEditableFocus()
            ?: lastEditable?.takeIf { keyboardTop != null && isStillVisible(it) && isUsableField(it) }
        if (field == null) {
            toast(R.string.bubble_no_field)
            return
        }
        target = field
        // Hold the microphone foreground-service state while dictating from the bubble (see
        // InferenceService.keepLoadedServiceTypes).
        if (keepModelLoaded) InferenceService.startKeepLoaded(this)

        view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        ui.resetLevels()
        ui.modelLoading = false
        ui.phase = BubblePhase.Listening
        // Long dictations must not be cut off by the screen timeout locking the phone.
        view?.keepScreenOn = true
        session = RecognitionSession(
            audioContext = this,
            inference = inference,
            options = RecognitionOptions(partialResults = false, segmented = true),
            listener = Listener(),
            scope = scope,
        ).also { it.start() }
    }

    private fun finishDictation() {
        val running = session ?: return
        view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        ui.phase = BubblePhase.Finishing
        running.stop()
    }

    private fun cancelDictation() {
        view?.performHapticFeedback(HapticFeedbackConstants.REJECT)
        session?.cancel()
        endSession()
    }

    private fun endSession() {
        view?.keepScreenOn = false
        session = null
        target = null
        ui.phase = BubblePhase.Idle
        ui.modelLoading = false
        updateBubble()
    }

    private inner class Listener : RecognitionSession.Listener {
        override fun onModelLoading(loading: Boolean) {
            ui.modelLoading = loading
        }

        override fun onReadyForSpeech() = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray) = Unit
        override fun onLanguageDetection(detection: LanguageDetection) = Unit
        override fun onPartialResult(text: String) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            ui.pushLevel((rmsdB + 2f) / 12f)
        }

        override fun onSegmentResult(text: String, confidence: Float, words: List<WordTiming>) {
            insert(text)
        }

        override fun onResult(text: String, confidence: Float, words: List<WordTiming>) {
            insert(text)
            endSession()
        }

        override fun onEndOfSegmentedSession() = endSession()

        override fun onError(error: Int, microphoneBusy: Boolean) {
            Log.w(TAG, "Recognition error $error" + if (microphoneBusy) " (microphone busy)" else "")
            when {
                microphoneBusy -> toast(R.string.status_error_mic_busy)
                error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Unit
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> toast(R.string.voice_input_error_no_model)
                error == SpeechRecognizer.ERROR_AUDIO -> toast(R.string.voice_input_error_audio)
                else -> toast(R.string.voice_input_error_generic)
            }
            endSession()
        }
    }

    // ── Text insertion ────────────────────────────────────────────────────────────────────────

    private fun insert(transcript: String) {
        if (transcript.isBlank()) return
        // Never switch to whatever field has focus now: that could be another chat.
        val node = target?.takeIf { it.refresh() }
        if (node != null && (setText(node, transcript) || paste(node, transcript))) return
        // The field takes neither direct insertion nor paste. Copy this segment and stop the
        // session: a later segment would replace the clipboard and lose this sentence.
        copyToClipboard(transcript.trim())
        toast(R.string.bubble_copied)
        session?.cancel()
        endSession()
    }

    /**
     * The field's real text, with a placeholder read as empty. Some apps surface their
     * placeholder through [AccessibilityNodeInfo.getText] while the field is empty, without
     * setting [AccessibilityNodeInfo.isShowingHintText], so it would otherwise be spliced in as
     * existing text. WhatsApp's empty compose box, for instance, reports `text="Message"` with
     * no hint and no caret, and a transcript came out as "Message hello".
     *
     * A focused editable field with real content always reports a caret, so non-empty text with
     * no text selection is a leaked placeholder, not content. Hint equality is deliberately not
     * used: a field may legitimately hold text equal to its hint (e.g. a typed "Search").
     */
    private fun fieldText(node: AccessibilityNodeInfo): CharSequence {
        val text = node.text
        if (node.isShowingHintText || text.isNullOrEmpty()) return ""
        if (node.textSelectionStart < 0 && node.textSelectionEnd < 0) return ""
        return text
    }

    /** Splices [transcript] in at the field's selection. `false` if the field refused. */
    private fun setText(node: AccessibilityNodeInfo, transcript: String): Boolean {
        if (!node.actionList.contains(AccessibilityAction.ACTION_SET_TEXT)) return false
        val current: CharSequence = fieldText(node)
        val splice = spliceTranscript(current, node.textSelectionStart, node.textSelectionEnd, transcript)
        val textArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, splice.text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, textArgs)) return false
        val selectionArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, splice.cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, splice.cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
        return true
    }

    /** Pastes [transcript] at the cursor through the clipboard. `false` if the field refused. */
    private fun paste(node: AccessibilityNodeInfo, transcript: String): Boolean {
        if (!node.actionList.contains(AccessibilityAction.ACTION_PASTE)) return false
        val current: CharSequence = fieldText(node)
        copyToClipboard(pasteText(current, node.textSelectionStart, node.textSelectionEnd, transcript))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
