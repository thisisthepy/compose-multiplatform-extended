/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.extended

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.internal.utils.OS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class PackageFormatFilesTest {
    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())

    // region the formats

    @Test
    fun theNewFormatsBelongToTheOperatingSystemsTheyPackageFor() {
        assertTrue(TargetFormat.Flatpak.isCompatibleWith(OS.Linux))
        assertTrue(TargetFormat.Msix.isCompatibleWith(OS.Windows))
        assertFalse(TargetFormat.Msix.isCompatibleWith(OS.Linux))
    }

    @Test
    fun theNewFormatsHaveTheFileExtensionOfTheirFile() {
        assertEquals(".flatpak", TargetFormat.Flatpak.fileExt)
        assertEquals(".msix", TargetFormat.Msix.fileExt)
    }

    @Test
    fun upstreamsAppImageIsStillTheDirectoryAndIsNotABundleFormat() {
        assertEquals("app", TargetFormat.AppImage.outputDirName)
        assertFalse(TargetFormat.AppImage.isBundleFormat)
        assertTrue(TargetFormat.Flatpak.isBundleFormat)
        assertTrue(TargetFormat.Msix.isBundleFormat)
        assertFalse(TargetFormat.Msi.isBundleFormat)
    }

    // endregion

    // region checksum

    @Test
    fun aChecksumIsRecordedBesideTheFileInSha256sumFormat() {
        val dir = java.nio.file.Files.createTempDirectory("checksum").toFile()
        try {
            val file = File(dir, "app.bin").apply { writeText("abc") }
            val sum = BundleChecksum.record(file)
            assertEquals("app.bin.sha256", sum.name)
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  app.bin\n",
                sum.readText()
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // endregion

    // region Flatpak and AppStream

    @Test
    fun flatpakApplicationIdsAreReverseDns() {
        assertTrue(FlatpakNames.isValidAppId("org.example.hello"))
        assertTrue(FlatpakNames.isValidAppId("io.github.user.My-App"))
        assertFalse(FlatpakNames.isValidAppId("hello"))
        assertFalse(FlatpakNames.isValidAppId("org.example.1hello"))
        assertFalse(FlatpakNames.isValidAppId("org..hello"))
    }

    @Test
    fun theDefaultFlatpakIdIsValid() {
        assertEquals("org.example.hello", BundleContext.defaultFlatpakId("Hello"))
        assertEquals("org.example.app2048", BundleContext.defaultFlatpakId("2048"))
        assertTrue(FlatpakNames.isValidAppId(BundleContext.defaultFlatpakId("노트")))
    }

    @Test
    fun theFlatpakManifestBuildsTheModuleFromTheLocalPayload() {
        val json = FlatpakManifest.render(
            appId = "org.example.hello", executableName = "hello", executableRelativePath = "bin/hello",
            runtime = "org.freedesktop.Platform", runtimeVersion = "25.08",
            finishArgs = FlatpakManifest.finishArgs(listOf("--share=network", "--socket=x11")),
            source = FlatpakPayloadSource.Directory("payload"),
            iconInstallPaths = listOf("share/icons/hicolor/256x256/apps/org.example.hello.png"),
            desktopFile = "org.example.hello.desktop", metainfoFile = "org.example.hello.metainfo.xml",
        )
        assertTrue(json.contains("\"id\": \"org.example.hello\""), json)
        assertTrue(json.contains("\"runtime-version\": \"25.08\""))
        assertTrue(json.contains("\"command\": \"hello\""))
        assertTrue(json.contains("\"type\": \"dir\""))
        assertTrue(json.contains("install -Dm644 packaging/org.example.hello.metainfo.xml /app/share/metainfo/org.example.hello.metainfo.xml"))
        assertTrue(json.contains("test -x /app/lib/hello/bin/hello"))
        // The defaults are kept once, and the extras follow them.
        assertEquals(1, Regex("--socket=x11").findAll(json).count())
        assertTrue(json.contains("--share=network"))
    }

    @Test
    fun theFlathubManifestFetchesThePayloadByUrlAndChecksIt() {
        val json = FlatpakManifest.render(
            appId = "org.example.hello", executableName = "hello", executableRelativePath = "bin/hello",
            runtime = "org.freedesktop.Platform", runtimeVersion = "25.08", finishArgs = FlatpakManifest.finishArgs(emptyList()),
            source = FlatpakPayloadSource.Archive("https://example.org/hello-1.0.0-x86_64.tar.gz", "ab".repeat(32)),
            iconInstallPaths = emptyList(), desktopFile = "a.desktop", metainfoFile = "a.metainfo.xml",
        )
        assertTrue(json.contains("\"type\": \"archive\""), json)
        assertTrue(json.contains("\"url\": \"https://example.org/hello-1.0.0-x86_64.tar.gz\""))
        assertTrue(json.contains("\"sha256\": \"${"ab".repeat(32)}\""))
        assertFalse(json.contains("\"path\": \"payload\""))
    }

    @Test
    fun theFlatpakManifestEscapesJsonStrings() {
        val json = Json.obj("k" to Json.str("a \"quoted\" \\ line\nbreak")).render(0)
        assertEquals("{\n    \"k\": \"a \\\"quoted\\\" \\\\ line\\nbreak\"\n}", json)
    }

    @Test
    fun theMetainfoIsWellFormedAndNamesTheDesktopEntryAndRelease() {
        val xml = AppStreamMetainfo.render(
            appId = "org.example.hello", name = "Hello & Co", summary = "Says <hello>", description = listOf("First", "Second"),
            version = "1.2.3", date = "2026-10-05", developerName = "Example", executable = "hello",
            releaseNotes = listOf("Fixed it"),
        )
        val doc = parse(xml)
        assertEquals("component", doc.documentElement.tagName)
        assertEquals("desktop-application", doc.documentElement.getAttribute("type"))
        assertTrue(xml.contains("<launchable type=\"desktop-id\">org.example.hello.desktop</launchable>"))
        assertTrue(xml.contains("<release version=\"1.2.3\" date=\"2026-10-05\">"))
        assertTrue(xml.contains("<name>Hello &amp; Co</name>"))
        assertEquals("org.example", AppStreamMetainfo.developerId("org.example.hello"))
    }

    // endregion

    // region MSIX

    @Test
    fun anMsixVersionHasFourPartsTakenFromTheApplicationVersion() {
        assertEquals("1.2.3.0", MsixVersion.fromApplicationVersion("1.2.3").toString())
        assertEquals("1.2.0.0", MsixVersion.fromApplicationVersion("1.2").toString())
        assertEquals("7.0.0.0", MsixVersion.fromApplicationVersion("7").toString())
        assertEquals("1.2.3.0", MsixVersion.fromApplicationVersion("1.2.3+build5").toString())
        assertEquals("1.2.3.0", MsixVersion.fromApplicationVersion("1.2.3.0").toString())
    }

    @Test
    fun anMsixVersionRefusesWhatTheStoreWouldMisorder() {
        assertThrows(IllegalArgumentException::class.java) { MsixVersion.fromApplicationVersion("1.2.0-beta.3") }
        assertThrows(IllegalArgumentException::class.java) { MsixVersion.fromApplicationVersion("1.2.3.4") }
        assertThrows(IllegalArgumentException::class.java) { MsixVersion.fromApplicationVersion("a.b.c") }
        assertThrows(IllegalStateException::class.java) { MsixVersion.fromApplicationVersion("1.70000.0") }
    }

    @Test
    fun theMsixIdentityNameIsMadeFromTheVendorAndTheName() {
        assertEquals("Example.Hello", MsixManifest.identityName("Example", "Hello"))
        assertEquals("Hello", MsixManifest.identityName(null, "Hello"))
        assertEquals("Example.MyApp", MsixManifest.identityName("Example", "My App"))
        assertTrue(MsixManifest.identityName(null, "x").length >= 3)
        assertEquals("App", MsixManifest.applicationId("2048"))
        assertEquals("MyApp2", MsixManifest.applicationId("My App 2"))
    }

    private fun manifest(arch: String = "x64", capabilities: List<String> = listOf("internetClient", "picturesLibrary")) =
        MsixManifest.render(
            identityName = "Example.Hello", publisher = "CN=\"Example, Inc\"", version = MsixVersion(1, 2, 3),
            architecture = arch, displayName = "Hello", publisherDisplayName = "Example", description = "Notes & <things>",
            executable = "hello.exe", languages = listOf("en-us", "ko-kr"), capabilities = capabilities,
        )

    @Test
    fun theMsixManifestIsWellFormedAndCarriesTheIdentityAndTheVersion() {
        val xml = manifest()
        parse(xml)
        assertTrue(
            xml.contains("<Identity Name=\"Example.Hello\" Publisher=\"CN=&quot;Example, Inc&quot;\" Version=\"1.2.3.0\" ProcessorArchitecture=\"x64\" />"),
            xml
        )
        assertTrue(xml.contains("Description=\"Notes &amp; &lt;things&gt;\""))
        assertTrue(xml.contains("<Resource Language=\"ko-kr\" />"))
    }

    @Test
    fun theMsixManifestDeclaresAFullTrustDesktopApplicationAndNamesEveryAsset() {
        val xml = manifest()
        assertTrue(xml.contains("EntryPoint=\"Windows.FullTrustApplication\""))
        assertTrue(xml.contains("<rescap:Capability Name=\"runFullTrust\" />"))
        assertEquals(1, Regex("runFullTrust").findAll(xml).count())
        for (asset in MsixAssets.all) assertTrue(xml.contains("Assets\\${asset.file}"), "${asset.file} is made but not named")
    }

    @Test
    fun theMsixCapabilitiesAreNamespacedAndOrdered() {
        val xml = manifest()
        val foundation = xml.indexOf("<Capability Name=\"internetClient\"")
        val uap = xml.indexOf("<uap:Capability Name=\"picturesLibrary\"")
        val rescap = xml.indexOf("<rescap:Capability Name=\"runFullTrust\"")
        assertTrue(foundation in 0 until uap && uap < rescap, xml)
    }

    @Test
    fun theMsixArchitectureFollowsTheTarget() {
        assertEquals("arm64", MsixManifest.architecture("mingwArm64"))
        assertEquals("x64", MsixManifest.architecture("mingwX64"))
        assertEquals("x64", MsixManifest.architecture("amd64"))
    }

    @Test
    fun theCertificationKitReportListsOnlyFailedRequiredTests() {
        val report = """
            <REPORT OVERALL_RESULT="FAIL">
              <REQUIREMENTS>
                <TEST INDEX="1" NAME="App manifest" OPTIONAL="FALSE"><RESULT><![CDATA[FAIL]]></RESULT></TEST>
                <TEST INDEX="2" NAME="Blocked executables" OPTIONAL="TRUE"><RESULT><![CDATA[FAIL]]></RESULT></TEST>
                <TEST INDEX="3" NAME="Supported APIs" OPTIONAL="FALSE"><RESULT><![CDATA[PASS]]></RESULT></TEST>
              </REQUIREMENTS>
            </REPORT>
        """.trimIndent()
        assertEquals(listOf("App manifest"), MsixCertification.requiredFailures(report))
        assertEquals(emptyList<String>(), MsixCertification.requiredFailures("<REPORT OVERALL_RESULT=\"PASS\"/>"))
    }

    @Test
    fun theSideloadInstructionsNameThePackageThePublisherAndBothWaysToInstall() {
        val text = AbstractMsixTask.installInstructions("hello.msix", "layout", "CN=Example", MsixVersion(1, 0, 0))
        assertTrue(text.contains("Add-AppxPackage -Register \"layout\\AppxManifest.xml\""))
        assertTrue(text.contains("Developer Mode"))
        assertTrue(text.contains("signtool sign"))
        assertTrue(text.contains("CN=Example"))
        assertTrue(text.contains("Partner Center"))
    }

    // endregion
}
