package io.github.danielperezmartinez.titanssh.rdp

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import io.github.danielperezmartinez.titanssh.config.desktopConfigDirectory
import java.io.File
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * The library ships in the app's resources (`lib/app/resources/` of each
 * package, ADR-0028 §5), where Compose points `compose.application.resources.dir`.
 * `-Dtitan.rdp.library=<file>` loads another one, for development. Skia on
 * x86 takes B, G, R, A.
 */
actual fun loadRdpEngine(): RdpAvailability = loadRdpLibraryOnce({
    val file = rdpLibraryFile() ?: error("this build has no RDP library (${System.mapLibraryName("titan_rdp")})")
    System.load(file.absolutePath)
}, bgra = true)

private fun rdpLibraryFile(): File? {
    System.getProperty("titan.rdp.library")?.let { return File(it).takeIf(File::isFile) }
    val dir = System.getProperty("compose.application.resources.dir") ?: return null
    return File(dir, System.mapLibraryName("titan_rdp")).takeIf(File::isFile)
}

actual fun createRdpCertificateStore(): RdpCertificateStore =
    FileRdpCertificateStore(File(desktopConfigDirectory(), "rdp_certificates"))

/** A new Skia bitmap per frame: Compose may keep drawing the previous one meanwhile. */
actual class RdpFrameImage actual constructor(private val width: Int, private val height: Int) {
    private val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)

    private var current: ImageBitmap = Bitmap().apply { allocPixels(info) }.asComposeImageBitmap()

    actual val image: ImageBitmap get() = current

    actual fun update(pixels: ByteArray) {
        val bitmap = Bitmap()
        bitmap.allocPixels(info)
        bitmap.installPixels(info, pixels, width * 4)
        current = bitmap.asComposeImageBitmap()
    }
}
