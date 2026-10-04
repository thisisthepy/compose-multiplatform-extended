/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.extended

import org.jetbrains.compose.desktop.application.dsl.FileAssociation
import org.jetbrains.compose.desktop.application.internal.InfoPlistBuilder
import org.jetbrains.compose.desktop.application.internal.InfoPlistBuilder.InfoPlistValue.InfoPlistListValue
import org.jetbrains.compose.desktop.application.internal.InfoPlistBuilder.InfoPlistValue.InfoPlistMapValue
import org.jetbrains.compose.desktop.application.internal.InfoPlistBuilder.InfoPlistValue.InfoPlistStringValue
import org.jetbrains.compose.desktop.application.internal.PlistKeys
import java.io.File

/** What a Kotlin/Native macOS application's Info.plist is made from. */
internal data class NativeInfoPlistInput(
    val executableName: String,
    val iconFileName: String?,
    val bundleID: String,
    val version: String,
    val buildVersion: String = version,
    val minimumSystemVersion: String,
    val appCategory: String? = null,
    val copyright: String? = null,
    val extraKeysRawXml: String? = null,
    val fileAssociations: List<FileAssociation> = emptyList(),
)

internal object NativeInfoPlist {
    fun render(input: NativeInfoPlistInput): String {
        val file = File.createTempFile("Info", ".plist")
        try {
            write(input, file)
            return file.readText()
        } finally {
            file.delete()
        }
    }

    fun write(input: NativeInfoPlistInput, file: File) {
        val builder = InfoPlistBuilder(input.extraKeysRawXml)
        builder[PlistKeys.LSMinimumSystemVersion] = input.minimumSystemVersion
        builder[PlistKeys.CFBundleDevelopmentRegion] = "English"
        builder[PlistKeys.CFBundleAllowMixedLocalizations] = "true"
        builder[PlistKeys.CFBundleExecutable] = input.executableName
        builder[PlistKeys.CFBundleIconFile] = input.iconFileName
        builder[PlistKeys.CFBundleIdentifier] = input.bundleID
        builder[PlistKeys.CFBundleShortVersionString] = input.version
        builder[PlistKeys.CFBundleVersion] = input.buildVersion
        builder[PlistKeys.LSApplicationCategoryType] = input.appCategory
        builder[PlistKeys.NSHumanReadableCopyright] = input.copyright
        builder[PlistKeys.NSSupportsAutomaticGraphicsSwitching] = "true"
        builder[PlistKeys.NSHighResolutionCapable] = "true"
        if (input.fileAssociations.isNotEmpty()) {
            builder[PlistKeys.CFBundleDocumentTypes] = input.fileAssociations.map { association ->
                InfoPlistMapValue(
                    PlistKeys.CFBundleTypeName to InfoPlistStringValue(association.description),
                    PlistKeys.CFBundleTypeRole to InfoPlistStringValue("Editor"),
                    PlistKeys.CFBundleTypeExtensions to InfoPlistListValue(InfoPlistStringValue(association.extension)),
                    PlistKeys.CFBundleTypeMIMETypes to InfoPlistListValue(InfoPlistStringValue(association.mimeType)),
                )
            }
        }
        builder.writeToFile(file)
    }
}

/** A freedesktop.org desktop entry for a Linux application. */
internal object LinuxDesktopEntry {
    fun render(
        name: String,
        exec: String,
        icon: String,
        description: String? = null,
        categories: String? = null,
        mimeTypes: List<String> = emptyList(),
    ): String = buildString {
        appendLine("[Desktop Entry]")
        appendLine("Type=Application")
        appendLine("Version=1.0")
        appendLine("Name=$name")
        if (!description.isNullOrBlank()) appendLine("Comment=${description.lineSequence().first()}")
        appendLine("Exec=$exec")
        appendLine("Icon=$icon")
        appendLine("Terminal=false")
        if (!categories.isNullOrBlank()) appendLine("Categories=${categories.trimEnd(';')};")
        if (mimeTypes.isNotEmpty()) appendLine("MimeType=${mimeTypes.joinToString(";")};")
    }
}

/** The control file of a Debian package. */
internal object DebControl {
    fun render(
        packageName: String,
        version: String,
        architecture: String,
        maintainer: String,
        description: String,
        installedSizeKb: Long,
        section: String = "utils",
        depends: List<String> = emptyList(),
    ): String = buildString {
        appendLine("Package: ${debianPackageName(packageName)}")
        appendLine("Version: $version")
        appendLine("Section: $section")
        appendLine("Priority: optional")
        appendLine("Architecture: $architecture")
        if (depends.isNotEmpty()) appendLine("Depends: ${depends.joinToString(", ")}")
        appendLine("Installed-Size: $installedSizeKb")
        appendLine("Maintainer: $maintainer")
        val lines = description.lines().filter { it.isNotBlank() }.ifEmpty { listOf(packageName) }
        appendLine("Description: ${lines.first()}")
        lines.drop(1).forEach { appendLine(" $it") }
    }

    /** Debian package names are lower case letters, digits, plus, minus and period. */
    fun debianPackageName(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9+.-]"), "-").trim('-').ifEmpty { "app" }

    fun architecture(konanTargetName: String): String = when {
        konanTargetName.contains("arm64", ignoreCase = true) -> "arm64"
        else -> "amd64"
    }
}

/** The Windows Installer source for a Kotlin/Native application folder. */
internal object WindowsInstallerSource {
    /** A stable GUID derived from [seed], so the same application keeps its upgrade code. */
    fun guid(seed: String): String =
        java.util.UUID.nameUUIDFromBytes(seed.toByteArray()).toString().uppercase()

    fun render(
        productName: String,
        version: String,
        manufacturer: String,
        exeName: String,
        upgradeCode: String,
        perUser: Boolean,
        shortcut: Boolean,
        files: List<String>,
    ): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val scope = if (perUser) "perUser" else "perMachine"
        val folder = if (perUser) "LocalAppDataFolder" else "ProgramFilesFolder"
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi">""")
            appendLine("""  <Product Id="*" Name="${esc(productName)}" Language="1033" Version="$version" Manufacturer="${esc(manufacturer)}" UpgradeCode="$upgradeCode">""")
            appendLine("""    <Package InstallerVersion="500" Compressed="yes" InstallScope="$scope" />""")
            appendLine("""    <MajorUpgrade DowngradeErrorMessage="A newer version is already installed." />""")
            appendLine("""    <MediaTemplate EmbedCab="yes" />""")
            appendLine("""    <Directory Id="TARGETDIR" Name="SourceDir">""")
            appendLine("""      <Directory Id="$folder">""")
            appendLine("""        <Directory Id="INSTALLFOLDER" Name="${esc(productName)}" />""")
            appendLine("""      </Directory>""")
            if (shortcut) appendLine("""      <Directory Id="ProgramMenuFolder" />""")
            appendLine("""    </Directory>""")
            appendLine("""    <ComponentGroup Id="AppFiles" Directory="INSTALLFOLDER">""")
            files.forEachIndexed { index, file ->
                appendLine("""      <Component Id="c$index" Guid="${guid("$upgradeCode/$file")}">""")
                appendLine("""        <File Id="f$index" Source="SourceDir\${esc(file.replace('/', '\\'))}" KeyPath="yes" />""")
                if (file == exeName && shortcut) {
                    appendLine("""        <Shortcut Id="s$index" Directory="ProgramMenuFolder" Name="${esc(productName)}" WorkingDirectory="INSTALLFOLDER" Advertise="yes" />""")
                }
                appendLine("""      </Component>""")
            }
            appendLine("""    </ComponentGroup>""")
            appendLine("""    <Feature Id="Main" Level="1"><ComponentGroupRef Id="AppFiles" /></Feature>""")
            appendLine("""  </Product>""")
            appendLine("""</Wix>""")
        }
    }
}

/** What notarization needs, and the one line that says why it is skipped. */
internal object NativeNotarization {
    fun missingCredentials(appleID: String?, password: String?, teamID: String?): List<String> = buildList {
        if (appleID.isNullOrEmpty()) add("appleID")
        if (password.isNullOrEmpty()) add("password")
        if (teamID.isNullOrEmpty()) add("teamID")
    }

    fun skipMessage(missing: List<String>): String =
        "Skipping notarization: no Apple credentials (missing ${missing.joinToString(", ")}). " +
            "Set nativeDistributions.macOS.notarization { appleID, password, teamID } " +
            "or the matching Gradle properties to enable it."
}
