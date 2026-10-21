package io.github.danielperezmartinez.titanssh.rdp

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.danielperezmartinez.titanssh.config.AndroidConfigContext
import java.io.File
import java.nio.ByteBuffer

/**
 * The APK carries `libtitan_rdp.so` for arm64-v8a and x86_64 (ADR-0028 §5);
 * a 32-bit device has none and gets [RdpAvailability.Missing]. Android's
 * bitmaps take R, G, B, A.
 */
actual fun loadRdpEngine(): RdpAvailability =
    loadRdpLibraryOnce({ System.loadLibrary("titan_rdp") }, bgra = false)

actual fun createRdpCertificateStore(): RdpCertificateStore =
    FileRdpCertificateStore(File(AndroidConfigContext.require().filesDir, "rdp_certificates"))

/** One mutable bitmap per desktop size, whose pixels each frame replaces. */
actual class RdpFrameImage actual constructor(width: Int, height: Int) {
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }

    actual val image: ImageBitmap = bitmap.asImageBitmap()

    actual fun update(pixels: ByteArray) {
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
    }
}
