/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.test.tests.unit

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The manifest packageNativeImage embeds on Windows. Without one a process is DPI unaware,
 * and Windows draws the window at 96 DPI and stretches the bitmap to a scaled display, which
 * is what blurs the text. What it declares is what the JDK's own java.exe declares, so an
 * image behaves the way the same application does on the JVM.
 */
class NativeImageWindowsManifestTest {
    private val manifest: String =
        NativeImageWindowsManifestTest::class.java
            .getResource("/org/jetbrains/compose/desktop/nativeimage/windows-app.manifest")
            ?.readText()
            ?: error("the plugin is missing its windows-app.manifest resource")

    @Test
    fun itIsWellFormedXml() {
        // The linker rejects a manifest it cannot parse, and the failure is late and obscure.
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest.byteInputStream())
    }

    @Test
    fun itAsksForPerMonitorDpiAwareness() {
        // Both spellings: the 2005 one is what Windows 8.1 reads, the 2016 one carries V2.
        assertTrue("<dpi1:dpiAware>true/PM</dpi1:dpiAware>" in manifest, manifest)
        assertTrue("PerMonitorV2" in manifest, manifest)
    }

    @Test
    fun itRunsAsTheInvokerAndNotElevated() {
        assertTrue("""level="asInvoker"""" in manifest, manifest)
        assertTrue("""uiAccess="false"""" in manifest, manifest)
    }

    @Test
    fun itTakesCommonControls6() {
        assertTrue("Microsoft.Windows.Common-Controls" in manifest, manifest)
        assertTrue("""version="6.0.0.0"""" in manifest, manifest)
    }

    /**
     * Without these a process is told it is on Windows 8, and the version AWT reads is wrong.
     * The five are the ids java.exe lists, Windows 7 through 10 and later.
     */
    @Test
    fun itDeclaresTheSupportedWindowsVersions() {
        for (id in listOf(
            "{e2011457-1546-43c5-a5fe-008deee3d3f0}",
            "{35138b9a-5d96-4fbd-8e2d-a2440225f93a}",
            "{4a2f28e3-53b9-4441-ba9c-d69d4a4a6e38}",
            "{1f676c76-80e1-4239-95bb-83d0f6d0da78}",
            "{8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a}",
        )) {
            assertTrue(id in manifest, "supportedOS $id is missing:\n$manifest")
        }
    }
}
