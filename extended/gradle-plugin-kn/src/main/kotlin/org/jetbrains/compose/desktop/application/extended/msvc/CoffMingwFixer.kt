/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.extended.msvc

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Makes MinGW-built COFF objects mean the same thing to MSVC's linker, in place.
 *
 * Two MinGW conventions differ from MSVC's and both are rewritten:
 *
 * 1. Static constructors. MinGW puts them in `.ctors` and runs them from its own startup code.
 *    The MSVC runtime runs the pointers it finds in `.CRT$XCU`, so the section is renamed and
 *    given the flags MSVC gives that section. With any other flags the linker keeps the two
 *    groups apart and the runtime never runs the constructors in one of them. Every `.CRT$`
 *    section gets the flags, not only the constructors, because winpthread's TLS callbacks sit
 *    in `.CRT$XL*` with the same problem.
 * 2. Unwind data. For a function in COMDAT section `.text$NAME`, MinGW writes `.pdata$NAME` and
 *    `.xdata$NAME` as COMDATs of their own and its linker pairs them with the code by name.
 *    MSVC's linker follows the COFF rules instead, sees two sections nothing refers to and drops
 *    them, and an exception passing through the function is then unwound as if it were a leaf.
 *    Marking them associative to `.text$NAME` says that they live and die with that code.
 *
 * Both the ordinary COFF layout and the "big object" layout (32-bit section numbers, which
 * Kotlin/Native writes once an object has more sections than 16 bits can number) are handled.
 */
object CoffMingwFixer {
    data class Result(val constructorSections: Int, val associativeUnwindSections: Int)

    private const val CRT_CHARACTERISTICS = 0x40400040
    private const val SELECT_ASSOCIATIVE = 5
    private const val MACHINE_AMD64 = 0x8664

    private class Layout(
        val sectionTable: Int,
        val sectionCount: Int,
        val symbolTable: Int,
        val symbolCount: Int,
        val symbolSize: Int,
    )

    /** Rewrites the `.a` archive at [bytes] in place and says what was changed. */
    fun fixArchive(bytes: ByteArray): Result {
        require(bytes.size >= 8 && String(bytes, 0, 8, Charsets.ISO_8859_1) == "!<arch>\n") {
            "not a static library archive"
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 8
        var ctors = 0
        var associated = 0
        while (pos + 60 <= bytes.size) {
            val size = String(bytes, pos + 48, 10, Charsets.ISO_8859_1).trim().toIntOrNull() ?: break
            val body = pos + 60
            if (body + size <= bytes.size) {
                val result = patchObject(buf, body)
                ctors += result.constructorSections
                associated += result.associativeUnwindSections
            }
            pos = body + size + (size and 1)
        }
        return Result(ctors, associated)
    }

    private fun layout(buf: ByteBuffer, base: Int): Layout? {
        if (base + 20 > buf.capacity()) return null
        val machine = buf.getShort(base).toInt() and 0xFFFF
        if (machine == MACHINE_AMD64) {
            val sections = buf.getShort(base + 2).toInt() and 0xFFFF
            val symPtr = buf.getInt(base + 8)
            val symCount = buf.getInt(base + 12)
            val optional = buf.getShort(base + 16).toInt() and 0xFFFF
            return Layout(base + 20 + optional, sections, base + symPtr, symCount, 18)
        }
        if (base + 56 > buf.capacity()) return null
        val sig1 = buf.getShort(base).toInt() and 0xFFFF
        val sig2 = buf.getShort(base + 2).toInt() and 0xFFFF
        val version = buf.getShort(base + 4).toInt() and 0xFFFF
        val bigMachine = buf.getShort(base + 6).toInt() and 0xFFFF
        if (sig1 == 0 && sig2 == 0xFFFF && version >= 2 && bigMachine == MACHINE_AMD64) {
            return Layout(base + 56, buf.getInt(base + 44), base + buf.getInt(base + 48), buf.getInt(base + 52), 20)
        }
        return null
    }

    private fun sectionName(buf: ByteBuffer, at: Int, stringTable: Int): String {
        val raw = ByteArray(8) { buf.get(at + it) }
        val end = raw.indexOf(0).let { if (it < 0) 8 else it }
        val name = String(raw, 0, end, Charsets.ISO_8859_1)
        val offset = when {
            name.startsWith("//") -> {
                val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
                name.drop(2).fold(0) { acc, c -> acc * 64 + alphabet.indexOf(c) }
            }
            name.startsWith("/") -> name.drop(1).toIntOrNull() ?: return name
            else -> return name
        }
        var e = stringTable + offset
        val sb = StringBuilder()
        while (e < buf.capacity() && buf.get(e).toInt() != 0) sb.append(buf.get(e++).toInt().toChar())
        return sb.toString()
    }

    private fun matches(buf: ByteBuffer, at: Int, text: String): Boolean {
        for (i in 0 until 8) {
            val want = if (i < text.length) text[i].code else 0
            if ((buf.get(at + i).toInt() and 0xFF) != want) return false
        }
        return true
    }

    private fun writeName(buf: ByteBuffer, at: Int, text: String) {
        for (i in 0 until 8) buf.put(at + i, (if (i < text.length) text[i].code else 0).toByte())
    }

    private fun patchObject(buf: ByteBuffer, base: Int): Result {
        val l = layout(buf, base) ?: return Result(0, 0)
        val big = l.symbolSize == 20
        val stringTable = l.symbolTable + l.symbolCount * l.symbolSize
        val names = ArrayList<String>(l.sectionCount)
        var ctors = 0
        for (i in 0 until l.sectionCount) {
            val at = l.sectionTable + i * 40
            if (matches(buf, at, ".ctors")) {
                writeName(buf, at, ".CRT\$XCU")
                ctors++
            }
            names += sectionName(buf, at, stringTable)
            if (names.last().startsWith(".CRT$")) buf.putInt(at + 36, CRT_CHARACTERISTICS)
        }
        val textIndex = HashMap<String, Int>()
        names.forEachIndexed { i, n -> if (n.startsWith(".text$")) textIndex[n.removePrefix(".text$")] = i + 1 }
        if ("" !in textIndex) names.indexOf(".text").let { if (it >= 0) textIndex[""] = it + 1 }
        var associated = 0
        var i = 0
        while (i < l.symbolCount) {
            val at = l.symbolTable + i * l.symbolSize
            if (matches(buf, at, ".ctors")) writeName(buf, at, ".CRT\$XCU")
            val sectionNumber: Int
            val storage: Int
            val aux: Int
            if (big) {
                sectionNumber = buf.getInt(at + 12)
                storage = buf.get(at + 18).toInt() and 0xFF
                aux = buf.get(at + 19).toInt() and 0xFF
            } else {
                sectionNumber = buf.getShort(at + 12).toInt()
                storage = buf.get(at + 16).toInt() and 0xFF
                aux = buf.get(at + 17).toInt() and 0xFF
            }
            if (storage == 3 && aux != 0 && sectionNumber > 0 && sectionNumber <= names.size) {
                val name = names[sectionNumber - 1]
                for (prefix in listOf(".pdata$", ".xdata$")) {
                    if (!name.startsWith(prefix)) continue
                    val target = textIndex[name.removePrefix(prefix)]
                    val characteristics = buf.getInt(l.sectionTable + (sectionNumber - 1) * 40 + 36)
                    if (target != null && (characteristics and 0x1000) != 0) {
                        val a = at + l.symbolSize
                        buf.putShort(a + 12, (target and 0xFFFF).toShort())
                        buf.put(a + 14, SELECT_ASSOCIATIVE.toByte())
                        if (big) buf.putShort(a + 16, (target ushr 16).toShort())
                        associated++
                    }
                }
            }
            i += 1 + aux
        }
        return Result(ctors, associated)
    }
}
