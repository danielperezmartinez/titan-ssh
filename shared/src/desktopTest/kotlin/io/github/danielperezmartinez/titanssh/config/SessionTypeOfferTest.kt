package io.github.danielperezmartinez.titanssh.config

import io.github.danielperezmartinez.titanssh.AppChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SessionTypeOfferTest {

    @Test
    fun the_settled_types_are_never_experimental() {
        listOf(SessionType.TERMINAL, SessionType.MOUSEPAD, SessionType.SFTP, SessionType.DESKTOP).forEach {
            assertFalse(it.experimental, it.name)
        }
    }

    @Test
    fun an_experimental_type_is_offered_only_in_the_canary() {
        SessionType.entries.forEach {
            assertEquals(!it.experimental || AppChannel.isCanary, it.offered, it.name)
        }
    }
}
