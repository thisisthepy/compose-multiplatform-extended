/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.thisisthepy.kotlin.gradle.nativedesktop.msvc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A one-member archive holding a COFF object with one section, named and flagged as given. */
internal fun archiveWithSection(sectionName: String, characteristics: Int): ByteArray {
    val object_ = ByteBuffer.allocate(20 + 40 + 4).order(ByteOrder.LITTLE_ENDIAN)
    object_.putShort(0x8664.toShort()).putShort(1).putInt(0).putInt(20 + 40).putInt(0).putShort(0).putShort(0)
    sectionName.padEnd(8, '\u0000').forEach { object_.put(it.code.toByte()) }
    object_.putInt(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0).putShort(0).putShort(0).putInt(characteristics)
    object_.putInt(4)
    val body = object_.array()
    val header = ("member.o/".padEnd(16) + "0".padEnd(12) + "0".padEnd(6) + "0".padEnd(6) + "644".padEnd(8) +
        body.size.toString().padEnd(10) + "`\n").toByteArray(Charsets.ISO_8859_1)
    return "!<arch>\n".toByteArray(Charsets.ISO_8859_1) + header + body + if (body.size % 2 == 1) byteArrayOf(10) else byteArrayOf()
}

class CoffMingwFixerTest {
    @Test
    fun `constructors move to the section the MSVC runtime runs, with its flags`() {
        val archive = archiveWithSection(".ctors", 0xC0300040.toInt())
        val result = CoffMingwFixer.fixArchive(archive)
        assertEquals(1, result.constructorSections)
        val name = String(archive, 8 + 60 + 20, 8, Charsets.ISO_8859_1)
        assertEquals(".CRT\$XCU", name)
        val flags = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN).getInt(8 + 60 + 20 + 36)
        assertEquals(0x40400040, flags)
    }

    @Test
    fun `an object with nothing to rewrite is left alone`() {
        val archive = archiveWithSection(".text", 0x60500020)
        val before = archive.copyOf()
        val result = CoffMingwFixer.fixArchive(archive)
        assertEquals(0, result.constructorSections)
        assertTrue(before.contentEquals(archive))
    }

    @Test
    fun `a file that is not an archive is refused`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            CoffMingwFixer.fixArchive(ByteArray(64))
        }
    }
}

class RuntimeDirectivesTest {
    private fun blank(text: String): Pair<String, RuntimeDirectives.Blanked> {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        val result = RuntimeDirectives.blank(bytes)
        return String(bytes, Charsets.ISO_8859_1) to result
    }

    @Test
    fun `the guard and the static default libraries become spaces of the same length`() {
        val text = "x /FAILIFMISMATCH:\"RuntimeLibrary=MT_StaticRelease\" /DEFAULTLIB:\"LIBCMT\" -defaultlib:libcpmt y"
        val (out, found) = blank(text)
        assertEquals(3, found.directives)
        assertEquals(text.length, out.length)
        assertEquals("x", out.trim().substring(0, 1))
        assertTrue(out.trim().endsWith("y"))
        assertTrue(!out.contains("FAILIFMISMATCH") && !out.contains("LIBCMT", ignoreCase = true))
    }

    @Test
    fun `other default libraries are kept`() {
        val (out, found) = blank("/DEFAULTLIB:kernel32.lib")
        assertEquals(0, found.directives)
        assertEquals("/DEFAULTLIB:kernel32.lib", out)
    }

    @Test
    fun `a debug runtime is reported and not blanked`() {
        val (out, found) = blank("/FAILIFMISMATCH:\"RuntimeLibrary=MDd_DynamicDebug\"")
        assertEquals(listOf("MDd_DynamicDebug"), found.unreconcilable)
        assertTrue(out.contains("FAILIFMISMATCH"))
    }
}

class MsvcLinkPipelineTest {
    private val toolchain = MsvcToolchain(
        "C:/VS", "14.51.1", "C:/VS/VC/Tools/MSVC/14.51.1", "10.0.26100.0", "C:/Kits/10", "C:/VS/VC/Tools/Llvm/x64/bin/clang-cl.exe",
    )

    private fun spec(dir: File, configure: (MsvcLinkSpec) -> MsvcLinkSpec = { it }) = configure(
        MsvcLinkSpec(
            name = "hello",
            toolchain = toolchain,
            staticLibrary = File(dir, "libhello.a"),
            gccRuntimeDir = File(dir, "gcc"),
            workDir = File(dir, "work"),
            outputFile = File(dir, "out/hello.exe"),
        )
    )

    @Test
    fun `the default link uses the UCRT, a static vcruntime and no C++ library`(@TempDir dir: File) {
        val commands = MsvcLinkPipeline(spec(dir)).commands()
        assertEquals(listOf("compile the MinGW bridge", "compile the entry point", "link hello.exe"), commands.map { it.description })
        val link = commands.last()
        assertEquals("C:/VS/VC/Tools/MSVC/14.51.1/bin/Hostx64/x64/link.exe", link.executable)
        assertTrue("/SUBSYSTEM:CONSOLE" in link.arguments)
        assertTrue("libvcruntime.lib" in link.arguments)
        assertTrue("/NODEFAULTLIB:vcruntime.lib" in link.arguments)
        assertTrue("/INCLUDE:longjmp" in link.arguments)
        assertTrue(link.arguments.none { it.contains("libcpmt") })
        assertTrue(link.arguments.any { it.endsWith("work/libhello.a") })
        assertTrue(link.arguments.any { it.endsWith("work/gcc/libstdc++.a") })
    }

    @Test
    fun `the bridge is compiled without naming a runtime library`(@TempDir dir: File) {
        val bridge = MsvcLinkPipeline(spec(dir)).commands().first()
        assertTrue("/Zl" in bridge.arguments)
        assertTrue("-D_ALLOW_RUNTIME_LIBRARY_MISMATCH" in bridge.arguments)
    }

    @Test
    fun `the compiler finds its headers and libraries without a developer prompt`(@TempDir dir: File) {
        val env = MsvcLinkPipeline(spec(dir)).commands().first().environment
        assertTrue(env.getValue("INCLUDE").contains("C:/Kits/10/Include/10.0.26100.0/ucrt"))
        assertTrue(env.getValue("LIB").contains("C:/Kits/10/Lib/10.0.26100.0/um/x64"))
    }

    @Test
    fun `the C++ library is linked, guard blanked, only when asked`(@TempDir dir: File) {
        val link = MsvcLinkPipeline(spec(dir) { it.copy(linkCppStandardLibrary = true) }).commands().last()
        assertTrue(link.arguments.any { it.endsWith("kotlin-msvc-libcpmt.lib") })
        assertTrue("/NODEFAULTLIB:libcpmt.lib" in link.arguments)
        assertTrue("/NODEFAULTLIB:msvcprt.lib" in link.arguments)
    }

    @Test
    fun `the windows subsystem gets a WinMain entry and an icon resource step`(@TempDir dir: File) {
        val pipeline = MsvcLinkPipeline(spec(dir) { it.copy(subsystem = WindowsSubsystem.Windows, icon = File(dir, "a.ico"), manifest = File(dir, "a.manifest")) })
        assertTrue(pipeline.entrySourceText().contains("WinMain"))
        val commands = pipeline.commands()
        assertTrue(commands.any { it.description == "compile the icon resource" })
        val link = commands.last().arguments
        assertTrue("/SUBSYSTEM:WINDOWS" in link)
        assertTrue("/MANIFEST:EMBED" in link)
    }

    @Test
    fun `a console entry is a plain main that calls Kotlin`(@TempDir dir: File) {
        val text = MsvcLinkPipeline(spec(dir)).entrySourceText()
        assertTrue(text.contains("int main(int argc, char **argv)"))
        assertTrue(text.contains("kotlin_msvc_entry"))
    }

    @Test
    fun `run prepares rewritten copies and starts the commands in order`(@TempDir dir: File) {
        File(dir, "libhello.a").writeBytes(archiveWithSection(".ctors", 0xC0300040.toInt()))
        File(dir, "gcc").mkdirs()
        for (name in listOf("libstdc++.a", "libgcc.a", "libgcc_eh.a", "libwinpthread.a")) {
            File(dir, "gcc/$name").writeBytes(archiveWithSection(".ctors", 0xC0300040.toInt()))
        }
        val ran = mutableListOf<String>()
        MsvcLinkPipeline(spec(dir), { command -> ran += command.description; 0 to "" }).run()
        assertEquals(3, ran.size)
        val copy = File(dir, "work/libhello.a").readBytes()
        assertEquals(".CRT\$XCU", String(copy, 8 + 60 + 20, 8, Charsets.ISO_8859_1))
        assertTrue(File(dir, "work/entry.c").isFile)
        assertTrue(File(dir, "work/mingw_bridge.c").readText().contains("__mingw_vsnprintf"))
        // The original is untouched.
        assertEquals(".ctors", String(File(dir, "libhello.a").readBytes(), 8 + 60 + 20, 6, Charsets.ISO_8859_1))
    }

    @Test
    fun `a failing command ends the link with its output`(@TempDir dir: File) {
        File(dir, "libhello.a").writeBytes(archiveWithSection(".text", 0x60500020))
        File(dir, "gcc").mkdirs()
        for (name in listOf("libstdc++.a", "libgcc.a", "libgcc_eh.a", "libwinpthread.a")) File(dir, "gcc/$name").writeBytes(archiveWithSection(".text", 0x60500020))
        val error = org.junit.jupiter.api.Assertions.assertThrows(MsvcLinkException::class.java) {
            MsvcLinkPipeline(spec(dir), { 2 to "LNK1120: 1 unresolved externals" }).run()
        }
        assertTrue(error.message!!.contains("LNK1120"))
    }

    @Test
    fun `the generated Kotlin entry calls the entry point`() {
        assertTrue(MsvcEntryCode.kotlinSource("hello.main", false).contains("hello.main()"))
        assertTrue(MsvcEntryCode.kotlinSource("hello.main", true).contains("hello.main(Array(argc)"))
        assertTrue(MsvcEntryCode.kotlinSource("main", false).contains("@CName(\"kotlin_msvc_entry\")"))
        assertTrue(MsvcEntryCode.kotlinSource("main", false, "entry_two").contains("fun entry_two("))
    }

    @Test
    fun `main with a command line is told from main without one`() {
        val with = "package hello\n\nfun main(args: Array<String>) {\n}\n"
        val without = "package hello\n\nfun main() {\n}\n"
        assertTrue(MainSignature.takesArguments("hello.main", listOf(with)))
        assertEquals(false, MainSignature.takesArguments("hello.main", listOf(without)))
        assertEquals(false, MainSignature.takesArguments("hello.main", listOf("package other\n\nfun main(args: Array<String>) {}")))
        assertTrue(MainSignature.takesArguments("main", listOf("fun main(args : Array<String>) {}")))
        assertEquals(false, MainSignature.takesArguments("hello.main", emptyList()))
    }
}
