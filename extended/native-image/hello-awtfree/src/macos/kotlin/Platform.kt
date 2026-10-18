package awtfree

import org.graalvm.nativeimage.c.function.CFunction
import org.thisisthepy.compose.window.WindowPlatform
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

/** The run's resize count, which only the Linux check reads; the macOS window keeps its own in C. */
internal fun resizeReport(platform: WindowPlatform): String = "resize: counted by the window in C on macOS"
