package awtfree

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.system.exitProcess
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.scene.ComposeWindowHost

/**
 * Text, a field and a control that changes when it is clicked. Text needs a font manager, a
 * shaper and a layout pass, the field needs the platform's text input, and the control shows
 * that pointer input reaches the scene.
 */
@Composable
fun Content() {
    var clicks by remember { mutableStateOf(0) }
    var typed by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Color(0xFF12321A))) {
        Column(Modifier.padding(top = 32.dp, start = 24.dp)) {
            BasicText("안녕하세요, no AWT here", style = TextStyle(color = Color.White, fontSize = 24.sp))
            BasicText(
                "composed by Compose, painted by Skia, shown by a window of our own",
                style = TextStyle(color = Color(0xFF9CCC9C), fontSize = 14.sp),
            )
            BasicTextField(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier.padding(top = 16.dp).size(260.dp, 32.dp).background(Color(0xFF1E4620)),
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White),
            )
            Box(
                Modifier.padding(top = 24.dp).size(220.dp, 56.dp).background(Color(0xFF2E7D32)).clickable { clicks++ },
            ) {
                BasicText(
                    if (clicks == 0) "click me" else "clicked $clicks",
                    Modifier.padding(16.dp),
                    style = TextStyle(color = Color.White, fontSize = 18.sp),
                )
            }
        }
    }
}

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
 */
fun main() {
    val check = System.getenv("HELLO_SELF_CHECK")
    var ok = true
    if (check != null) {
        ok = textSelfCheck() && drawSample(File(check))
    }

    val host = ComposeWindowHost(
        platform = newPlatform(),
        config = WindowConfig("안녕하세요, AwtFree", 520, 360, minWidth = 240, minHeight = 160),
        surfaceFor = ::newSurface,
        content = { Content() },
    )
    if (!host.open()) {
        System.err.println("self-check: the window did not open")
        exitProcess(2)
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
