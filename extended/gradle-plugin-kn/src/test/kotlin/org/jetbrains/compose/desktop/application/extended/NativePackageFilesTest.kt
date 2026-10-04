/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.extended

import org.jetbrains.compose.desktop.application.dsl.FileAssociation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class NativePackageFilesTest {
    private val plistInput = NativeInfoPlistInput(
        executableName = "hello",
        iconFileName = "hello.icns",
        bundleID = "org.example.hello",
        version = "1.2.3",
        minimumSystemVersion = "10.13",
        appCategory = "public.app-category.utilities",
        copyright = "Copyright (C) 2026",
    )

    private fun parse(xml: String) =
        DocumentBuilderFactory.newInstance().apply {
            // The plist DTD is remote; the structure is what is checked here.
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }.newDocumentBuilder().parse(xml.byteInputStream())

    @Test
    fun infoPlistNamesTheExecutableIconAndBundle() {
        val plist = NativeInfoPlist.render(plistInput)
        parse(plist)
        for (pair in listOf(
            "CFBundleExecutable" to "hello",
            "CFBundleIconFile" to "hello.icns",
            "CFBundleIdentifier" to "org.example.hello",
            "CFBundleShortVersionString" to "1.2.3",
            "CFBundleVersion" to "1.2.3",
            "LSMinimumSystemVersion" to "10.13",
            "NSHighResolutionCapable" to "true",
        )) {
            assertTrue("<key>${pair.first}</key>\n    <string>${pair.second}</string>" in plist.replace("\r", ""), plist)
        }
    }

    @Test
    fun infoPlistCarriesRawExtraKeys() {
        val plist = NativeInfoPlist.render(plistInput.copy(extraKeysRawXml = "<key>LSUIElement</key><true/>"))
        parse(plist)
        assertTrue("<key>LSUIElement</key><true/>" in plist, plist)
    }

    @Test
    fun infoPlistDeclaresFileAssociations() {
        val plist = NativeInfoPlist.render(
            plistInput.copy(fileAssociations = listOf(FileAssociation("text/x-note", "note", "Note", null)))
        )
        parse(plist)
        assertTrue("<key>CFBundleDocumentTypes</key>" in plist, plist)
        assertTrue("<string>note</string>" in plist, plist)
        assertTrue("<string>text/x-note</string>" in plist, plist)
    }

    @Test
    fun infoPlistWithoutAssociationsHasNoDocumentTypes() {
        assertFalse("CFBundleDocumentTypes" in NativeInfoPlist.render(plistInput))
    }

    @Test
    fun desktopEntryHasTheRequiredKeys() {
        val entry = LinuxDesktopEntry.render(
            name = "Hello",
            exec = "hello",
            icon = "hello",
            description = "A greeting.\nSecond line.",
            categories = "Utility",
            mimeTypes = listOf("text/x-note", "text/x-other"),
        )
        val lines = entry.lines()
        assertEquals("[Desktop Entry]", lines.first())
        for (line in listOf(
            "Type=Application", "Name=Hello", "Exec=hello", "Icon=hello", "Terminal=false",
            "Comment=A greeting.", "Categories=Utility;", "MimeType=text/x-note;text/x-other;",
        )) {
            assertTrue(line in lines, entry)
        }
    }

    @Test
    fun desktopEntryDoesNotDoubleTheCategoryTerminator() {
        assertTrue("Categories=Utility;" in LinuxDesktopEntry.render("A", "a", "a", categories = "Utility;").lines())
    }

    @Test
    fun cliExecutablesAreKexeOnMacAndLinuxAndExeOnWindows() {
        assertEquals("tool.kexe", NativeCliOutput.fileName("tool", windows = false))
        assertEquals("tool.exe", NativeCliOutput.fileName("tool", windows = true))
    }

    @Test
    fun aDeveloperIdIdentityAlwaysSignsTheKexe() {
        for (valid in listOf(true, false)) {
            assertEquals(NativeCliOutput.MacSigning.DeveloperId, NativeCliOutput.macSigning(true, valid))
        }
    }

    @Test
    fun theLinkerSignatureIsKeptWhileItVerifiesAndReSignedWhenItDoesNot() {
        assertEquals(NativeCliOutput.MacSigning.KeepLinkerSignature, NativeCliOutput.macSigning(false, true))
        assertEquals(NativeCliOutput.MacSigning.ReSignAdHoc, NativeCliOutput.macSigning(false, false))
    }

    @Test
    fun appImageArchitectureFollowsTheTarget() {
        assertEquals("arm64", NativeCliOutput.architecture("linuxArm64"))
        assertEquals("amd64", NativeCliOutput.architecture("linuxX64"))
    }

    @Test
    fun notarizationNamesTheMissingCredentialsAndSkips() {
        val missing = NativeNotarization.missingCredentials("me@example.org", null, "")
        assertEquals(listOf("password", "teamID"), missing)
        val message = NativeNotarization.skipMessage(missing)
        assertTrue(message.startsWith("Skipping notarization"), message)
        assertTrue("password, teamID" in message, message)
        assertTrue(NativeNotarization.missingCredentials("a", "b", "c").isEmpty())
    }

    @Test
    fun theManifestResolverReturnsTheOverrideOrWritesTheDefault() {
        val work = File.createTempFile("manifest", "").apply { delete(); mkdirs() }
        try {
            val override = work.resolve("mine.manifest").apply { writeText("<assembly/>") }
            assertEquals(override, WindowsAppManifest.resolve(override, work))
            val written = WindowsAppManifest.resolve(null, work)
            assertTrue("PerMonitorV2" in written.readText())
        } finally {
            work.deleteRecursively()
        }
    }
}
