package awtfree

import java.io.File
import kotlin.system.exitProcess
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.scene.ComposeWindowHost

/**
 * A Compose application with no AWT in it: the window is the core fork's GraalVM window, the
 * scene is hosted by its scene module, and Skia draws into the window's own surface, Metal on
 * macOS and GL on Linux.
 *
 * With HELLO_SELF_CHECK naming a PNG it proves, with nobody watching, what a one-executable
 * build has to:
 * 1. Korean is shaped, broken into words and wrapped correctly by what is linked in
 *    ([textSelfCheck]), with no ICU data file on the machine;
 * 2. the sample text inks pixels, drawn into a raster surface and written to the PNG;
 * 3. the window opens and presents frames of the Compose content, each drawn at the size the
 *    window had when it was presented.
 * The exit status is 0 only when all three hold.
 *
 * With HELLO_RUN_SECONDS set it stays up that long, or until the window is closed, for a check
 * that drives the window from outside (the Linux chrome and accessibility check), and then says
 * how many frames it presented at the wrong size and what the window's resize count was. The
 * exit status is 0 only when no frame was presented at a size other than the window's.
 */
fun main() {
    val check = System.getenv("HELLO_SELF_CHECK")
    var ok = true
    if (check != null) {
        ok = textSelfCheck() && drawSample(File(check))
    }

    if (!onFirstThread()) {
        System.err.println("self-check: main is not on the process's first thread, which the window's calls need")
        exitProcess(3)
    }
    val platform = newPlatform()
    val host = ComposeWindowHost(
        platform = platform,
        config = WindowConfig("안녕하세요, AwtFree", 520, 360, minWidth = 240, minHeight = 160),
        surfaceFor = ::newSurface,
        content = { Content() },
    )
    if (!host.open()) {
        System.err.println("self-check: the window did not open")
        exitProcess(2)
    }
    val stay = System.getenv("HELLO_RUN_SECONDS")?.toLongOrNull()
    if (check == null && stay != null) {
        val until = System.nanoTime() + stay * 1_000_000_000L
        try {
            while (!host.closed && System.nanoTime() < until) host.turn(16)
        } finally {
            host.close()
        }
        val log = host.presentLog
        println("scene: presented ${log.frames} frames of Compose content, ${log.mismatched} at a size other than the window's")
        println(resizeReport(platform))
        exitProcess(if (log.frames > 0 && log.mismatched <= 2) 0 else 1)
    }
    if (check == null) {
        host.run()
        return
    }
    val deadline = System.nanoTime() + 20_000_000_000L
    try {
        // A frame is drawn when something changed, and nothing changes in this content on its
        // own, so each turn asks for one: 30 frames drawn and presented in a row.
        while (host.presentLog.frames < 30 && System.nanoTime() < deadline && !host.closed) {
            host.requestFrame()
            host.turn(16)
        }
    } finally {
        host.close()
    }
    val log = host.presentLog
    println("scene: presented ${log.frames} frames of Compose content, ${log.mismatched} at a size other than the window's")
    if (log.frames == 0 || log.mismatched != 0) ok = false
    exitProcess(if (ok) 0 else 1)
}

/** Draws the sample text into a raster surface, counts what it inked and writes the PNG. */
private fun drawSample(png: File): Boolean {
    val surface = Surface.makeRasterN32Premul(480, 240)
    surface.canvas.clear(org.jetbrains.skia.Color.WHITE)
    sampleParagraph(440f).paint(surface.canvas, 20f, 20f)
    // Text that laid out but drew nothing would pass the layout check; count what it drew.
    val bitmap = Bitmap().apply { allocN32Pixels(480, 240) }
    surface.readPixels(bitmap, 0, 0)
    var inked = 0
    for (y in 0 until 240) for (x in 0 until 480) if (bitmap.getColor(x, y) != org.jetbrains.skia.Color.WHITE) inked++
    if (inked == 0) {
        System.err.println("self-check: the sample text drew no pixels")
        return false
    }
    println("self-check: the sample text inked $inked pixels")
    val data = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG) ?: run {
        System.err.println("self-check: Skia did not encode the PNG")
        return false
    }
    png.writeBytes(data.bytes)
    println("self-check: drew ${data.size} bytes of PNG to $png")
    return true
}
