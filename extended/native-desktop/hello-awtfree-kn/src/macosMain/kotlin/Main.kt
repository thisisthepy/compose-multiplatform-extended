@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package awtfree

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.thisisthepy.compose.window.MacosAx
import org.thisisthepy.compose.window.TitleBarStyle
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.closeButtonInset
import org.thisisthepy.compose.window.formatMacosAxNode
import org.thisisthepy.compose.window.macos.MacosWindowPlatform
import org.thisisthepy.compose.window.macos.axApiProbe
import org.thisisthepy.compose.window.macosAccessibleContentFindings
import org.thisisthepy.compose.window.macosChromeFindings
import org.thisisthepy.compose.window.window
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.posix.getenv
import kotlin.system.exitProcess

/**
 * The AWT-free sample in the Kotlin/Native window. With HELLO_SELF_CHECK naming a PNG it proves,
 * with nobody watching, what hello-awtfree proves in the GraalVM window:
 * 1. Korean is shaped, broken into words and wrapped correctly by what is linked in, with no
 *    ICU data file on the machine;
 * 2. the sample text inks pixels, drawn into a raster surface and written to the PNG;
 * 3. the window opens and presents frames of the Compose content, each drawn at the size the
 *    window had when it was presented;
 * 4. the title bar is the one the configuration asked for, and a screen reader finds the field
 *    and the button by their names.
 * The exit status is 0 only when all four hold. HELLO_TITLE_BAR=Simple opens the window with
 * the plain title bar.
 */
fun main() {
    val check = getenv("HELLO_SELF_CHECK")?.toKString()
    var ok = true
    if (check != null) {
        ok = textSelfCheck() && drawSample(check)
    }
    val application = NSApplication.sharedApplication()
    // An executable outside a bundle is not, by default, something the system puts in front
    // or gives the keyboard.
    application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
    val titleBar = if (getenv("HELLO_TITLE_BAR")?.toKString() == "Simple") TitleBarStyle.Simple else TitleBarStyle.Normal
    val config = WindowConfig("안녕하세요, AwtFree", 520, 360, minWidth = 240, minHeight = 160, titleBar = titleBar)
    val platform = MacosWindowPlatform { Content() }
    val listener = object : WindowListener {
        override fun onEvent(event: WindowEvent) {}
    }
    if (!platform.open(config, listener)) {
        println("self-check: the window did not open")
        exitProcess(2)
    }
    application.activateIgnoringOtherApps(true)
    if (check == null) {
        application.run()
        return
    }
    val started = NSProcessInfo.processInfo.systemUptime
    fun presented() = platform.resizeStats?.presented ?: 0L
    // A frame is drawn when something changed, and nothing changes in this content on its own,
    // so each turn asks for one: 30 frames drawn and presented in a row.
    while (presented() < 30 && NSProcessInfo.processInfo.systemUptime - started < 20.0) {
        platform.requestFrame()
        platform.pump(16)
    }
    val stats = platform.resizeStats
    println("scene: presented ${stats?.presented ?: 0} frames of Compose content, ${stats?.stale ?: 0} at a size other than the window's")
    if ((stats?.presented ?: 0) == 0L || (stats?.stale ?: 0) != 0L) ok = false
    if (!readerChecks(platform, config)) ok = false
    exitProcess(if (ok) 0 else 1)
}

/**
 * The title bar and what a screen reader is told, asked in process: an outside client needs an
 * accessibility trust a CI runner has not given. What the system's API answers an outside
 * client is printed with its error number.
 */
private fun readerChecks(platform: MacosWindowPlatform, config: WindowConfig): Boolean {
    // Asking is what tells the window a reader is listening; it describes itself from the next
    // frame on.
    platform.accessibilitySnapshot()
    repeat(20) {
        platform.requestFrame()
        platform.pump(16)
    }
    val snapshot = platform.accessibilitySnapshot()
    val caption = platform.caption()
    val style = config.titleBar
    var ok = true
    val chrome = macosChromeFindings(snapshot, config.title, platform.macosTitleBar, caption)
    println("chrome: $style caption $caption, close button inset ${snapshot.closeButtonInset(config.title)} pt, macOS ${snapshot.macosMajor}")
    if (chrome.isEmpty()) {
        println("chrome: ok ($style)")
    } else {
        ok = false
        chrome.forEach { println("chrome: fail: $it") }
    }
    val content = macosAccessibleContentFindings(
        snapshot,
        config.title,
        listOf(MacosAx.TEXT_FIELD to FIELD_LABEL, MacosAx.BUTTON to BUTTON_LABEL),
    )
    if (content.isEmpty()) {
        println("accessibility: ok (a text field '$FIELD_LABEL' and a button '$BUTTON_LABEL')")
    } else {
        ok = false
        content.forEach { println("accessibility: fail: $it") }
    }
    println("accessibility: ${axApiProbe().describe()}")
    if (!ok) {
        println("accessibility: what the window told a reader:")
        snapshot.window(config.title).forEach { println("  ${formatMacosAxNode(it)}") }
    }
    return ok
}

/** Draws the sample text into a raster surface, counts what it inked and writes the PNG. */
private fun drawSample(png: String): Boolean {
    val surface = Surface.makeRasterN32Premul(480, 240)
    surface.canvas.clear(org.jetbrains.skia.Color.WHITE)
    sampleParagraph(440f).paint(surface.canvas, 20f, 20f)
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
    val written = bytes.usePinned {
        NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()).writeToFile(png, atomically = true)
    }
    if (!written) {
        println("self-check: the PNG could not be written to $png")
        return false
    }
    println("self-check: drew ${bytes.size} bytes of PNG to $png")
    return true
}
