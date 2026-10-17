package awtfree

import java.io.File
import kotlin.system.exitProcess
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener

/**
 * Opens a window through the extended window module and, with HELLO_SELF_CHECK naming a PNG,
 * checks Korean text layout, draws the sample text into that PNG and exits.
 *
 * The text check runs before the window opens, so a failure is reported even where no window
 * can be shown. It prints `text self-check: ok` or `text self-check: FAILED` with what was
 * wrong, and the exit status follows it.
 */
fun main() {
    val check = System.getenv("HELLO_SELF_CHECK")
    val textOk = textSelfCheck()

    if (check != null) {
        val surface = Surface.makeRasterN32Premul(480, 240)
        surface.canvas.clear(Color.WHITE)
        sampleParagraph(440f).paint(surface.canvas, 20f, 20f)
        val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)
            ?: run { System.err.println("self-check: Skia did not encode the PNG"); exitProcess(1) }
        File(check).writeBytes(png.bytes)
        println("self-check: drew ${png.size} bytes of PNG to $check")
    }

    val platform = windowPlatform()
    val listener = object : WindowListener {
        override fun onEvent(event: WindowEvent) {}
    }
    if (!platform.open(WindowConfig(title = "안녕하세요, AwtFree", width = 480, height = 240), listener)) {
        System.err.println("self-check: ${platform.name} could not open a window")
        exitProcess(1)
    }
    repeat(20) { platform.pump(16) }
    platform.requestFrame()
    repeat(5) { platform.pump(16) }
    println("window: ${platform.name} measured ${platform.measure()}")
    platform.close()
    exitProcess(if (textOk) 0 else 1)
}
