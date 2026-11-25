package org.schabi.parakeetype.notes

import android.content.Context
import androidx.core.content.edit

private const val PREFS_NAME = "voice_notes"
private const val KEY_ENABLED_AT = "enabled_at"
private const val KEY_DONE = "done_files"
private const val SEEN_PREFIX = "seen:"

/** How many transcribed files are remembered, and how many conversations. */
private const val MAX_ENTRIES = 50

/**
 * What [VoiceNoteListenerService] has already handled, kept across process death so a
 * re-posted notification never transcribes a voice note twice.
 *
 * A plain SharedPreferences file, not the settings DataStore: backups only include
 * `datastore/`, and this bookkeeping shouldn't move to another phone.
 */
class VoiceNoteStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * When the listener first ran. Conversations it hasn't seen before only count messages
     * newer than this, so granting access doesn't transcribe the backlog.
     */
    fun enabledAtMs(): Long {
        val stored = prefs.getLong(KEY_ENABLED_AT, 0L)
        if (stored != 0L) return stored
        val now = System.currentTimeMillis()
        prefs.edit { putLong(KEY_ENABLED_AT, now) }
        return now
    }

    /** Timestamp of the newest message handled in [conversation], or [enabledAtMs]. */
    fun seenUntilMs(conversation: String): Long =
        prefs.getLong(SEEN_PREFIX + conversation, 0L).takeIf { it != 0L } ?: enabledAtMs()

    fun setSeenUntil(conversation: String, timeMs: Long) {
        val key = SEEN_PREFIX + conversation
        val seen = prefs.all.keys.filter { it.startsWith(SEEN_PREFIX) && it != key }
        prefs.edit {
            putLong(key, timeMs)
            // Forget the stalest conversations; they fall back to enabledAtMs, which is older.
            if (seen.size >= MAX_ENTRIES) {
                seen.sortedBy { prefs.getLong(it, 0L) }
                    .take(seen.size - MAX_ENTRIES + 1)
                    .forEach { remove(it) }
            }
        }
    }

    fun doneFiles(): Set<String> =
        prefs.getString(KEY_DONE, null)?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    fun markDone(path: String) {
        val done = (prefs.getString(KEY_DONE, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()) + path
        prefs.edit { putString(KEY_DONE, done.takeLast(MAX_ENTRIES).joinToString("\n")) }
    }
}
