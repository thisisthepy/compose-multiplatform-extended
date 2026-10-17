package awtfree

import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.graalvm.macos.AppKitWindowPlatform

fun windowPlatform(): WindowPlatform = AppKitWindowPlatform()
