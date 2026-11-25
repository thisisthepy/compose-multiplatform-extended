/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.test.tests.unit

import org.jetbrains.compose.desktop.preview.internal.skikoVersionOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The preview finds skiko's own version in a skiko-awt jar, whatever the jar is called. This fork
 * republishes skiko-awt as org.thisisthepy.compose.skiko:skiko-awt:<fork version>, so the version
 * in the file name is the fork's, and the runtime asked for with it does not exist (#78).
 */
class SkikoJarVersionTest {
    @TempDir
    lateinit var dir: File

    /** A class file holding [strings] as constants, as `org.jetbrains.skiko.Version` does. */
    private fun classWith(vararg strings: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(0xCAFEBABE.toInt())
            out.writeShort(0)
            out.writeShort(52)
            out.writeShort(strings.size + 1)
            for (string in strings) {
                out.writeByte(1) // CONSTANT_Utf8
                out.writeUTF(string)
            }
        }
        return bytes.toByteArray()
    }

    private fun jar(name: String, entries: Map<String, ByteArray>): File {
        val file = dir.resolve(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, content) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun theForkJarGivesSkikosOwnVersion() {
        // What org/jetbrains/skiko/Version.class holds in skiko 0.144.6: the field names, then
        // skiko's version, then Skia's.
        val versionClass = classWith("org/jetbrains/skiko/Version", "getSkiko", "skiko", "skia", "0.144.6", "m144-22f58c9fd4")
        val forkJar = jar("skiko-awt-1.11.1-ext.6.jar", mapOf("org/jetbrains/skiko/Version.class" to versionClass))
        assertEquals("0.144.6", skikoVersionOf(forkJar))
    }

    @Test
    fun aPreReleaseVersionIsKeptWhole() {
        val versionClass = classWith("skiko", "0.9.4-alpha.2", "m132")
        assertEquals("0.9.4-alpha.2", skikoVersionOf(jar("skiko-awt-x.jar", mapOf("org/jetbrains/skiko/Version.class" to versionClass))))
    }

    @Test
    fun aJarWithoutTheClassFallsBackToItsName() {
        val upstreamJar = jar("skiko-awt-0.144.6.jar", mapOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray()))
        assertEquals("0.144.6", skikoVersionOf(upstreamJar))
    }

    @Test
    fun somethingThatIsNotAJarGivesNothing() {
        val notAJar = dir.resolve("skiko-awt-.jar").apply { writeText("not a zip") }
        assertNull(skikoVersionOf(notAJar))
    }
}
