package io.github.danielperezmartinez.titanssh.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FailureCauseTest {

    @Test
    fun lists_each_cause_below_the_failure_one_per_line() {
        val root = java.net.NoRouteToHostException("No route to host")
        val transport = java.io.IOException("Connection setup failed", root)
        val failure = RuntimeException("Could not connect to x:22", transport)

        assertEquals(
            "IOException: Connection setup failed\nNoRouteToHostException: No route to host",
            failureCause(failure),
        )
    }

    @Test
    fun a_failure_without_a_cause_has_nothing_to_unfold() {
        assertNull(failureCause(RuntimeException("Could not connect to x:22")))
    }

    @Test
    fun include_self_starts_with_the_failure_itself() {
        assertEquals("IllegalStateException", failureCause(IllegalStateException(), includeSelf = true))
    }

    @Test
    fun repeated_lines_and_cycles_are_shown_once() {
        val inner = java.io.IOException("reset")
        val wrapper = java.io.IOException("reset", inner)
        val failure = RuntimeException("x", wrapper)
        assertEquals("IOException: reset", failureCause(failure))

        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertEquals("RuntimeException: b\nRuntimeException: a", failureCause(RuntimeException("top", b)))
    }
}
