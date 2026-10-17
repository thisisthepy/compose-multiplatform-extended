/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.test.tests.unit

import groovy.json.JsonSlurper
import org.jetbrains.compose.desktop.application.internal.NativeImageNoAwt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** An AwtFree image is built from metadata with no AWT entry and fails on an AWT symbol. */
class NativeImageNoAwtTest {
    private val metadata = """
        {
          "reflection": [
            {"type": "androidx.compose.ui.awt.ComposeWindow", "allDeclaredMethods": true},
            {"type": "com.apple.eawt._AppEventHandler"},
            {"type": "sun.awt.SunToolkit"},
            {"type": "java.awt.Toolkit"},
            {"type": "javax.swing.UIManager"},
            {"type": "kotlin.Metadata"},
            {"type": "org.jetbrains.skia.Image"}
          ],
          "jni": [
            {"type": "java.awt.Window"},
            {"type": "org.jetbrains.skia.impl.Native"}
          ],
          "resources": [
            {"glob": "META-INF/services/java.awt.im.spi.InputMethodDescriptor"},
            {"module": "java.desktop", "glob": "sun/awt/resources/awt_ko_KR.properties"},
            {"bundle": "sun.awt.resources.awt"},
            {"bundle": "com.apple.laf.resources.aqua"},
            {"glob": "fonts/Inter.ttf"}
          ]
        }
    """.trimIndent()

    private fun entries(json: String, key: String) =
        ((JsonSlurper().parseText(json) as Map<*, *>)[key] as List<*>).map { it as Map<*, *> }

    @Test
    fun cleanedMetadataKeepsOnlyWhatIsNotAwt() {
        val cleaned = NativeImageNoAwt.cleanMetadata(metadata)
        assertEquals(listOf("kotlin.Metadata", "org.jetbrains.skia.Image"), entries(cleaned, "reflection").map { it["type"] })
        assertEquals(listOf("org.jetbrains.skia.impl.Native"), entries(cleaned, "jni").map { it["type"] })
        assertEquals(listOf("fonts/Inter.ttf"), entries(cleaned, "resources").map { it["glob"] })
    }

    @Test
    fun cleanedMetadataDirectoryLeavesTheSourceAlone(@TempDir dir: File) {
        val source = dir.resolve("source").apply { mkdirs() }
        source.resolve("reachability-metadata.json").writeText(metadata)
        val target = dir.resolve("target")
        NativeImageNoAwt.cleanMetadataDirectory(source, target)
        assertEquals(metadata, source.resolve("reachability-metadata.json").readText())
        assertEquals(1, entries(target.resolve("reachability-metadata.json").readText(), "resources").size)
    }

    @Test
    fun forbiddenTypesNameTheRootsOfAwt() {
        for (type in listOf("java.awt.Toolkit", "java.awt.Component", "java.awt.GraphicsEnvironment")) {
            assertTrue(type in NativeImageNoAwt.forbiddenTypes, type)
        }
    }

    @Test
    fun executableScanFindsAwtSymbolsAndLibraryNames(@TempDir dir: File) {
        val clean = dir.resolve("clean").apply { writeBytes(ByteArray(10_000_000) { 7 } + "Java_org_jetbrains_skia_Surface".toByteArray()) }
        assertTrue(NativeImageNoAwt.scanExecutable(clean).isEmpty())

        // The name straddles the boundary of two reads.
        val bytes = ByteArray(8 * 1024 * 1024 - 5) { 7 } + "JAWT_GetAWT".toByteArray() + ByteArray(100)
        val awt = dir.resolve("awt").apply { writeBytes(bytes) }
        assertEquals(setOf("JAWT_GetAWT"), NativeImageNoAwt.scanExecutable(awt))

        val lib = dir.resolve("lib").apply { writeText("x Java_sun_awt_X11_foo libawt_xawt.so") }
        assertEquals(setOf("Java_sun_awt_", "libawt"), NativeImageNoAwt.scanExecutable(lib))
        assertFalse(NativeImageNoAwt.forbiddenSymbols.isEmpty())
    }

    @Test
    fun awtLibrariesBesideAnImageAreFound(@TempDir dir: File) {
        for (name in listOf("app", "libskiko.so", "libawt.so", "libawt_xawt.so", "libfontmanager.so", "jawt.dll")) {
            dir.resolve(name).writeText("")
        }
        assertEquals(listOf("jawt.dll", "libawt.so", "libawt_xawt.so", "libfontmanager.so"), NativeImageNoAwt.awtLibrariesBeside(dir))
    }
}
