package link

import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import kotlin.system.exitProcess

/**
 * Opens one window, pumps a few times, presents one frame and exits. It draws nothing: the
 * point is that the window layer links and that its upcall table is reachable at run time.
 */
fun main() {
    val platform = windowPlatform()
    val listener = object : WindowListener {
        override fun onEvent(event: WindowEvent) {}
    }
    if (!platform.open(WindowConfig(title = "AwtFree link check", width = 320, height = 200), listener)) {
        System.err.println("link check failed: ${platform.name} could not open a window")
        exitProcess(1)
    }
    repeat(20) { platform.pump(16) }
    platform.requestFrame()
    repeat(5) { platform.pump(16) }
    val size = platform.measure()
    println("link check: ${platform.name} measured $size")
    platform.close()
    println("link check ok")
    exitProcess(0)
}
