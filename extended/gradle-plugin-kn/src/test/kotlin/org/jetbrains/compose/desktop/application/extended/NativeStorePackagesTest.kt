/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.extended

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

class NativeStorePackagesTest {
    private fun parseXml(xml: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())

    private val msixMeta = MsixMetadata(
        displayName = "Ember",
        publisherDisplayName = "Example",
        description = "Notes & <things>",
        identityName = "Example.Ember",
        publisher = "CN=\"Example, Inc\"",
        languages = listOf("en-us", "ko-kr"),
        capabilities = listOf("internetClient", "picturesLibrary"),
    )

    private val storeVersion = MsixVersion.fromSemver("1.2.3", MsixChannel.Store)

    @Test
    fun msixStoreVersionIsTheSemverWithRevisionZero() {
        assertEquals("1.4.2.0", MsixVersion.fromSemver("1.4.2", MsixChannel.Store).toString())
        assertEquals("2.0.1.0", MsixVersion.fromSemver("2.0.1+git.abc", MsixChannel.Store).toString())
    }

    @Test
    fun msixStoreRefusesARevisionAndAPrerelease() {
        assertThrows(IllegalArgumentException::class.java) { MsixVersion.fromSemver("1.4.2", MsixChannel.Store, 7) }
        val error = assertThrows(IllegalArgumentException::class.java) {
            MsixVersion.fromSemver("1.4.2-beta.1", MsixChannel.Store)
        }
        assertTrue("pre-release" in error.message!!, error.message)
    }

    @Test
    fun msixSideloadRevisionIsTheBuildNumber() {
        assertEquals("1.4.2.31", MsixVersion.fromSemver("1.4.2-beta.1+abc", MsixChannel.Sideload, 31).toString())
    }

    @Test
    fun msixVersionPartsAreSixteenBitsAndWellFormed() {
        assertThrows(IllegalArgumentException::class.java) { MsixVersion.fromSemver("1.70000.0", MsixChannel.Store) }
        for (bad in listOf("1.0", "1.0.0.0", "a.b.c", "", "1..0")) {
            assertThrows(Exception::class.java, { MsixVersion.fromSemver(bad, MsixChannel.Store) }, bad)
        }
    }

    @Test
    fun msixLaterReleasesMapToGreaterVersions() {
        val mapped = listOf("0.9.9", "1.0.0", "1.0.1", "1.1.0", "2.0.0").map { MsixVersion.fromSemver(it, MsixChannel.Store) }
        assertTrue(mapped.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun msixManifestCarriesIdentityAndDeclaresAFullTrustApplication() {
        val xml = MsixManifest.render(msixMeta, storeVersion, MsixArch.Arm64, "bin\\ember.exe")
        parseXml(xml)
        assertTrue(
            """<Identity Name="Example.Ember" Publisher="CN=&quot;Example, Inc&quot;" Version="1.2.3.0" ProcessorArchitecture="arm64" />""" in xml,
            xml
        )
        assertTrue("""EntryPoint="Windows.FullTrustApplication"""" in xml)
        assertTrue("""Executable="bin\ember.exe"""" in xml)
        assertEquals(1, Regex("runFullTrust").findAll(xml).count())
        assertTrue("""Description="Notes &amp; &lt;things&gt;"""" in xml)
    }

    @Test
    fun msixCapabilitiesAreOrderedAndNamespaced() {
        val xml = MsixManifest.render(msixMeta, storeVersion, MsixArch.X64, "ember.exe")
        val foundation = xml.indexOf("""<Capability Name="internetClient"""")
        val uap = xml.indexOf("""<uap:Capability Name="picturesLibrary"""")
        val rescap = xml.indexOf("""<rescap:Capability Name="runFullTrust"""")
        assertTrue(foundation in 0 until uap && uap < rescap, xml)
        assertTrue("""<Resource Language="ko-kr" />""" in xml)
    }

    @Test
    fun msixManifestNamesEveryAssetItShips() {
        val xml = MsixManifest.render(msixMeta, storeVersion, MsixArch.X64, "ember.exe")
        for (spec in MsixAssets.ALL) assertTrue("Assets\\${spec.file}" in xml, "${spec.file} is generated but not referenced")
    }

    @Test
    fun msixApplicationIdIsAlphanumeric() {
        assertEquals("Ember", MsixManifest.applicationId("Ember"))
        assertEquals("MyApp2", MsixManifest.applicationId("My App 2"))
        assertEquals("App", MsixManifest.applicationId("2048"))
    }

    @Test
    fun msixAssetsAreWrittenAtTheirExactSizes() {
        val work = File.createTempFile("msix", "").apply { delete(); mkdirs() }
        try {
            val icon = work.resolve("icon.png")
            ImageIO.write(BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB), "png", icon)
            MsixAssets.writeAll(icon, work.resolve("Assets"))
            for (spec in MsixAssets.ALL) {
                val image = ImageIO.read(work.resolve("Assets").resolve(spec.file))
                assertEquals(spec.width to spec.height, image.width to image.height, spec.file)
            }
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun sdkVersionsOrderNumericallyAndTheNewestToolWins() {
        assertEquals(listOf(10, 0, 22621, 0), WindowsSdk.sdkVersion("10.0.22621.0"))
        assertEquals(null, WindowsSdk.sdkVersion("wdf"))
        assertEquals(null, WindowsSdk.sdkVersion("10.0.1"))
        val kits = File.createTempFile("kits", "").apply { delete(); mkdirs() }
        try {
            for (v in listOf("10.0.9999.0", "10.0.22621.0", "10.0.19041.0")) {
                kits.resolve("bin/$v/x64").mkdirs()
                kits.resolve("bin/$v/x64/makeappx.exe").writeText("")
            }
            assertEquals("10.0.22621.0", WindowsSdk.newestInKits(kits, "x64", "makeappx.exe")!!.parentFile.parentFile.name)
        } finally {
            kits.deleteRecursively()
        }
    }

    @Test
    fun anUnsignedMsixComesWithInstallInstructionsAndAChecksum() {
        val notes = InstallNotes.unsignedMsix("Example.Ember_1.2.3.0_x64.msix")
        assertTrue("Add-AppxPackage -Path .\\Example.Ember_1.2.3.0_x64.msix -AllowUnsigned" in notes, notes)
        assertTrue(NativeChecksums.FILE_NAME in notes)
    }

    private val linuxMeta = LinuxAppMetadata(
        id = "org.example.ember",
        name = "Ember",
        summary = "Notes & more",
        description = listOf("First paragraph.", "Second <paragraph>."),
        version = "1.2.3",
        date = "2026-10-05",
        executable = "ember",
        developerName = "Example",
        homepage = "https://example.org",
    )

    @Test
    fun metainfoIsWellFormedAndHasWhatFlathubValidates() {
        val xml = AppStreamMetainfo.render(linuxMeta)
        parseXml(xml)
        for (needle in listOf(
            "<id>org.example.ember</id>", "<metadata_license>CC0-1.0</metadata_license>",
            "<summary>Notes &amp; more</summary>", "<launchable type=\"desktop-id\">org.example.ember.desktop</launchable>",
            "<release version=\"1.2.3\" date=\"2026-10-05\"/>", "<p>Second &lt;paragraph&gt;.</p>",
        )) assertTrue(needle in xml, "$needle\n$xml")
        assertEquals("org.example.ember.metainfo.xml", AppStreamMetainfo.fileName(linuxMeta))
        assertEquals("org.example.ember.appdata.xml", AppStreamMetainfo.appdataFileName(linuxMeta))
    }

    @Test
    fun flatpakManifestNamesTheAppRuntimeAndSandbox() {
        val json = FlatpakManifest.render(linuxMeta, payloadDir = "payload", iconFile = "org.example.ember.png")
        for (needle in listOf(
            "\"app-id\": \"org.example.ember\"", "\"runtime\": \"org.freedesktop.Platform\"", "\"runtime-version\": \"24.08\"",
            "\"command\": \"ember\"", "\"--socket=x11\"", "\"--share=ipc\"", "\"--device=dri\"", "\"type\": \"dir\"",
            "/app/share/icons/hicolor/256x256/apps/org.example.ember.png",
        )) assertTrue(needle in json, "$needle\n$json")
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertEquals(json.count { it == '[' }, json.count { it == ']' })
    }

    @Test
    fun flatpakWaylandKeepsX11AsTheFallbackAndExtrasAreNotRepeated() {
        val args = FlatpakManifest.finishArgs(wayland = true, extra = listOf("--share=network", "--device=dri"))
        assertEquals(listOf("--share=ipc", "--socket=wayland", "--socket=fallback-x11", "--device=dri", "--share=network"), args)
    }

    @Test
    fun jsonStringsAreEscaped() {
        assertEquals("\"a\\\"b\\\\c\\n\"", FlatpakManifest.Json.str("a\"b\\c\n"))
    }

    @Test
    fun appImageUpdateInformationIsTheGithubZsyncForm() {
        assertEquals(
            "gh-releases-zsync|owner|repo|latest|Ember-*-x86_64.AppImage.zsync",
            AppImageUpdateInfo.githubReleases("owner", "repo", "Ember", "x86_64")
        )
    }

    @Test
    fun checksumsAreRecordedInSha256sumFormat() {
        val dir = File.createTempFile("sums", "").apply { delete(); mkdirs() }
        try {
            dir.resolve("a.bin").writeText("abc")
            val out = NativeChecksums.write(dir, listOf(dir.resolve("a.bin"), dir.resolve("missing")))
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  a.bin\n", out.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theGatekeeperNoteSaysHowToOpenABlockedApp() {
        val notes = InstallNotes.macGatekeeper("Ember")
        assertTrue("Open Anyway" in notes)
        assertTrue("xattr -dr com.apple.quarantine \"/Applications/Ember.app\"" in notes, notes)
        assertFalse("$" in notes)
    }
}
