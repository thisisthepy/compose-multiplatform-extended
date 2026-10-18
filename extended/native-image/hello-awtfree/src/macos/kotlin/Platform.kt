package awtfree

import org.graalvm.nativeimage.c.function.CFunction
import org.thisisthepy.compose.window.MacosAx
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.closeButtonInset
import org.thisisthepy.compose.window.formatMacosAxNode
import org.thisisthepy.compose.window.macosAccessibleContentFindings
import org.thisisthepy.compose.window.macosChromeFindings
import org.thisisthepy.compose.window.window
import org.thisisthepy.compose.window.scene.ComposeWindowHost
import org.thisisthepy.compose.window.graalvm.macos.AppKitWindowPlatform
import org.thisisthepy.compose.window.graalvm.macos.appKitWindowSurface
import org.thisisthepy.compose.window.graalvm.macos.installApplicationMenu
import org.thisisthepy.compose.window.scene.WindowSurface

internal fun newPlatform(): WindowPlatform = AppKitWindowPlatform()

internal fun newSurface(platform: WindowPlatform): WindowSurface? {
    // The menu bar every application has: without it command-Q does not quit and command-C
    // does not copy.
    installApplicationMenu("hello-awtfree")
    return appKitWindowSurface(platform)
}

/** Nonzero on the process's first thread, the only one AppKit takes calls from. */
@CFunction("pthread_main_np")
private external fun pthreadMainNp(): Int

/**
 * Whether main runs on the process's first thread. The AppKit window hands its calls to that
 * thread when it is called from another, and that thread has to be serving them; a native image
 * that ran main on a new thread leaves it waiting for main to end, and the first such call waits
 * forever.
 */
internal fun onFirstThread(): Boolean = pthreadMainNp() != 0

/**
 * Checks the running window as a reader who cannot see it meets it, and prints what it found.
 *
 * Asked in process, because an outside accessibility client needs a trust a CI runner has not
 * given: the window walks its own accessibility elements, the objects AppKit's accessibility
 * server answers every client from. What the system's API answers an outside client is
 * printed too, with its error number.
 *
 * - The title bar the configuration asked for: the three system buttons, the content under
 *   the bar, no title drawn, the caption's height and button room as the buttons' own frames
 *   say, and the corner radius from the measured table.
 * - The field and the button, by role and by the names a screen reader is told.
 *
 * Returns whether both hold.
 */
internal fun platformChecks(host: ComposeWindowHost, platform: WindowPlatform, config: WindowConfig): Boolean {
    val window = platform as AppKitWindowPlatform
    // Asking is what tells the window a reader is listening; the scene describes itself from
    // the next frame on.
    window.accessibilitySnapshot()
    repeat(20) {
        host.requestFrame()
        host.turn(16)
    }
    val snapshot = window.accessibilitySnapshot()
    val caption = window.caption()
    val style = config.titleBar
    var ok = true
    val chrome = macosChromeFindings(snapshot, config.title, window.macosTitleBar, caption)
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
    println("accessibility: ${window.accessibilityApiProbe().describe()}")
    if (!ok) {
        println("accessibility: what the window told a reader:")
        snapshot.window(config.title).forEach { println("  ${formatMacosAxNode(it)}") }
    }
    return ok
}
