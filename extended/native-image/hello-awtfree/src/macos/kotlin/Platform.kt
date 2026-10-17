package awtfree

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
