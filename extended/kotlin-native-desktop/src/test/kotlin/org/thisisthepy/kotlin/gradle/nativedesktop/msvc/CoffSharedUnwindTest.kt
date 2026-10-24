/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.thisisthepy.kotlin.gradle.nativedesktop.msvc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CoffSharedUnwindTest {
    private val comdat = 0x1000
    private val textFlags = 0x60500020 or comdat
    private val pdataFlags = 0x40300040 or comdat

    /**
     * One object: two COMDAT `.text` sections (selection "no duplicates") and, when [shared], one
     * COMDAT `.pdata$` whose two entries cover both, the way LLVM writes functions it puts in
     * sections named plain `.text`. Otherwise the `.pdata$` covers the first only.
     */
    private fun object_(shared: Boolean): ByteArray {
        val sections = 3
        val headers = 20 + sections * 40
        val pdataSize = if (shared) 24 else 12
        val relocs = if (shared) 4 else 2
        val relocAt = headers + pdataSize
        val symbolsAt = relocAt + relocs * 10
        val symbols = 6
        val b = ByteBuffer.allocate(symbolsAt + symbols * 18 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(0x8664.toShort()).putShort(sections.toShort()).putInt(0).putInt(symbolsAt).putInt(symbols).putShort(0).putShort(0)
        fun header(name: String, size: Int, raw: Int, reloc: Int, nreloc: Int, flags: Int) {
            name.padEnd(8, '\u0000').forEach { b.put(it.code.toByte()) }
            b.putInt(0).putInt(0).putInt(size).putInt(raw).putInt(reloc).putInt(0).putShort(nreloc.toShort()).putShort(0).putInt(flags)
        }
        header(".text", 0, 0, 0, 0, textFlags)
        header(".text", 0, 0, 0, 0, textFlags)
        header(".pdata$", pdataSize, headers, relocAt, relocs, pdataFlags)
        repeat(pdataSize) { b.put(0) }
        // Entry 0 covers section 1 (symbol 0); entry 1, when shared, covers section 2 (symbol 2).
        b.putInt(0).putInt(0).putShort(3)
        b.putInt(4).putInt(0).putShort(3)
        if (shared) {
            b.putInt(12).putInt(2).putShort(3)
            b.putInt(16).putInt(2).putShort(3)
        }
        fun symbol(name: String, section: Int, selection: Int, associated: Int) {
            name.padEnd(8, '\u0000').forEach { b.put(it.code.toByte()) }
            b.putInt(0).putShort(section.toShort()).putShort(0).put(3).put(1)
            b.putInt(0).putShort(0).putShort(0).putInt(0).putShort(associated.toShort()).put(selection.toByte()).put(0).put(0).put(0)
        }
        symbol(".text", 1, 1, 0)
        symbol(".text", 2, 1, 0)
        symbol(".pdata$", 3, 2, 0)
        b.putInt(4)
        return b.array()
    }

    private fun archive(body: ByteArray): ByteArray {
        val header = ("member.o/".padEnd(16) + "0".padEnd(12) + "0".padEnd(6) + "0".padEnd(6) + "644".padEnd(8) +
            body.size.toString().padEnd(10) + "`\n").toByteArray(Charsets.ISO_8859_1)
        return "!<arch>\n".toByteArray(Charsets.ISO_8859_1) + header + body + if (body.size % 2 == 1) byteArrayOf(10) else byteArrayOf()
    }

    private fun flags(archive: ByteArray, section: Int) =
        ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN).getInt(8 + 60 + 20 + (section - 1) * 40 + 36)

    @Test
    fun `unwind data covering several COMDATs and that code become ordinary sections`() {
        val archive = archive(object_(shared = true))
        val result = CoffMingwFixer.fixArchive(archive)
        assertEquals(1, result.sharedUnwindSections)
        assertEquals(0, result.associativeUnwindSections)
        for (section in 1..3) assertEquals(0, flags(archive, section) and comdat, "section $section is still a COMDAT")
    }

    @Test
    fun `unwind data covering one COMDAT is tied to it by its relocation`() {
        val archive = archive(object_(shared = false))
        val result = CoffMingwFixer.fixArchive(archive)
        assertEquals(0, result.sharedUnwindSections)
        assertEquals(1, result.associativeUnwindSections)
        for (section in 1..3) assertEquals(comdat, flags(archive, section) and comdat)
        // The .pdata's aux record now selects "associative" with section 1.
        val aux = 8 + 60 + (object_(false).size - 4 - 6 * 18) + 5 * 18
        val buffer = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, buffer.getShort(aux + 12).toInt())
        assertEquals(5, archive[aux + 14].toInt())
    }
}
