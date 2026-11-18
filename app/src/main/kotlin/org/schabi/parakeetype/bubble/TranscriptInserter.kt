package org.schabi.parakeetype.bubble

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import org.schabi.parakeetype.R

/**
 * Puts a transcript into another app's text field: spliced in at the selection with
 * [AccessibilityNodeInfo.ACTION_SET_TEXT], or, where the field refuses that, pasted through the
 * clipboard with [AccessibilityNodeInfo.ACTION_PASTE].
 */
class TranscriptInserter(private val context: Context) {

    /** Inserts [transcript] into [node]. `false` if the field takes neither way. */
    fun insert(node: AccessibilityNodeInfo, transcript: String): Boolean =
        setText(node, transcript) || paste(node, transcript)

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
    }

    private fun currentText(node: AccessibilityNodeInfo): CharSequence =
        fieldText(node.text, node.isShowingHintText, node.textSelectionStart, node.textSelectionEnd)

    private fun setText(node: AccessibilityNodeInfo, transcript: String): Boolean {
        if (!node.actionList.contains(AccessibilityAction.ACTION_SET_TEXT)) return false
        val splice = spliceTranscript(currentText(node), node.textSelectionStart, node.textSelectionEnd, transcript)
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

    private fun paste(node: AccessibilityNodeInfo, transcript: String): Boolean {
        if (!node.actionList.contains(AccessibilityAction.ACTION_PASTE)) return false
        copyToClipboard(pasteText(currentText(node), node.textSelectionStart, node.textSelectionEnd, transcript))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }
}
