package io.github.danielperezmartinez.titanssh.terminal

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The screen frames of a remote desktop (ADR-0029): the wire bytes match the
 * agent's (the same hex strings as `screen_test.go`), [RemoteScreen] rebuilds
 * the screen from tiles and publishes it only at the end of a frame, and
 * [ScreenViewport] maps between the view and the screen.
 */
class ScreenFramesTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun frames_match_the_agents_bytes() {
        val golden = listOf(
            AgentFrame.ScreenHello(1, ScreenCodec.QUALITY_AUTO) to "0f0000000201ff",
            AgentFrame.ScreenSize(1280, 800) to "100000000405000320",
            AgentFrame.ScreenTile(64, 128, 64, 32, ScreenCodec.ENCODING_FILL, byteArrayOf(1, 2, 3)) to
                "110000000c004000800040002000010203",
            AgentFrame.ScreenFrameEnd(7) to "120000000400000007",
            AgentFrame.ScreenAck(0x01020304) to "130000000401020304",
            AgentFrame.CursorShape(2, 1, 1, 0, byteArrayOf(9)) to "1400000009000200010001000009",
            AgentFrame.CursorPos(-3, 40, visible = true) to "1500000005fffd002801",
            AgentFrame.PointerTo(300, 2) to "1600000004012c0002",
        )
        for ((frame, wire) in golden) {
            assertEquals(wire, hex(AgentProtocol.encode(frame)), "encode $frame")
            assertEquals(listOf(frame), AgentProtocol.FrameDecoder().feed(unhex(wire)), "decode $wire")
        }
        // Sequence numbers are unsigned 32-bit.
        val big = AgentFrame.ScreenFrameEnd(0xFFFF_FFF0L)
        assertEquals(listOf<AgentFrame>(big), AgentProtocol.FrameDecoder().feed(AgentProtocol.encode(big)))
    }

    /** A surface that records what reached it. */
    private class FakeSurface(override val width: Int, override val height: Int) : ScreenSurface {
        val pixels = IntArray(width * height)
        val writes = mutableListOf<List<Int>>()
        override fun write(pixels: IntArray, x: Int, y: Int, w: Int, h: Int) {
            writes += listOf(x, y, w, h)
            for (row in y until y + h) pixels.copyInto(this.pixels, row * width + x, row * width + x, row * width + x + w)
        }
    }

    private fun zlib(raw: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(raw)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    @Test
    fun remote_screen_applies_tiles_at_the_end_of_a_frame() {
        val surfaces = mutableListOf<FakeSurface>()
        val screen = RemoteScreen { w, h -> FakeSurface(w, h).also { surfaces += it } }
        assertNull(screen.apply(AgentFrame.ScreenSize(8, 4)))
        // FILL the left half red, ZLIB the right half as a gradient.
        screen.apply(AgentFrame.ScreenTile(0, 0, 4, 4, ScreenCodec.ENCODING_FILL, byteArrayOf(0xFF.toByte(), 0, 0)))
        val raw = ByteArray(4 * 4 * 3) { (it * 5).toByte() }
        screen.apply(AgentFrame.ScreenTile(4, 0, 4, 4, ScreenCodec.ENCODING_ZLIB, zlib(raw)))
        // Nothing shown yet: the frame has not ended.
        assertTrue(surfaces.single().writes.isEmpty())
        assertNull(screen.view.value.surface)

        assertEquals(1L, screen.apply(AgentFrame.ScreenFrameEnd(1)))
        val s = surfaces.single()
        assertEquals(listOf(listOf(0, 0, 8, 4)), s.writes)
        assertEquals(0xFFFF0000.toInt(), s.pixels[0])
        val p = 3 * 8 + 5 // row 3, column 5: raw pixel (3*4+1)
        val i = (3 * 4 + 1) * 3
        assertEquals(
            (0xFF shl 24) or ((raw[i].toInt() and 0xFF) shl 16) or ((raw[i + 1].toInt() and 0xFF) shl 8) or (raw[i + 2].toInt() and 0xFF),
            s.pixels[p],
        )
        assertEquals(1L, screen.view.value.frame)
        assertEquals(s, screen.view.value.surface)

        // A later frame writes only the rectangle it touched.
        screen.apply(AgentFrame.ScreenTile(2, 1, 1, 1, ScreenCodec.ENCODING_FILL, byteArrayOf(0, 0, 0x7F)))
        screen.apply(AgentFrame.ScreenFrameEnd(2))
        assertEquals(listOf(2, 1, 1, 1), s.writes.last())
        assertEquals(0xFF00007F.toInt(), s.pixels[1 * 8 + 2])
    }

    @Test
    fun remote_screen_decodes_jpeg_tiles_and_the_cursor() {
        val screen = RemoteScreen { w, h -> FakeSurface(w, h) }
        screen.apply(AgentFrame.ScreenSize(16, 16))
        val img = BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 8) for (x in 0 until 16) img.setRGB(x, y, 0x00C08040)
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
        screen.apply(AgentFrame.ScreenTile(0, 8, 16, 8, ScreenCodec.ENCODING_JPEG, jpeg))
        screen.apply(AgentFrame.ScreenFrameEnd(1))
        val px = screen.pixelAt(5, 12)
        assertTrue(kotlin.math.abs(((px shr 16) and 0xFF) - 0xC0) <= 4 && kotlin.math.abs((px and 0xFF) - 0x40) <= 4, "jpeg pixel %08x".format(px))

        val argb = byteArrayOf(0x80.toByte(), 0x11, 0x22, 0x33, 0xFF.toByte(), 0, 0, 0)
        screen.apply(AgentFrame.CursorShape(2, 1, 1, 0, zlib(argb)))
        screen.apply(AgentFrame.CursorPos(10, 11, visible = true))
        val v = screen.view.value
        val cursor = assertNotNull(v.cursor)
        assertEquals(1, cursor.hotX)
        assertEquals(0x80112233.toInt(), (cursor.surface as FakeSurface).pixels[0])
        assertEquals(10, v.cursorX)
        assertTrue(v.cursorVisible)
    }

    @Test
    fun remote_screen_refuses_tiles_it_cannot_place() {
        val screen = RemoteScreen { w, h -> FakeSurface(w, h) }
        screen.apply(AgentFrame.ScreenSize(4, 4))
        assertFailsWith<AgentProtocolException> {
            screen.apply(AgentFrame.ScreenTile(2, 2, 4, 4, ScreenCodec.ENCODING_FILL, byteArrayOf(0, 0, 0)))
        }
        assertFailsWith<AgentProtocolException> {
            screen.apply(AgentFrame.ScreenTile(0, 0, 1, 1, 9, byteArrayOf()))
        }
        assertFailsWith<AgentProtocolException> {
            screen.apply(AgentFrame.ScreenTile(0, 0, 2, 2, ScreenCodec.ENCODING_ZLIB, zlib(ByteArray(5))))
        }
        assertFailsWith<AgentProtocolException> { screen.apply(AgentFrame.ScreenSize(0, 10)) }
    }

    @Test
    fun viewport_fits_and_follows_the_cursor_when_zoomed() {
        // 1280x800 into a 640x800 view: half size, centred vertically.
        val fit = ScreenViewport(640f, 800f, 1280, 800)
        assertEquals(0.5f, fit.scale)
        assertEquals(0f, fit.offsetX)
        assertEquals(200f, fit.offsetY)
        assertEquals(100 to 50, fit.toScreen(50f, 225f))
        assertEquals(0 to 0, fit.toScreen(-10f, 0f)) // outside: kept on the screen
        assertEquals(1279 to 799, fit.toScreen(9999f, 9999f))

        // Zoom 2 around the cursor at the right edge: the view stops at the edge.
        val zoomed = ScreenViewport(640f, 800f, 1280, 800, zoom = 2f, focusX = 1270f, focusY = 400f)
        assertEquals(1f, zoomed.scale)
        assertEquals(640f - 1280f, zoomed.offsetX)
        assertEquals(1270 to 400, zoomed.toScreen(630f, 400f))
    }
}
