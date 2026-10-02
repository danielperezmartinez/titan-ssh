package io.github.danielperezmartinez.titanssh.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.transport.TransportException

/** The algorithms the engine offers, as sshj will put them in its KEXINIT. */
class SshjConfigTest {

    private fun names(factories: List<Factory.Named<*>>) = factories.map { it.name }

    @Test
    fun offers_exactly_the_listed_algorithms_in_order() {
        val config = sshjConfig(keepAlive = false)

        // Every name must exist in sshj: a typo would silently drop an algorithm.
        assertEquals(SshAlgorithms.keyExchange, names(config.keyExchangeFactories))
        assertEquals(SshAlgorithms.keys, names(config.keyAlgorithms))
        assertEquals(SshAlgorithms.ciphers, names(config.cipherFactories))
        assertEquals(SshAlgorithms.macs, names(config.macFactories))
        assertEquals(listOf("none"), names(config.compressionFactories))
    }

    @Test
    fun keepalive_is_only_set_when_asked() {
        assertEquals(KeepAliveProvider.KEEP_ALIVE, sshjConfig(keepAlive = true).keepAliveProvider)
        assertEquals(KeepAliveProvider.HEARTBEAT, sshjConfig(keepAlive = false).keepAliveProvider)
    }

    @Test
    fun reads_the_kind_of_a_failed_negotiation() {
        fun settlement(list: String) =
            RuntimeException("connect failed", TransportException("Unable to reach a settlement of $list: [a] and [b]"))

        assertEquals(SshAlgorithmKind.KEY_EXCHANGE, unnegotiatedAlgorithm(settlement("KeyExchangeAlgorithms")))
        assertEquals(SshAlgorithmKind.HOST_KEY, unnegotiatedAlgorithm(settlement("HostKeyAlgorithms")))
        assertEquals(SshAlgorithmKind.CIPHER, unnegotiatedAlgorithm(settlement("Server2ClientCipherAlgorithms")))
        assertEquals(SshAlgorithmKind.MAC, unnegotiatedAlgorithm(settlement("Client2ServerMACAlgorithms")))
        assertEquals(SshAlgorithmKind.COMPRESSION, unnegotiatedAlgorithm(settlement("Client2ServerCompressionAlgorithms")))
        assertNull(unnegotiatedAlgorithm(TransportException("Broken transport; encountered EOF")))
    }
}
