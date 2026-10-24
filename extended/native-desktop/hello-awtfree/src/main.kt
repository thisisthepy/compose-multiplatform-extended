@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package awtfree

import kotlin.system.exitProcess
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.linux.X11Window
import org.thisisthepy.compose.window.linux.x11WindowSurface
import org.thisisthepy.compose.window.scene.ComposeWindowHost
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.timespec

/**
 * The GraalVM AWT-free sample's application as a Kotlin/Native executable: the same Compose
 * content (Content.kt, linked from extended/native-image/hello-awtfree) hosted by the core
 * fork's scene module in its Kotlin/Native X11 window, with no virtual machine in the file.
 *
 * It answers the gate the GraalVM cells run, in the same words:
 * - with HELLO_SELF_CHECK naming a PNG: the Korean text check, the sample text drawn into the
 *   PNG, 30 frames of the Compose content presented, then exit, 0 only when all of it held;
 * - with HELLO_RUN_SECONDS: it stays up that long or until the window is closed, for the Linux
 *   chrome and accessibility check that drives it from outside, then reports the frames it
 *   presented at the wrong size and the window's resize count.
 */
fun main() {
    val check = getenv("HELLO_SELF_CHECK")?.toKString()
    var ok = true
    if (check != null) ok = textSelfCheck() && drawSample(check)

    val platform = X11Window()
    val host = ComposeWindowHost(
        platform = platform,
        config = WindowConfig("안녕하세요, AwtFree", 520, 360, minWidth = 240, minHeight = 160),
        surfaceFor = ::x11WindowSurface,
        content = { Content() },
    )
    if (!host.open()) {
        println("self-check: the window did not open")
        exitProcess(2)
    }
    val stay = getenv("HELLO_RUN_SECONDS")?.toKString()?.toLongOrNull()
    if (check == null && stay == null) {
        host.run()
        return
    }
    val until = nowNanos() + (stay ?: 20L) * 1_000_000_000L
    try {
        while (!host.closed && nowNanos() < until) {
            // The self-check draws 30 frames in a row; nothing in the content changes on its
            // own, so each turn asks for one. A run that is being driven draws when it is.
            if (check != null) {
                if (host.presentLog.frames >= 30) break
                host.requestFrame()
            }
            host.turn(16)
        }
    } finally {
        host.close()
    }
    val log = host.presentLog
    println("scene: presented ${log.frames} frames of Compose content, ${log.mismatched} at a size other than the window's")
    if (stay != null) println(platform.resizeStats.report())
    if (log.frames == 0 || log.mismatched > 2) ok = false
    exitProcess(if (ok) 0 else 1)
}

private fun nowNanos(): Long = memScoped {
    val now = alloc<timespec>()
    clock_gettime(CLOCK_MONOTONIC, now.ptr)
    now.tv_sec * 1_000_000_000L + now.tv_nsec
}

/** Draws the sample text into a raster surface, counts what it inked and writes the PNG. */
private fun drawSample(png: String): Boolean {
    val surface = Surface.makeRasterN32Premul(480, 240)
    surface.canvas.clear(org.jetbrains.skia.Color.WHITE)
    sampleParagraph(440f).paint(surface.canvas, 20f, 20f)
    // Text that laid out but drew nothing would pass the layout check; count what it drew.
    val bitmap = Bitmap().apply { allocN32Pixels(480, 240) }
    surface.readPixels(bitmap, 0, 0)
    var inked = 0
    for (y in 0 until 240) for (x in 0 until 480) if (bitmap.getColor(x, y) != org.jetbrains.skia.Color.WHITE) inked++
    if (inked == 0) {
        println("self-check: the sample text drew no pixels")
        return false
    }
    println("self-check: the sample text inked $inked pixels")
    val bytes = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes ?: run {
        println("self-check: Skia did not encode the PNG")
        return false
    }
    val file = fopen(png, "wb") ?: run {
        println("self-check: could not open $png")
        return false
    }
    val written = bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), file) }
    fclose(file)
    if (written.toLong() != bytes.size.toLong()) {
        println("self-check: wrote $written of ${bytes.size} bytes to $png")
        return false
    }
    println("self-check: drew ${bytes.size} bytes of PNG to $png")
    return true
}
