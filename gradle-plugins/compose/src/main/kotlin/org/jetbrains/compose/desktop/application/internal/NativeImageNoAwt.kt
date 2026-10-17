/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.internal

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File

/**
 * What it takes for a native image to hold no AWT: the types whose reachability fails the
 * build, the metadata entries that would make AWT reachable, and the scan of the finished
 * executable.
 */
internal object NativeImageNoAwt {
    /**
     * Types native-image refuses to find reachable in an AwtFree image. They are the roots
     * of AWT and Swing: reaching any of them means a window system, an event queue or a
     * look and feel is part of the image.
     */
    val forbiddenTypes: List<String> = listOf(
        "java.awt.Toolkit",
        "java.awt.Component",
        "java.awt.Window",
        "java.awt.GraphicsEnvironment",
        "java.awt.EventQueue",
        "java.awt.image.BufferedImage",
        "javax.swing.JComponent",
        "javax.swing.UIManager",
        "sun.awt.SunToolkit",
        "sun.java2d.SunGraphics2D",
    )

    /** Class and package prefixes that belong to java.desktop or to Compose's AWT layer. */
    private val awtPrefixes: List<String> = listOf(
        "java.awt.", "javax.swing.", "javax.accessibility.", "javax.imageio.", "javax.print.",
        "sun.awt.", "sun.java2d.", "sun.lwawt.", "sun.font.", "sun.swing.", "sun.print.",
        "com.apple.eawt.", "com.apple.laf.", "com.sun.swing.", "com.sun.java.swing.",
        "com.sun.imageio.", "apple.awt.", "apple.laf.",
        "androidx.compose.ui.awt.",
    )

    /** The same prefixes as resource paths. */
    private val awtPaths: List<String> = awtPrefixes.map { it.replace('.', '/') } + "META-INF/services/java.awt."

    private fun isAwtName(name: String): Boolean = awtPrefixes.any { name.startsWith(it) }

    private fun isAwtResource(entry: Map<*, *>): Boolean {
        if (entry["module"] == "java.desktop") return true
        val glob = entry["glob"] as? String
        if (glob != null && awtPaths.any { glob.startsWith(it) }) return true
        val bundle = entry["bundle"] as? String
        return bundle != null && isAwtName(bundle)
    }

    private fun typeName(entry: Map<*, *>): String? = when (val type = entry["type"]) {
        is String -> type
        is Map<*, *> -> (type["proxy"] as? List<*>)?.joinToString(",")
        else -> null
    }

    private fun isAwtEntry(entry: Any?): Boolean {
        if (entry !is Map<*, *>) return false
        if (entry.containsKey("glob") || entry.containsKey("bundle") || entry.containsKey("module")) return isAwtResource(entry)
        val name = typeName(entry) ?: (entry["name"] as? String) ?: return false
        return name.split(',').any { isAwtName(it) }
    }

    /**
     * The reachability metadata with every entry about AWT, Swing, Java 2D, the macOS AWT
     * extensions and Compose's AWT window layer removed. The tracing agent records them as a
     * run on GraalVM's JVM opens an AWT window, and each one makes the class it names
     * reachable, which an AwtFree image must not allow.
     */
    fun cleanMetadata(json: String): String {
        val root = JsonSlurper().parseText(json) as? Map<*, *> ?: return json
        val cleaned = LinkedHashMap<String, Any?>()
        for ((key, value) in root) {
            cleaned[key as String] = if (value is List<*>) value.filterNot(::isAwtEntry) else value
        }
        return JsonOutput.prettyPrint(JsonOutput.toJson(cleaned))
    }

    /** Copies [source] to [target], cleaning every reachability metadata file in it. */
    fun cleanMetadataDirectory(source: File, target: File) {
        target.deleteRecursively()
        source.copyRecursively(target, overwrite = true)
        target.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.forEach {
            it.writeText(cleanMetadata(it.readText()))
        }
    }

    /**
     * What a linked executable must not mention. The first three are the symbols of AWT's
     * native methods and of JAWT; the rest are the names of the libraries that hold them.
     */
    val forbiddenSymbols: List<String> = listOf(
        "Java_sun_awt_", "Java_sun_java2d_", "Java_java_awt_", "Java_sun_lwawt_", "Java_sun_font_",
        "JAWT_GetAWT", "libawt", "libjawt", "libosxui", "libfontmanager", "awt.dll",
    )

    /**
     * The shared libraries of the JDK's AWT that native-image copies beside an image when
     * AWT's natives are reachable from it, found by name in [directory]. An AwtFree image has
     * none, so one here is a second sign that AWT got in.
     */
    fun awtLibrariesBeside(directory: File): List<String> =
        directory.listFiles().orEmpty().map { it.name }.filter { name ->
            val base = name.removePrefix("lib").substringBefore('.')
            base in setOf("awt", "awt_xawt", "awt_headless", "jawt", "fontmanager", "osxui", "awt_lwawt", "javajpeg", "lcms", "mlib_image")
        }.sorted()

    /**
     * Searches [executable] for [forbiddenSymbols] and returns the ones that occur. A symbol
     * table is only one place a name can sit (a stripped image keeps its dynamic exports and
     * the strings of its image heap), so the whole file is searched.
     */
    fun scanExecutable(executable: File, symbols: List<String> = forbiddenSymbols): Set<String> {
        val patterns = symbols.associateWith { it.toByteArray(Charsets.ISO_8859_1) }
        val longest = patterns.values.maxOf { it.size }
        val found = linkedSetOf<String>()
        val chunk = ByteArray(8 * 1024 * 1024)
        executable.inputStream().buffered(chunk.size).use { input ->
            var carried = 0
            while (true) {
                val read = input.read(chunk, carried, chunk.size - carried)
                if (read < 0) break
                val end = carried + read
                for ((symbol, pattern) in patterns) {
                    if (symbol !in found && indexOf(chunk, end, pattern) >= 0) found += symbol
                }
                carried = minOf(longest - 1, end)
                System.arraycopy(chunk, end - carried, chunk, 0, carried)
            }
        }
        return found
    }

    private fun indexOf(data: ByteArray, end: Int, pattern: ByteArray): Int {
        val last = end - pattern.size
        var i = 0
        outer@ while (i <= last) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }
}
