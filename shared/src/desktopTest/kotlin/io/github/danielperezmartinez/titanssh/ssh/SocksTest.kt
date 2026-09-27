package io.github.danielperezmartinez.titanssh.ssh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The SOCKS handshake of the dynamic tunnel ([[Ejecutar los túneles de las sesiones]]). */
class SocksTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun read(input: ByteArray): Pair<Socks.Request, ByteArray> {
        val out = ByteArrayOutputStream()
        return Socks.readRequest(ByteArrayInputStream(input), out) to out.toByteArray()
    }

    @Test
    fun socks5_connect_to_a_domain() {
        val host = "db.internal".toByteArray()
        val input = bytes(5, 1, 0) + bytes(5, 1, 0, 3, host.size) + host + bytes(0x15, 0x38)
        val (request, answer) = read(input)
        assertEquals(Socks.Request(5, "db.internal", 5432), request)
        assertContentEquals(bytes(5, 0), answer, "no-authentication method chosen")

        val reply = ByteArrayOutputStream()
        Socks.replySuccess(reply, request)
        assertContentEquals(bytes(5, 0, 0, 1, 0, 0, 0, 0, 0, 0), reply.toByteArray())
    }

    @Test
    fun socks5_connect_to_ipv4_and_ipv6() {
        val (v4, _) = read(bytes(5, 2, 2, 0) + bytes(5, 1, 0, 1, 10, 0, 0, 7, 0, 80))
        assertEquals(Socks.Request(5, "10.0.0.7", 80), v4)

        val loopback6 = ByteArray(16).also { it[15] = 1 }
        val (v6, _) = read(bytes(5, 1, 0) + bytes(5, 1, 0, 4) + loopback6 + bytes(0, 22))
        assertEquals(22, v6.port)
        assertEquals("0:0:0:0:0:0:0:1", v6.host)
    }

    @Test
    fun socks5_without_no_auth_is_refused() {
        val out = ByteArrayOutputStream()
        assertFailsWith<Socks.Unsupported> {
            Socks.readRequest(ByteArrayInputStream(bytes(5, 1, 2)), out)
        }
        assertContentEquals(bytes(5, 0xFF), out.toByteArray())
    }

    @Test
    fun socks5_bind_is_not_supported() {
        val out = ByteArrayOutputStream()
        assertFailsWith<Socks.Unsupported> {
            Socks.readRequest(ByteArrayInputStream(bytes(5, 1, 0) + bytes(5, 2, 0, 1, 127, 0, 0, 1, 0, 80)), out)
        }
        val answer = out.toByteArray()
        assertEquals(Socks.V5_COMMAND_UNSUPPORTED, answer[3].toInt(), "reply after the method choice")
    }

    @Test
    fun socks5_failure_codes() {
        val request = Socks.Request(5, "x", 1)
        fun code(kind: ForwardProblem.Kind): Int =
            ByteArrayOutputStream().also { Socks.replyFailure(it, request, kind) }.toByteArray()[1].toInt()
        assertEquals(Socks.V5_NOT_ALLOWED, code(ForwardProblem.Kind.PROHIBITED))
        assertEquals(Socks.V5_REFUSED, code(ForwardProblem.Kind.UNREACHABLE))
        assertEquals(Socks.V5_FAILURE, code(ForwardProblem.Kind.OTHER))
    }

    @Test
    fun socks4_and_4a() {
        val (v4, _) = read(bytes(4, 1, 0, 80, 192, 168, 1, 2) + "user".toByteArray() + bytes(0))
        assertEquals(Socks.Request(4, "192.168.1.2", 80), v4)

        val (v4a, _) = read(bytes(4, 1, 0x1F, 0x90, 0, 0, 0, 1, 0) + "example.org".toByteArray() + bytes(0))
        assertEquals(Socks.Request(4, "example.org", 8080), v4a)

        val reply = ByteArrayOutputStream()
        Socks.replyFailure(reply, v4a, ForwardProblem.Kind.UNREACHABLE)
        assertContentEquals(bytes(0, Socks.V4_REJECTED, 0, 0, 0, 0, 0, 0), reply.toByteArray())
    }

    @Test
    fun a_non_socks_client_is_reported() {
        assertFailsWith<Socks.Unsupported> { read("GET / HTTP/1.1\r\n".toByteArray()) }
    }

    @Test
    fun bind_failures_are_classified_by_message() {
        assertEquals(ForwardFailure.PORT_IN_USE, bindFailure("Address already in use: bind"))
        assertEquals(ForwardFailure.PORT_IN_USE, bindFailure("Address already in use"))
        assertEquals(ForwardFailure.PERMISSION_DENIED, bindFailure("Permission denied"))
        assertEquals(
            ForwardFailure.PERMISSION_DENIED,
            bindFailure("An attempt was made to access a socket in a way forbidden by its access permissions"),
        )
        assertEquals(ForwardFailure.BAD_ADDRESS, bindFailure("Cannot assign requested address: bind"))
        assertEquals(ForwardFailure.OTHER, bindFailure("something else"))
    }
}
