package io.github.danielperezmartinez.titanssh.config

import kotlin.test.Test
import kotlin.test.assertTrue

class RdpSessionOfferTest {

    @Test
    fun rdp_is_experimental() {
        assertTrue(SessionType.RDP.experimental)
    }
}
