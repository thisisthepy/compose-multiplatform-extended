/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.internal

import org.jetbrains.compose.internal.utils.OS
import java.io.File

/**
 * The shared libraries GraalVM writes beside a native image that the image does not load.
 *
 * native-image copies the JDK libraries the image would have used: the `java` and `jvm` shims,
 * which a static image never opens, and the libraries whose code the plugin links in from NIK's
 * static archives instead (AWT's, FreeType, JAWT). The application ships the executable alone,
 * so whatever is left beside it is at best dead weight and at worst a second copy of a library
 * that a later `loadLibrary` would pick up instead of the linked one.
 */
internal object NativeImageLeftovers {
    /** File names, per host, of what the executable does not load. macOS writes none of them. */
    fun names(os: OS): List<String> = when (os) {
        OS.Linux -> listOf("libjava.so", "libjvm.so", "libawt_headless.so", "libfreetype.so")
        // freetype.lib and jawt.lib are linked in (the Windows static JDK archives); the Java
        // Access Bridge's library is copied with java.desktop but nothing registers or loads it.
        OS.Windows -> listOf("java.dll", "jvm.dll", "freetype.dll", "jawt.dll", "javaaccessbridge.dll")
        OS.MacOS -> emptyList()
    }

    /** Deletes [names] from [directory] and answers what it deleted. */
    fun remove(os: OS, directory: File): List<File> =
        names(os).map { directory.resolve(it) }.filter { it.isFile && it.delete() }
}
