@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package awtfree

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.linux.X11Window
import org.thisisthepy.compose.window.scene.FrameTarget
import org.thisisthepy.compose.window.scene.WindowSurface

/** GL's sized internal format for 8-bit RGBA, which the window's GLX framebuffer is. */
private const val GL_RGBA8_FORMAT = 0x8058

/**
 * Where the scene draws on the Kotlin/Native X11 window: Skia over the window's GLX
 * framebuffer, the same one [X11Window.draw] draws into. The GraalVM window's counterpart is
 * `x11WindowSurface` in the core fork's graalvm-linux module; this one is here until
 * native/linux can depend on the scene module itself. The frame for a new size is drawn from
 * inside the window's handling of it, through [X11Window.resizePainter].
 */
fun x11WindowSurface(platform: WindowPlatform): WindowSurface? =
    (platform as? X11Window)?.let(::X11GlWindowSurface)

/** Skia over [window]'s GLX framebuffer. */
class X11GlWindowSurface(private val window: X11Window) : WindowSurface {
    // Made once the window has made its context current, which it does when it opens.
    private val context = DirectContext.makeGL()

    override fun begin(width: Int, height: Int): FrameTarget? {
        if (width <= 0 || height <= 0 || !window.makeCurrent()) return null
        val target = BackendRenderTarget.makeGL(width, height, 0, 0, 0, GL_RGBA8_FORMAT)
        val surface = Surface.makeFromBackendRenderTarget(
            context,
            target,
            // GL counts rows from the bottom and Skia from the top.
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
        window.resizePainter = paint
    }

    override fun close() = context.close()
}
