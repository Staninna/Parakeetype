package org.schabi.parakeetype.bubble

/**
 * One insertion into a field: its whole text and cursor before and after. The bubble writes
 * fields with `ACTION_SET_TEXT` (the whole text at once), so undo and redo simply write the
 * other side back.
 */
data class FieldEdit(
    val before: String,
    val beforeCursor: Int,
    val after: String,
    val afterCursor: Int,
)

/**
 * Undo / redo history of the bubble's insertions into one field. An edit is only undone while
 * the field still reads exactly [FieldEdit.after] (and redone while it reads
 * [FieldEdit.before]); otherwise the user changed the field meanwhile, and the whole history is
 * dropped instead of overwriting their change.
 */
class EditHistory(private val maxEdits: Int = MAX_EDITS) {
    private val done = ArrayDeque<FieldEdit>()
    private val undone = ArrayDeque<FieldEdit>()

    val canUndo: Boolean get() = done.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()

    /** A new insertion; it makes the undone edits stale, as in any editor. */
    fun record(edit: FieldEdit) {
        undone.clear()
        done.addLast(edit)
        while (done.size > maxEdits) done.removeFirstOrNull()
    }

    /** The edit to undo while the field reads [current], or `null` (history dropped on a mismatch). */
    fun undo(current: String): FieldEdit? {
        val edit = done.lastOrNull() ?: return null
        if (current != edit.after) {
            clear()
            return null
        }
        done.removeLastOrNull()
        undone.addLast(edit)
        return edit
    }

    /** The edit to redo while the field reads [current], or `null` (history dropped on a mismatch). */
    fun redo(current: String): FieldEdit? {
        val edit = undone.lastOrNull() ?: return null
        if (current != edit.before) {
            clear()
            return null
        }
        undone.removeLastOrNull()
        done.addLast(edit)
        return edit
    }

    fun clear() {
        done.clear()
        undone.clear()
    }

    private companion object {
        const val MAX_EDITS = 20
    }
}
