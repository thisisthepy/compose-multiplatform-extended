/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.thisisthepy.kotlin.gradle.nativedesktop.msvc

/**
 * Blanking of the C runtime directives that MSVC objects carry.
 *
 * Every C++ object the MSVC compiler writes holds a guard,
 * `/FAILIFMISMATCH:"RuntimeLibrary=MT_StaticRelease"`, which fails the link (LNK2038) beside an
 * object that says anything else, and default library names (`/DEFAULTLIB:"LIBCMT"`,
 * `/DEFAULTLIB:"libcpmt"`) that pull the static runtime in beside the DLL one, where the two
 * define the same functions twice. Linking a copy of the library with those directives
 * blanked lets the application, and not whichever object the linker reads first, decide the
 * runtime. The guard exists for code that hands runtime objects across a library boundary
 * (a `FILE*`, memory one side allocates and the other frees), so blanking it is right for
 * libraries that meet only in C calls with numbers and pointers.
 *
 * Each directive becomes spaces of the same length, so no offset in the file moves.
 */
object RuntimeDirectives {
    class Blanked(val directives: Int, val unreconcilable: List<String>)

    private val reconcilable = setOf("mt_staticrelease", "md_dynamicrelease")
    private val staticDefaults = setOf("libcmt", "libcmt.lib", "libcpmt", "libcpmt.lib")

    fun blank(bytes: ByteArray): Blanked {
        var count = 0
        val bad = LinkedHashSet<String>()
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt()
            if (b != '/'.code && b != '-'.code) {
                i++
                continue
            }
            val end = matchDirective(bytes, i, bad)
            if (end != null) {
                for (k in i until end) bytes[k] = ' '.code.toByte()
                count++
                i = end
            } else {
                i++
            }
        }
        return Blanked(count, bad.toList())
    }

    private fun matchDirective(bytes: ByteArray, start: Int, bad: MutableSet<String>): Int? {
        val after = start + 1
        keyword(bytes, after, "failifmismatch:")?.let { valueAt ->
            val (value, end) = token(bytes, valueAt) ?: return null
            val eq = value.indexOf('=')
            if (eq < 0) return null
            val key = value.substring(0, eq)
            val setting = value.substring(eq + 1)
            if (!key.equals("RuntimeLibrary", ignoreCase = true)) return null
            if (setting.lowercase() in reconcilable) return end
            bad += setting
            return null
        }
        keyword(bytes, after, "defaultlib:")?.let { valueAt ->
            val (value, end) = token(bytes, valueAt) ?: return null
            if (value.lowercase() in staticDefaults) return end
        }
        return null
    }

    private fun keyword(bytes: ByteArray, at: Int, word: String): Int? {
        val end = at + word.length
        if (end > bytes.size) return null
        for (k in word.indices) {
            if (bytes[at + k].toInt().toChar().lowercaseChar() != word[k]) return null
        }
        return end
    }

    private fun token(bytes: ByteArray, at: Int): Pair<String, Int>? {
        if (at >= bytes.size) return null
        val quoted = bytes[at].toInt() == '"'.code
        val from = if (quoted) at + 1 else at
        var end = from
        while (end < bytes.size) {
            val c = bytes[end].toInt()
            val stop = if (quoted) c == '"'.code else c == ' '.code || c == 0 || c == '\t'.code || c == '\n'.code || c == '\r'.code
            if (stop) break
            if (c < 0x20 || c >= 0x7f) return null
            end++
        }
        if (end == from || (quoted && end >= bytes.size)) return null
        return String(bytes, from, end - from, Charsets.ISO_8859_1) to (if (quoted) end + 1 else end)
    }

    fun debugRuntimeMessage(library: String, settings: List<String>): String =
        "$library was built against the debug C runtime (${settings.joinToString()}). " +
            "An executable links one C runtime, the release one, and the debug runtime lays out the C++ " +
            "library differently, so the two cannot share a program (LNK2038). " +
            "Rebuild that library with /MT or /MD, not /MTd or /MDd."
}
