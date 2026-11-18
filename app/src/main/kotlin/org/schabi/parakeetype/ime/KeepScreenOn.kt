package org.schabi.parakeetype.ime

import android.view.Window
import android.view.WindowManager
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.schabi.parakeetype.ui.keyboard.KeyboardUiState

/** Listening, or still turning speech into text. */
internal val KeyboardUiState.isDictating: Boolean
    get() = this is KeyboardUiState.Listening ||
            this is KeyboardUiState.Processing ||
            this is KeyboardUiState.Transcribing

/**
 * Keeps the screen on while the keyboard [isDictating], so the screen timeout cannot lock the
 * phone in the middle of a long dictation. [window] is looked up on every state: the keyboard's
 * window can be replaced while the service lives.
 */
internal fun LifecycleOwner.keepScreenOnWhileDictating(uiState: Flow<KeyboardUiState>, window: () -> Window?) {
    lifecycleScope.launch {
        uiState.collect { state ->
            val win = window() ?: return@collect
            if (state.isDictating) win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else win.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
