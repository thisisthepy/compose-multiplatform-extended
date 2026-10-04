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
import org.thisisthepy.compose.window.graalvm.linux.X11Upcalls
import org.thisisthepy.compose.window.graalvm.linux.X11Window
import org.thisisthepy.compose.window.scene.FrameTarget
import org.thisisthepy.compose.window.scene.WindowSurface

private const val GL_RGBA8 = 0x8058

internal fun newPlatform(): WindowPlatform = X11Window()

internal fun newSurface(platform: WindowPlatform): WindowSurface? = GlWindowSurface(platform as X11Window)

/** Skia over the window's GLX framebuffer, which the window makes current when it opens. */
private class GlWindowSurface(private val window: X11Window) : WindowSurface {
    private val context = DirectContext.makeGL()

    override fun begin(width: Int, height: Int): FrameTarget? {
        if (!window.beginFrame()) return null
        val target = BackendRenderTarget.makeGL(width, height, 0, 0, 0, GL_RGBA8)
        val surface = Surface.makeFromBackendRenderTarget(
            context,
            target,
            SurfaceOrigin.BOTTOM_LEFT,
            SurfaceColorFormat.RGBA_8888,
            ColorSpace.sRGB,
            SurfaceProps(PixelGeometry.RGB_H),
        )
        if (surface == null) {
            target.close()
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
        if (paint == null) X11Upcalls.clearFramePainter() else X11Upcalls.setFramePainter(paint)
    }

    override fun close() = context.close()
}
