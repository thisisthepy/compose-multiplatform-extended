package hello

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.graalvm.macos.AppKitUpcallSlots
import org.thisisthepy.compose.window.graalvm.macos.AppKitWindowPlatform
import org.thisisthepy.compose.window.graalvm.macos.NativeWindow
import org.thisisthepy.compose.window.graalvm.macos.forgetFrameCallback
import org.thisisthepy.compose.window.graalvm.macos.installApplicationMenu
import org.thisisthepy.compose.window.graalvm.macos.registerFrameCallback
import org.thisisthepy.compose.window.scene.FrameTarget
import org.thisisthepy.compose.window.scene.WindowSurface

internal fun newPlatform(): WindowPlatform = AppKitWindowPlatform()

internal fun newSurface(platform: WindowPlatform): WindowSurface? {
    val window = (platform as AppKitWindowPlatform).nativeWindow ?: return null
    // The menu bar every application has: without it command-Q does not quit and command-C
    // does not copy.
    installApplicationMenu("Hello AWT-free")
    return MetalWindowSurface(window)
}

/** Skia over the Metal drawable AppKit's layer hands out, one frame at a time. */
private class MetalWindowSurface(private val window: NativeWindow) : WindowSurface {
    private val context = DirectContext.makeMetal(window.device, window.queue)

    override fun begin(width: Int, height: Int): FrameTarget? {
        // Zero means the system had no drawable to give: frames are made faster than the
        // screen takes them, and the answer is to skip one.
        val texture = window.beginFrame()
        if (texture == 0L) return null
        val target = BackendRenderTarget.makeMetal(width, height, texture)
        val surface = Surface.makeFromBackendRenderTarget(
            context,
            target,
            SurfaceOrigin.TOP_LEFT,
            SurfaceColorFormat.BGRA_8888,
            ColorSpace.sRGB,
            SurfaceProps(PixelGeometry.RGB_H),
        )
        if (surface == null) {
            target.close()
            window.endFrame()
            return null
        }
        return object : FrameTarget {
            override val surface: Surface = surface
            override fun close() {
                surface.close()
                target.close()
            }
        }
    }

    override fun setResizePainter(paint: (() -> Boolean)?) {
        if (paint == null) {
            AppKitUpcallSlots.frame = null
            forgetFrameCallback()
        } else {
            AppKitUpcallSlots.frame = Runnable { paint() }
            registerFrameCallback()
        }
    }

    override fun close() = context.close()
}
