package org.schabi.parakeetype.bubble

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class EditHistoryTest {

    private val first = FieldEdit("", 0, "Hello", 5)
    private val second = FieldEdit("Hello", 5, "Hello world", 11)

    @Test
    fun `undo and redo step through the edits`() {
        val history = EditHistory()
        history.record(first)
        history.record(second)

        assertThat(history.undo("Hello world")).isEqualTo(second)
        assertThat(history.undo("Hello")).isEqualTo(first)
        assertThat(history.canUndo).isFalse()
        assertThat(history.redo("")).isEqualTo(first)
        assertThat(history.redo("Hello")).isEqualTo(second)
        assertThat(history.canRedo).isFalse()
        assertThat(history.undo("Hello world")).isEqualTo(second)
    }

    @Test
    fun `a changed field drops the history instead of overwriting it`() {
        val history = EditHistory()
        history.record(first)

        assertThat(history.undo("Hello!")).isNull()
        assertThat(history.canUndo).isFalse()

        history.record(first)
        history.undo("Hello")
        assertThat(history.redo("typed")).isNull()
        assertThat(history.canRedo).isFalse()
    }

    @Test
    fun `a new edit clears the redo history`() {
        val history = EditHistory()
        history.record(first)
        history.undo("Hello")
        history.record(FieldEdit("", 0, "Bye", 3))

        assertThat(history.canRedo).isFalse()
    }

    @Test
    fun `only the newest edits are kept`() {
        val history = EditHistory(maxEdits = 1)
        history.record(first)
        history.record(second)

        assertThat(history.undo("Hello world")).isEqualTo(second)
        assertThat(history.canUndo).isFalse()
    }
}
