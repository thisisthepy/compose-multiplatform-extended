package org.jetbrains.compose.test.tests.unit

import org.jetbrains.compose.desktop.application.internal.nativeDesktopLinkerOpts
import org.jetbrains.kotlin.konan.target.Family
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeLinkerOptionsTest {
    @Test
    fun macosLinksAppKitAndMetal() {
        val opts = nativeDesktopLinkerOpts(Family.OSX, null, true, emptyList())
        assertTrue(listOf("-framework", "AppKit") in opts.windowed(2))
        assertTrue(listOf("-framework", "Metal") in opts.windowed(2))
    }

    @Test
    fun linuxLinksX11AndSkikoDirectory() {
        val opts = nativeDesktopLinkerOpts(Family.LINUX, "/skiko", true, listOf("-lfoo"))
        assertTrue("-lX11" in opts)
        assertEquals(listOf("-L/skiko", "-lfoo"), opts.takeLast(2))
    }

    @Test
    fun linuxWithoutWindowSystemAddsNoLibraries() {
        assertEquals(emptyList(), nativeDesktopLinkerOpts(Family.LINUX, null, false, emptyList()))
    }
}
