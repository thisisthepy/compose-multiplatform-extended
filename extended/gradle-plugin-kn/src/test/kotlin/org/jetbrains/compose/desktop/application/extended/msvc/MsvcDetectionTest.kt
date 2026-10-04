/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.extended.msvc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

private class FakeHost(
    private val installs: List<String>,
    private val files: Set<String>,
    private val directories: Map<String, List<String>>,
) : MsvcHost {
    override val programFilesX86 = "C:/Program Files (x86)"
    override fun vswhereInstallPaths() = installs
    override fun isFile(path: String) = path in files
    override fun isDirectory(path: String) = directories.containsKey(path) || files.any { it.startsWith("$path/") }
    override fun listDirectory(path: String) = directories[path].orEmpty()
}

class MsvcDetectionTest {
    private val vs = "C:/VS/2026/Community"

    private fun host(
        toolsets: List<String> = listOf("14.44.35207", "14.51.36014"),
        clang: Boolean = true,
        sdk: Boolean = true,
    ): FakeHost {
        val root = "$vs/VC/Tools/MSVC"
        val files = mutableSetOf<String>()
        toolsets.forEach { files += "$root/$it/bin/Hostx64/x64/link.exe" }
        if (clang) files += "$vs/VC/Tools/Llvm/x64/bin/clang-cl.exe"
        if (sdk) files += "C:/Program Files (x86)/Windows Kits/10/Include/10.0.26100.0/um/windows.h"
        val directories = mutableMapOf(
            root to toolsets,
            "C:/Program Files (x86)/Windows Kits/10/Include" to if (sdk) listOf("10.0.22621.0", "10.0.26100.0") else emptyList(),
        )
        return FakeHost(listOf(vs), files, directories)
    }

    @Test
    fun `parses vswhere output with several installations`() {
        val output = "C:\\Program Files\\Microsoft Visual Studio\\2022\\BuildTools\r\n\r\nC:\\VS\\2026\\Community\r\n"
        assertEquals(
            listOf("C:/Program Files/Microsoft Visual Studio/2022/BuildTools", "C:/VS/2026/Community"),
            parseVswhereInstallPaths(output),
        )
    }

    @Test
    fun `ignores vswhere noise that is not a path`() {
        assertEquals(emptyList<String>(), parseVswhereInstallPaths("Error 0x57: bad argument\n"))
    }

    @Test
    fun `picks the newest toolset and the newest SDK`() {
        val toolchain = MsvcDetector.locate(host())
        assertEquals("14.51.36014", toolchain.toolsetVersion)
        assertEquals("10.0.26100.0", toolchain.windowsSdkVersion)
        assertEquals("$vs/VC/Tools/Llvm/x64/bin/clang-cl.exe", toolchain.clangCl)
        assertEquals("$vs/VC/Tools/MSVC/14.51.36014/bin/Hostx64/x64/link.exe", toolchain.link)
    }

    @Test
    fun `a minimum newer than every toolset says what was found`() {
        val error = assertThrows(MsvcNotFoundException::class.java) {
            MsvcDetector.locate(host(), MsvcRequirements(minimumToolset = "14.60"))
        }
        assertTrue(error.message!!.contains("14.51.36014"))
        assertTrue(error.message!!.contains("14.60"))
        assertTrue(error.message!!.contains("Verified MSVC toolset versions"))
    }

    @Test
    fun `a minimum is satisfied by an older toolset being skipped`() {
        val toolchain = MsvcDetector.locate(host(), MsvcRequirements(minimumToolset = "14.50"))
        assertEquals("14.51.36014", toolchain.toolsetVersion)
    }

    @Test
    fun `no Visual Studio gives the install message`() {
        val error = assertThrows(MsvcNotFoundException::class.java) {
            MsvcDetector.locate(FakeHost(emptyList(), emptySet(), emptyMap()))
        }
        assertTrue(error.message!!.contains("Desktop development with C++"))
    }

    @Test
    fun `clang-cl is optional unless required`() {
        val without = host(clang = false)
        assertEquals(null, MsvcDetector.locate(without).clangCl)
        val error = assertThrows(MsvcNotFoundException::class.java) {
            MsvcDetector.locate(without, MsvcRequirements(requireClangCl = true))
        }
        assertTrue(error.message!!.contains("clang-cl"))
    }

    @Test
    fun `a missing Windows SDK is reported`() {
        val error = assertThrows(MsvcNotFoundException::class.java) { MsvcDetector.locate(host(sdk = false)) }
        assertTrue(error.message!!.contains("Windows"))
    }

    @Test
    fun `version comparison is numeric`() {
        assertTrue(MsvcDetector.compareVersions("14.51.1", "14.5") > 0)
        assertTrue(MsvcDetector.compareVersions("14.9", "14.10") < 0)
        assertEquals(0, MsvcDetector.compareVersions("14.51", "14.51.0"))
    }

    @Test
    fun `the install hint names the verified range`() {
        assertFalse(MsvcDetector.installHint("x").contains("older than"))
        assertTrue(MsvcDetector.installHint("x").contains(MsvcVerifiedVersions.HIGHEST))
    }
}
