package org.jetbrains.compose.test.tests.unit

import org.jetbrains.compose.desktop.application.internal.MsvcHost
import org.jetbrains.compose.desktop.application.internal.MsvcNotFoundException
import org.jetbrains.compose.desktop.application.internal.compareVersions
import org.jetbrains.compose.desktop.application.internal.locateMsvc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MsvcToolchainTest {
    private class FakeHost(
        val install: String?,
        val files: Set<String>,
        val dirs: Map<String, List<String>>
    ) : MsvcHost {
        override val programFilesX86 = "C:/PF86"
        override fun vswhereInstallPath() = install
        override fun isFile(path: String) = path in files
        override fun isDirectory(path: String) = dirs.containsKey(path) || files.any { it.startsWith("$path/") }
        override fun listDirectory(path: String) = dirs[path].orEmpty()
    }

    private val vs = "C:/VS"
    private fun host(toolset: String = "14.51.36014", clang: Boolean = true, sdk: Boolean = true) = FakeHost(
        vs,
        buildSet {
            if (clang) add("$vs/VC/Tools/Llvm/x64/bin/clang-cl.exe")
            if (sdk) add("C:/PF86/Windows Kits/10/Include/10.0.26100.0/um/windows.h")
        },
        mapOf(
            "$vs/VC/Tools/MSVC" to listOf("14.44.1", toolset),
            "$vs/VC/Tools/MSVC/$toolset" to emptyList(),
            "$vs/VC/Tools/MSVC/14.44.1" to emptyList(),
            "C:/PF86/Windows Kits/10/Include" to listOf("10.0.26100.0")
        )
    )

    @Test
    fun findsNewestToolsetClangAndSdk() {
        val t = locateMsvc(host())
        assertEquals("14.51.36014", t.toolsetVersion)
        assertEquals("10.0.26100.0", t.windowsSdkVersion)
        assertTrue(t.clangCl.endsWith("clang-cl.exe"))
    }

    @Test
    fun missingVisualStudioExplainsHowToInstall() {
        val e = assertFailsWith<MsvcNotFoundException> { locateMsvc(FakeHost(null, emptySet(), emptyMap())) }
        assertTrue("Install Visual Studio Build Tools" in e.message!!)
    }

    @Test
    fun oldToolsetIsRejected() {
        val h = FakeHost(vs, emptySet(), mapOf("$vs/VC/Tools/MSVC" to listOf("14.44.1"), "$vs/VC/Tools/MSVC/14.44.1" to emptyList()))
        val e = assertFailsWith<MsvcNotFoundException> { locateMsvc(h) }
        assertTrue("older than 14.51" in e.message!!)
    }

    @Test
    fun missingClangIsReported() {
        assertTrue("clang-cl" in assertFailsWith<MsvcNotFoundException> { locateMsvc(host(clang = false)) }.message!!)
    }

    @Test
    fun missingSdkIsReported() {
        assertTrue("Windows SDK" in assertFailsWith<MsvcNotFoundException> { locateMsvc(host(sdk = false)) }.message!!)
    }

    @Test
    fun versionsCompareNumerically() {
        assertTrue(compareVersions("14.51.1", "14.9") > 0)
        assertEquals(0, compareVersions("14.51", "14.51.0"))
    }
}
