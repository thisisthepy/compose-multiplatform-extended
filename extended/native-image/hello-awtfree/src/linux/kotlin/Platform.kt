package awtfree

import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.graalvm.linux.X11Window
import org.thisisthepy.compose.window.graalvm.linux.x11WindowSurface
import org.thisisthepy.compose.window.scene.WindowSurface

internal fun newPlatform(): WindowPlatform = X11Window()

internal fun newSurface(platform: WindowPlatform): WindowSurface? = x11WindowSurface(platform)

/** X11 takes calls from any one thread, so where main runs does not matter here. */
internal fun onFirstThread(): Boolean = true
