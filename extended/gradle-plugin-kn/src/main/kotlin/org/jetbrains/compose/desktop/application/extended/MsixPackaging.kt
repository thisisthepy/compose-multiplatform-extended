/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.extended

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Where an MSIX is going. The two differ in who owns the fourth part of the version. */
enum class MsixChannel {
    /** Partner Center. The Store reserves the revision, so a Store release is `Major.Minor.Patch.0`. */
    Store,

    /** A direct download. The revision is a build number that only grows. */
    Sideload,
}

/** The four-part package version, each part a 16-bit number. */
internal data class MsixVersion(val major: Int, val minor: Int, val build: Int, val revision: Int) :
    Comparable<MsixVersion> {
    override fun toString() = "$major.$minor.$build.$revision"

    override fun compareTo(other: MsixVersion) =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.build }, { it.revision })

    companion object {
        /**
         * Maps the application's `MAJOR.MINOR.PATCH` version onto a package version. The mapping
         * is monotonic, because Windows only replaces an installed package with a greater one.
         * A pre-release is refused for the Store: `1.2.0-beta.3` and `1.2.0` would both be
         * `1.2.0.0`, and the release could never replace the beta.
         */
        fun fromSemver(semver: String, channel: MsixChannel, revision: Int? = null): MsixVersion {
            val trimmed = semver.trim()
            val withoutBuild = trimmed.substringBefore('+')
            val core = withoutBuild.substringBefore('-')
            val pre = withoutBuild.substringAfter('-', "").takeIf { '-' in withoutBuild }
            val parts = core.split('.')
            require(parts.size == 3) { "version `$trimmed` is not MAJOR.MINOR.PATCH" }
            fun part(name: String, text: String): Int {
                val value = text.toLongOrNull() ?: error("version `$trimmed`: the $name part `$text` is not a number")
                require(value in 0..65535) { "version `$trimmed`: the $name part is $value, and a package version part cannot exceed 65535" }
                return value.toInt()
            }
            val rev = when (channel) {
                MsixChannel.Store -> {
                    require(pre == null) {
                        "version `$trimmed` is a pre-release (`$pre`), and the Store cannot tell it apart from the " +
                            "release it precedes. Package it for the sideload channel, or give it a patch number of its own"
                    }
                    require(revision == null || revision == 0) {
                        "a Store package must have revision 0 (the Store reserves the fourth part), and revision $revision was given"
                    }
                    0
                }
                MsixChannel.Sideload -> {
                    val r = revision ?: 0
                    require(r in 0..65535) { "revision $r cannot exceed 65535" }
                    r
                }
            }
            return MsixVersion(part("major", parts[0]), part("minor", parts[1]), part("patch", parts[2]), rev)
        }
    }
}

/** What the manifest says about the application. */
internal data class MsixMetadata(
    val displayName: String,
    val publisherDisplayName: String,
    val description: String,
    val identityName: String,
    val publisher: String,
    val languages: List<String> = listOf("en-us"),
    val capabilities: List<String> = listOf("internetClient"),
    val restrictedCapabilities: List<String> = emptyList(),
    val minVersion: String = DEFAULT_MIN_VERSION,
    val maxVersionTested: String = DEFAULT_MAX_VERSION_TESTED,
    val backgroundColor: String = "transparent",
) {
    companion object {
        const val DEFAULT_MIN_VERSION = "10.0.17763.0"
        const val DEFAULT_MAX_VERSION_TESTED = "10.0.26100.0"

        /** `Name.Subname` with letters, digits, `.` and `-`, as the identity name rules want. */
        fun identityFrom(name: String): String =
            name.replace(Regex("[^A-Za-z0-9.-]"), "").trim('.', '-').ifEmpty { "App" }
    }
}

internal enum class MsixArch(val text: String) { X64("x64"), Arm64("arm64") }

internal object MsixManifest {
    private val uapCapabilities = setOf(
        "appointments", "blockedChatMessages", "chat", "contacts", "documentsLibrary", "enterpriseAuthentication",
        "musicLibrary", "objects3D", "phoneCall", "picturesLibrary", "removableStorage", "sharedUserCertificates",
        "userAccountInformation", "videosLibrary", "voipCall",
    )

    fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    /** The application id inside the package: a letter followed by letters and digits. */
    fun applicationId(displayName: String): String {
        val id = displayName.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.take(64)
        return if (id.firstOrNull()?.let { it in 'a'..'z' || it in 'A'..'Z' } == true) id else "App"
    }

    /** `executable` is the path inside the package, with backslashes. */
    fun render(meta: MsixMetadata, version: MsixVersion, arch: MsixArch, executable: String): String {
        val e = ::escape
        return buildString {
            appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
            appendLine("<Package")
            appendLine("""  xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"""")
            appendLine("""  xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"""")
            appendLine("""  xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"""")
            appendLine("""  IgnorableNamespaces="uap rescap">""")
            appendLine("""  <Identity Name="${e(meta.identityName)}" Publisher="${e(meta.publisher)}" Version="$version" ProcessorArchitecture="${arch.text}" />""")
            appendLine("  <Properties>")
            appendLine("    <DisplayName>${e(meta.displayName)}</DisplayName>")
            appendLine("    <PublisherDisplayName>${e(meta.publisherDisplayName)}</PublisherDisplayName>")
            appendLine("    <Logo>${MsixAssets.DIR}\\${MsixAssets.STORE_LOGO.file}</Logo>")
            appendLine("  </Properties>")
            appendLine("  <Dependencies>")
            appendLine("""    <TargetDeviceFamily Name="Windows.Desktop" MinVersion="${e(meta.minVersion)}" MaxVersionTested="${e(meta.maxVersionTested)}" />""")
            appendLine("  </Dependencies>")
            appendLine("  <Resources>")
            meta.languages.forEach { appendLine("""    <Resource Language="${e(it)}" />""") }
            appendLine("  </Resources>")
            appendLine("  <Applications>")
            appendLine("""    <Application Id="${applicationId(meta.displayName)}" Executable="${e(executable)}" EntryPoint="Windows.FullTrustApplication">""")
            appendLine(
                """      <uap:VisualElements DisplayName="${e(meta.displayName)}" Description="${e(meta.description)}" """ +
                    """BackgroundColor="${e(meta.backgroundColor)}" """ +
                    """Square150x150Logo="${MsixAssets.DIR}\${MsixAssets.SQUARE_150.file}" """ +
                    """Square44x44Logo="${MsixAssets.DIR}\${MsixAssets.SQUARE_44.file}">"""
            )
            appendLine("""        <uap:DefaultTile Wide310x150Logo="${MsixAssets.DIR}\${MsixAssets.WIDE_310.file}" />""")
            appendLine("      </uap:VisualElements>")
            appendLine("    </Application>")
            appendLine("  </Applications>")
            appendLine("  <Capabilities>")
            // The schema wants every Capability before any DeviceCapability.
            meta.capabilities.filter { it !in uapCapabilities }.forEach { appendLine("""    <Capability Name="${e(it)}" />""") }
            meta.capabilities.filter { it in uapCapabilities }.forEach { appendLine("""    <uap:Capability Name="${e(it)}" />""") }
            appendLine("""    <rescap:Capability Name="runFullTrust" />""")
            meta.restrictedCapabilities.filter { it != "runFullTrust" }
                .forEach { appendLine("""    <rescap:Capability Name="${e(it)}" />""") }
            appendLine("  </Capabilities>")
            appendLine("</Package>")
        }
    }
}

/** The images a package carries, drawn from the application's one icon. */
internal object MsixAssets {
    const val DIR = "Assets"

    data class Spec(val file: String, val width: Int, val height: Int)

    val STORE_LOGO = Spec("StoreLogo.png", 50, 50)
    val SQUARE_44 = Spec("Square44x44Logo.png", 44, 44)
    val SQUARE_150 = Spec("Square150x150Logo.png", 150, 150)
    val WIDE_310 = Spec("Wide310x150Logo.png", 310, 150)
    val ALL = listOf(STORE_LOGO, SQUARE_44, SQUARE_150, WIDE_310)

    /** Writes every image into [dir], each at its exact size, the icon fitted and centred. */
    fun writeAll(icon: File, dir: File) {
        val source = ImageIO.read(icon) ?: error("the icon ${icon.absolutePath} is not a readable image; use a PNG")
        dir.mkdirs()
        for (spec in ALL) ImageIO.write(fit(source, spec.width, spec.height), "png", File(dir, spec.file))
    }

    fun fit(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val scale = minOf(width.toDouble() / source.width, height.toDouble() / source.height)
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(source, (width - w) / 2, (height - h) / 2, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }
}
