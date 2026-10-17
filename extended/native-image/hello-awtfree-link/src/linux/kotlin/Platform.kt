package link

import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.graalvm.linux.X11Window

fun windowPlatform(): WindowPlatform = X11Window()
