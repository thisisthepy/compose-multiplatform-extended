package awtfree

import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.scene.ComposeWindowHost
import org.thisisthepy.compose.window.graalvm.linux.X11Window
import org.thisisthepy.compose.window.graalvm.linux.x11WindowSurface
import org.thisisthepy.compose.window.scene.WindowSurface

internal fun newPlatform(): WindowPlatform = X11Window()

internal fun newSurface(platform: WindowPlatform): WindowSurface? = x11WindowSurface(platform)

/** X11 takes calls from any one thread, so where main runs does not matter here. */
internal fun onFirstThread(): Boolean = true

/**
 * The platform's checks of the running window. None on this desktop yet: the X11 title bar
 * and accessibility checks are the Linux window's to add.
 */
internal fun platformChecks(host: ComposeWindowHost, platform: WindowPlatform, config: WindowConfig): Boolean {
    println("chrome: not checked on this desktop; the X11 window's title bar and accessibility checks are added with it")
    return true
}
