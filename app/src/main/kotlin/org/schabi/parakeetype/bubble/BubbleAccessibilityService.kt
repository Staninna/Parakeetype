package org.schabi.parakeetype.bubble

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

/**
 * Shows a floating microphone bubble on focused text fields while another keyboard is in use,
 * so dictation works without switching to the Parakeetype keyboard.
 *
 * [FieldFinder] decides where the bubble belongs, [BubbleWindow] draws and places it. Tapping
 * it starts a segmented [RecognitionSession] (the same pipeline and loaded model as the
 * keyboard and the system recognizer) and turns it into a recording pill with a live waveform,
 * cancel and done. Each finished utterance goes into the field that was focused when dictation
 * started, through [TranscriptInserter]. Nothing leaves the device.
 */
class BubbleAccessibilityService : AccessibilityService() {

    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = Runnable { updateBubble() }
    private val settleRunnable = Runnable { updateBubble() }

    /** Since when the checks have said "hide" (uptime ms), or `null` while they say "show". */
    private var hideSince: Long? = null

    private val ui = BubbleUiState()
    private lateinit var finder: FieldFinder
    private lateinit var window: BubbleWindow
    private lateinit var inserter: TranscriptInserter
    private lateinit var inference: InferenceConnection
    private lateinit var mediaPause: MediaPause

    private var session: RecognitionSession? = null

    /** The field dictation started in; utterances go here even if focus moves meanwhile. */
    private var target: AccessibilityNodeInfo? = null

    private var keepModelLoaded = true

    override fun onServiceConnected() {
        super.onServiceConnected()
        finder = FieldFinder(this)
        inserter = TranscriptInserter(this)
        inference = InferenceConnection(this)
        mediaPause = MediaPause(this)
        window = BubbleWindow(this, ui) {
            ParakeetypeKeyboardTheme {
                BubbleContent(
                    state = ui,
                    onStart = ::startDictation,
                    onFinish = ::finishDictation,
                    onCancel = ::cancelDictation,
                )
            }
        }
        scope.launch {
            AppPreferences(applicationContext).keepModelLoaded.collect {
                keepModelLoaded = it
                // Turned off while the bubble is hidden: release the model now, not on next hide.
                if (!it && !window.attached && session == null) inference.unbind()
            }
        }
        updateBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) finder.remember(event)
        scheduleUpdate()
    }

    private fun scheduleUpdate() {
        if (!handler.hasCallbacks(updateRunnable)) handler.postDelayed(updateRunnable, UPDATE_DELAY_MS)
        handler.removeCallbacks(settleRunnable)
        handler.postDelayed(settleRunnable, SETTLE_DELAY_MS)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session?.cancel()
        session = null
        if (::window.isInitialized) window.destroy()
        if (::inference.isInitialized) inference.unbind()
        if (::mediaPause.isInitialized) mediaPause.resume()
        scope.cancel()
        super.onDestroy()
    }

    // ── Visibility ────────────────────────────────────────────────────────────────────────────

    private fun updateBubble() {
        val keyboard = finder.keyboard()
        window.keyboardTop = keyboard.top
        if (session != null) {
            // Navigating away mid-dictation (no text field focused any more) would otherwise
            // leave this segmented session recording with no silence timeout; cancel it.
            // Otherwise keep the bubble and follow the keyboard (if any) while dictating.
            val stillThere = finder.editableFocus() != null || target?.let { finder.isStillVisible(it) } == true
            if (stillThere) {
                hideSince = null
                window.place()
            } else if (confirmHide()) {
                cancelDictation()
            }
            return
        }
        val show = when {
            // Our own keyboard has its own talk button.
            keyboard.isOwn -> false
            // Never offer dictation into a password field, nor over Parakeetype itself.
            finder.focusIsPasswordOrOwn() -> false
            // Show on any focused field, also when no keyboard is up (e.g. after Back dismissed
            // it); without a keyboard the bubble sits at its remembered free height.
            finder.editableFocus() != null -> true
            // Another keyboard is up, so the user is typing somewhere, even when the field
            // could not be found (yet): the bubble belongs there.
            else -> keyboard.top != null
        }
        if (show) {
            hideSince = null
            showBubble()
        } else if (!window.attached || keyboard.isOwn || confirmHide()) {
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

    private fun showBubble() {
        if (window.attached) {
            window.place()
        } else if (window.attach()) {
            // Start loading the model now, so it is ready (or nearly) by the time of the tap.
            inference.bind()
        }
    }

    private fun hideBubble() {
        if (!window.attached) return
        window.remove()
        // Without "keep model loaded" the model is released together with the bubble.
        if (!keepModelLoaded) inference.unbind()
    }

    // ── Dictation ─────────────────────────────────────────────────────────────────────────────

    private fun startDictation() {
        if (session != null) return
        if (!PermissionHelper.hasRecordPermission(this)) {
            toast(R.string.bubble_need_permission)
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        // Right after the keyboard opened, the last field an event came from is the best guess
        // when the focus query does not return it (virtual text fields).
        val field = finder.editableFocus()
            ?: if (window.keyboardTop != null) finder.recentEditable() else null
        if (field == null) {
            toast(R.string.bubble_no_field)
            return
        }
        target = field
        // Hold the microphone foreground-service state while dictating from the bubble (see
        // InferenceService.keepLoadedServiceTypes).
        if (keepModelLoaded) InferenceService.startKeepLoaded(this)

        window.view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        ui.resetLevels()
        ui.modelLoading = false
        ui.phase = BubblePhase.Listening
        // Long dictations must not be cut off by the screen timeout locking the phone.
        window.view.keepScreenOn = true
        // Music playing meanwhile would be transcribed along with the user.
        mediaPause.pause()
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
        window.view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        ui.phase = BubblePhase.Finishing
        running.stop()
    }

    private fun cancelDictation() {
        window.view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        session?.cancel()
        endSession()
    }

    private fun endSession() {
        window.view.keepScreenOn = false
        mediaPause.resume()
        session = null
        target = null
        ui.phase = BubblePhase.Idle
        ui.modelLoading = false
        updateBubble()
    }

    /** Inserts one finished utterance into [target]. */
    private fun insert(transcript: String) {
        if (transcript.isBlank()) return
        // Never switch to whatever field has focus now: that could be another chat.
        val node = target?.takeIf { it.refresh() }
        if (node != null && inserter.insert(node, transcript)) return
        // The field takes neither direct insertion nor paste. Copy this segment and stop the
        // session: a later segment would replace the clipboard and lose this sentence.
        inserter.copyToClipboard(transcript.trim())
        toast(R.string.bubble_copied)
        session?.cancel()
        endSession()
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

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** Whether the user has switched the bubble's accessibility service on. */
        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, BubbleAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
        }
    }
}
