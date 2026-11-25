/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.test.tests.unit

import org.jetbrains.compose.desktop.application.internal.NativeImageLeftovers
import org.jetbrains.compose.internal.utils.OS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * GraalVM writes the shared libraries an image would have loaded beside the executable, and the
 * static image loads none of them; only the executable and what the application put there stay.
 */
class NativeImageLeftoversTest {
    @TempDir
    lateinit var dir: File

    private fun files(vararg names: String) = names.forEach { dir.resolve(it).writeText(it) }
    private fun left() = dir.list()!!.sorted()

    @Test
    fun windowsKeepsTheExecutableAlone() {
        // What packageNativeImage left beside gemstone.exe (plugin 1.11.1-ext.3, Gemstone #255).
        files("gemstone.exe", "freetype.dll", "java.dll", "javaaccessbridge.dll", "jawt.dll", "jvm.dll")
        val removed = NativeImageLeftovers.remove(OS.Windows, dir)
        assertEquals(listOf("gemstone.exe"), left())
        assertEquals(
            listOf("freetype.dll", "java.dll", "javaaccessbridge.dll", "jawt.dll", "jvm.dll"),
            removed.map { it.name }.sorted(),
        )
    }

    @Test
    fun windowsKeepsLibrariesItDoesNotKnow() {
        files("app.exe", "jvm.dll", "native-helper.dll", "app.pdb")
        NativeImageLeftovers.remove(OS.Windows, dir)
        assertEquals(listOf("app.exe", "app.pdb", "native-helper.dll"), left())
    }

    @Test
    fun linuxRemovesWhatItAlwaysDid() {
        files("app", "libjava.so", "libjvm.so", "libawt_headless.so", "libfreetype.so", "libother.so")
        NativeImageLeftovers.remove(OS.Linux, dir)
        assertEquals(listOf("app", "libother.so"), left())
    }

    @Test
    fun macosRemovesNothing() {
        files("app", "libjava.dylib")
        assertEquals(emptyList<File>(), NativeImageLeftovers.remove(OS.MacOS, dir))
        assertEquals(listOf("app", "libjava.dylib"), left())
    }
}
