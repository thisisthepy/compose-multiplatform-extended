/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.thisisthepy.kotlin.gradle.nativedesktop.msvc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MsvcLinkBundleTest {
    private fun zip(file: File, vararg entries: Pair<String, String>): File {
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `a bundle gives its libraries, runtime files, import libraries and the C++ library`(@TempDir dir: File) {
        val archive = zip(
            File(dir, "skiko-mingwx64-1.11.1-ext.1-msvc.zip"),
            "lib/skiko-bridges.lib" to "a",
            "lib/skia.lib" to "b",
            "runtime/icudtl.dat" to "c",
            "msvc-link.properties" to "systemLibraries=opengl32.lib, d3d12.lib\ncppStandardLibrary=true\n",
        )
        val bundle = MsvcLinkBundle.unpack(archive, File(dir, "out"))
        assertEquals(listOf("skia.lib", "skiko-bridges.lib"), bundle.libraries.map { it.name })
        assertTrue(bundle.libraries.all { it.isFile })
        assertEquals(listOf("icudtl.dat"), bundle.runtimeFiles.map { it.name })
        assertEquals(listOf("opengl32.lib", "d3d12.lib"), bundle.systemLibraries)
        assertTrue(bundle.cppStandardLibrary)
    }

    @Test
    fun `without properties it links its libraries and nothing else`(@TempDir dir: File) {
        val bundle = MsvcLinkBundle.unpack(zip(File(dir, "a.zip"), "lib/a.lib" to "a"), File(dir, "out"))
        assertEquals(emptyList<String>(), bundle.systemLibraries)
        assertFalse(bundle.cppStandardLibrary)
        assertEquals(emptyList<File>(), bundle.runtimeFiles)
    }

    @Test
    fun `a bundle with nothing to link is refused`(@TempDir dir: File) {
        assertThrows(MsvcLinkException::class.java) {
            MsvcLinkBundle.unpack(zip(File(dir, "a.zip"), "runtime/x.dat" to "x"), File(dir, "out"))
        }
    }

    @Test
    fun `an entry outside the bundle's directory is refused`(@TempDir dir: File) {
        assertThrows(MsvcLinkException::class.java) {
            MsvcLinkBundle.unpack(zip(File(dir, "a.zip"), "../escape.lib" to "x", "lib/a.lib" to "a"), File(dir, "out"))
        }
        assertFalse(File(dir, "escape.lib").exists())
    }
}
