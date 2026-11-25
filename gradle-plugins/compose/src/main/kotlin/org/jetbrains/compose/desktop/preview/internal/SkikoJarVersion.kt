/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.preview.internal

import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile

private const val VERSION_CLASS = "org/jetbrains/skiko/Version.class"
private val versionPattern = Regex("""\d+\.\d+\.\d+([-+][0-9A-Za-z.-]+)?""")

/**
 * Skiko's own version in a skiko-awt [jar], or null when it cannot be found.
 *
 * The version is read from `org.jetbrains.skiko.Version`, whose constants are skiko's version and
 * then Skia's (`0.144.6`, `m144-...`). The file name is only the fallback: this fork republishes
 * skiko-awt as `org.thisisthepy.compose.skiko:skiko-awt:<fork version>`, so its name carries the
 * fork's version, while the natives (`org.jetbrains.skiko:skiko-awt-runtime-<target>`) are still
 * JetBrains' at skiko's.
 */
internal fun skikoVersionOf(jar: File): String? {
    val fromClass = runCatching {
        ZipFile(jar).use { zip ->
            val entry = zip.getEntry(VERSION_CLASS) ?: return@use null
            zip.getInputStream(entry).use { utf8Constants(DataInputStream(it)) }
                .firstOrNull { versionPattern.matches(it) }
        }
    }.getOrNull()
    if (fromClass != null) return fromClass
    // A jar that is not one (or any read error) gives nothing rather than a guess from its name.
    if (runCatching { ZipFile(jar).close() }.isFailure) return null
    return jar.name.removePrefix("skiko-awt-").removeSuffix(".jar").takeIf { versionPattern.matches(it) }
}

/** The CONSTANT_Utf8 entries of a class file's constant pool, in order (JVMS 4.4). */
private fun utf8Constants(input: DataInputStream): List<String> {
    if (input.readInt() != 0xCAFEBABE.toInt()) return emptyList()
    input.readUnsignedShort() // minor version
    input.readUnsignedShort() // major version
    val count = input.readUnsignedShort()
    val strings = ArrayList<String>()
    var index = 1
    while (index < count) {
        when (val tag = input.readUnsignedByte()) {
            1 -> strings += input.readUTF()
            3, 4 -> input.skipBytes(4)
            5, 6 -> { input.skipBytes(8); index++ } // a long or a double takes two entries
            7, 8, 16, 19, 20 -> input.skipBytes(2)
            9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
            15 -> input.skipBytes(3)
            else -> error("constant pool tag $tag")
        }
        index++
    }
    return strings
}
