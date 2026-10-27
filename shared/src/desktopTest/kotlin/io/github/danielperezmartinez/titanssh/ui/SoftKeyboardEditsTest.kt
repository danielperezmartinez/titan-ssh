package io.github.danielperezmartinez.titanssh.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SoftKeyboardEditsTest {

    private val anchor = "​"

    /** Feeds [texts] as successive field values and returns what would reach the remote. */
    private fun typed(edits: SoftKeyboardEdits, vararg texts: String): String = buildString {
        texts.forEach { text ->
            val edit = edits.change(text)
            repeat(edit.backspaces) { append('⌫') }
            append(edit.added)
        }
    }

    @Test
    fun keys_that_arrive_before_the_field_is_updated_are_sent_once() {
        // Each change builds on the previous one, as when key events outrun
        // recomposition: the old reset-per-key field sent "eececho" here.
        val edits = SoftKeyboardEdits()
        assertEquals("echo", typed(edits, "${anchor}e", "${anchor}ec", "${anchor}ech", "${anchor}echo"))
    }

    @Test
    fun a_composing_word_that_is_replaced_becomes_backspaces_and_the_new_word() {
        val edits = SoftKeyboardEdits()
        assertEquals("teh⌫⌫he", typed(edits, "${anchor}t", "${anchor}te", "${anchor}teh", "${anchor}the"))
    }

    @Test
    fun deleting_the_anchor_is_one_backspace_and_resets_the_field() {
        val edits = SoftKeyboardEdits()
        val edit = edits.change("")
        assertEquals(SoftKeyboardEdits.Edit(1, "", reset = true, resetNow = true), edit)
        assertEquals("a", typed(edits, "${anchor}a"))
    }

    @Test
    fun a_line_break_leaves_a_reset_due_and_a_key_built_on_the_old_text_is_not_repeated() {
        val edits = SoftKeyboardEdits()
        assertEquals("ls", typed(edits, "${anchor}l", "${anchor}ls"))
        val enter = edits.change("${anchor}ls\n")
        assertEquals(SoftKeyboardEdits.Edit(0, "\n", reset = true), enter)
        // Keys typed before the keyboard went quiet build on the kept text.
        assertEquals("x", typed(edits, "${anchor}ls\nx"))
        edits.reset()
        // A key event processed before the reset reached the field: the field
        // takes its text again, and the next key builds on it.
        assertEquals("z", typed(edits, "${anchor}ls\nxz"))
        assertEquals("y", typed(edits, "${anchor}ls\nxzy"))
        edits.reset()
        // And the next one, on the field as it should be again.
        assertEquals("w", typed(edits, "${anchor}w"))
    }

    @Test
    fun a_long_line_resets_the_field_without_losing_or_repeating_text() {
        val edits = SoftKeyboardEdits()
        val line = "a".repeat(70)
        val sent = typed(edits, *(1..line.length).map { anchor + line.take(it) }.toTypedArray())
        assertEquals(line, sent)
        assertEquals(true, edits.change(anchor + line + "b").reset)
        edits.reset()
        assertEquals("c", typed(edits, "${anchor}c"))
    }

    @Test
    fun the_anchor_never_reaches_the_remote() {
        val edits = SoftKeyboardEdits()
        edits.change("")
        assertEquals("q", typed(edits, "${anchor}q"))
    }
}
