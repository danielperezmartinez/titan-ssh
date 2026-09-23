package io.github.danielperezmartinez.titanssh.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabListTest {

    @Test
    fun add_appends_and_activates() {
        val list = TabList().add("a").add("b").add("c")
        assertEquals(listOf("a", "b", "c"), list.order)
        assertEquals("c", list.activeId)
    }

    @Test
    fun add_duplicate_only_activates() {
        val list = TabList().add("a").add("b").add("a")
        assertEquals(listOf("a", "b"), list.order)
        assertEquals("a", list.activeId)
    }

    @Test
    fun activate_ignores_unknown_id() {
        val list = TabList().add("a").add("b")
        assertEquals("b", list.activate("zzz").activeId)
        assertEquals("a", list.activate("a").activeId)
    }

    @Test
    fun removing_active_selects_right_neighbour() {
        // active is "b"; removing it should focus "c" (the tab to its right).
        val list = TabList().add("a").add("b").add("c").activate("b")
        val next = list.remove("b")
        assertEquals(listOf("a", "c"), next.order)
        assertEquals("c", next.activeId)
    }

    @Test
    fun removing_active_last_selects_new_last() {
        val list = TabList().add("a").add("b").add("c") // active "c"
        val next = list.remove("c")
        assertEquals(listOf("a", "b"), next.order)
        assertEquals("b", next.activeId)
    }

    @Test
    fun removing_non_active_keeps_active() {
        val list = TabList().add("a").add("b").add("c").activate("c")
        val next = list.remove("a")
        assertEquals(listOf("b", "c"), next.order)
        assertEquals("c", next.activeId)
    }

    @Test
    fun removing_last_remaining_clears_active() {
        val next = TabList().add("a").remove("a")
        assertTrue(next.isEmpty)
        assertNull(next.activeId)
    }

    @Test
    fun move_reorders_and_keeps_active() {
        val list = TabList().add("a").add("b").add("c").activate("a")
        val next = list.move(0, 2)
        assertEquals(listOf("b", "c", "a"), next.order)
        assertEquals("a", next.activeId)
    }

    @Test
    fun move_left_and_right_respect_edges() {
        val list = TabList().add("a").add("b").add("c")
        assertEquals(listOf("a", "b", "c"), list.moveLeft("a").order) // already first
        assertEquals(listOf("a", "b", "c"), list.moveRight("c").order) // already last
        assertEquals(listOf("b", "a", "c"), list.moveRight("a").order)
        assertEquals(listOf("a", "c", "b"), list.moveLeft("c").order)
    }
}
