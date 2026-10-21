package io.github.danielperezmartinez.titanssh.rdp

import io.github.danielperezmartinez.titanssh.config.RdpSettings
import io.github.danielperezmartinez.titanssh.config.Session
import io.github.danielperezmartinez.titanssh.config.SessionType
import io.github.danielperezmartinez.titanssh.config.TitanConfig
import io.github.danielperezmartinez.titanssh.terminal.InputKeys
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class RdpModelTest {

    @Test
    fun events_parse_from_the_native_text() {
        assertEquals(RdpEvent.Certificate("ab12", "CN=x"), RdpEvent.parse("certificate\tab12\tCN=x"))
        assertEquals(RdpEvent.Desktop(1280, 800), RdpEvent.parse("desktop\t1280\t800"))
        assertEquals(RdpEvent.Frame, RdpEvent.parse("frame"))
        assertEquals(RdpEvent.Ended("auth", "denied"), RdpEvent.parse("ended\tauth\tdenied"))
        assertNull(RdpEvent.parse("desktop\tx\t1"))
        assertNull(RdpEvent.parse("something new"))
    }

    @Test
    fun keys_map_to_set1_scancodes() {
        assertEquals(0x1E, RdpKeys.scancode(InputKeys.A))
        assertEquals(0x2C, RdpKeys.scancode(InputKeys.Z))
        assertEquals(0x0B, RdpKeys.scancode(InputKeys.DIGIT_0))
        assertEquals(0x02, RdpKeys.scancode(InputKeys.DIGIT_0 + 1))
        assertEquals(0x0A, RdpKeys.scancode(InputKeys.DIGIT_9))
        assertEquals(0x3B, RdpKeys.scancode(InputKeys.function(1)))
        assertEquals(0x44, RdpKeys.scancode(InputKeys.function(10)))
        assertEquals(0x57, RdpKeys.scancode(InputKeys.function(11)))
        assertEquals(0x58, RdpKeys.scancode(InputKeys.F12))
        assertEquals(0xE04B, RdpKeys.scancode(InputKeys.ARROW_LEFT))
        assertEquals(listOf(RdpKeys.CTRL, RdpKeys.ALT), RdpKeys.modifierScancodes(InputKeys.MOD_ALT or InputKeys.MOD_CTRL))
        assertEquals(2, RdpKeys.button(InputKeys.BUTTON_RIGHT))
    }

    @Test
    fun wheel_steps_never_exceed_a_notch() {
        assertEquals(listOf(120, 120, 60), wheelSteps(300))
        assertEquals(listOf(-40), wheelSteps(-40))
        assertEquals(emptyList(), wheelSteps(0))
    }

    @Test
    fun certificates_are_remembered_per_server() = runBlocking {
        val file = File(Files.createTempDirectory("titan-rdp").toFile(), "rdp_certificates")
        val store = FileRdpCertificateStore(file)
        val key = certificateKey("Host.Example", 22, "LOCALHOST", 3389)
        assertEquals("host.example:22/localhost:3389", key)
        assertNull(store.trusted(key))
        store.trust(key, "aa")
        store.trust("other", "bb")
        store.trust(key, "cc")
        assertEquals("cc", FileRdpCertificateStore(file).trusted(key))
        assertEquals("bb", store.trusted("other"))
        assertEquals("AA:BB", formatFingerprint("aabb"))
    }

    @Test
    fun older_configurations_read_rdp_settings_as_defaults() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """{"version":3,"sessions":[{"id":"s","name":"n","hostId":"h","type":"TERMINAL"}]}"""
        val config = json.decodeFromString(TitanConfig.serializer(), old)
        assertEquals(RdpSettings(), config.sessions.single().rdp)
        val rdp = Session(id = "r", name = "pc", hostId = "h", type = SessionType.RDP, rdp = RdpSettings(targetPort = 3390, passwordRef = "pc.rdp"))
        val back = json.decodeFromString(TitanConfig.serializer(), json.encodeToString(TitanConfig.serializer(), TitanConfig(sessions = listOf(rdp))))
        assertEquals(rdp, back.sessions.single())
        assertEquals(TitanConfig.CURRENT_VERSION, back.version)
    }
}
