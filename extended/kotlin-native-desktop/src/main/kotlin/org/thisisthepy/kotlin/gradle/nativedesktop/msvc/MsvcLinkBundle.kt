/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.thisisthepy.kotlin.gradle.nativedesktop.msvc

import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

/**
 * The MSVC half of a Kotlin/Native library, which its klib cannot carry.
 *
 * A Kotlin/Native library with C++ in it normally embeds the compiled C++ in its klib, and
 * Kotlin/Native's own linker takes it from there. On Windows that linker is MinGW's, and C++ that
 * has to be MSVC's (to call into a library built with MSVC, such as JetBrains' Skia) cannot go
 * that way. Such a library publishes it beside the klib instead, as a zip with the classifier
 * `msvc`, and the MSVC link adds it:
 *
 * - `lib/`: the static libraries (`.lib`) to link, their runtime directives blanked;
 * - `runtime/`: files the program reads at run time, copied next to the executable;
 * - `msvc-link.properties`: `systemLibraries=a.lib,b.lib`, the Windows import libraries it calls
 *   into, and `cppStandardLibrary=true` when it is C++ and needs MSVC's `libcpmt.lib`.
 */
data class MsvcLinkBundle(
    val libraries: List<File>,
    val runtimeFiles: List<File>,
    val systemLibraries: List<String>,
    val cppStandardLibrary: Boolean,
) {
    companion object {
        const val CLASSIFIER = "msvc"

        /** Unpacks [zip] into [directory], which is emptied first, and reads what it holds. */
        fun unpack(zip: File, directory: File): MsvcLinkBundle {
            directory.deleteRecursively()
            directory.mkdirs()
            val root = directory.canonicalFile
            ZipFile(zip).use { archive ->
                for (entry in archive.entries()) {
                    val out = File(directory, entry.name).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) {
                        throw MsvcLinkException("${zip.name} has an entry outside its own directory: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile.mkdirs()
                        archive.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                    }
                }
            }
            val properties = Properties()
            File(directory, "msvc-link.properties").takeIf { it.isFile }?.reader()?.use { properties.load(it) }
            fun files(dir: String, filter: (File) -> Boolean) =
                File(directory, dir).listFiles().orEmpty().filter { it.isFile && filter(it) }.sortedBy { it.name }
            val libraries = files("lib") { it.extension.equals("lib", ignoreCase = true) }
            if (libraries.isEmpty()) throw MsvcLinkException("${zip.name} holds no lib/*.lib to link")
            return MsvcLinkBundle(
                libraries = libraries,
                runtimeFiles = files("runtime") { true },
                systemLibraries = properties.getProperty("systemLibraries").orEmpty()
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() },
                cppStandardLibrary = properties.getProperty("cppStandardLibrary")?.trim().toBoolean(),
            )
        }
    }
}
