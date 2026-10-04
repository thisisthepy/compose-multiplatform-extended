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

/** What a Kotlin/Native executable is called, per operating system, and what a signature needs. */
internal object NativeCliOutput {
    /** `.kexe` on macOS and Linux, `.exe` on Windows. */
    fun fileName(name: String, windows: Boolean): String = if (windows) "$name.exe" else "$name.kexe"

    enum class MacSigning { DeveloperId, KeepLinkerSignature, ReSignAdHoc }

    /**
     * What to do with a linked macOS executable. A Developer ID identity always signs. Without
     * one the linker's own ad hoc signature is kept as long as it still verifies, and a
     * post-link edit that broke it is signed ad hoc again.
     */
    fun macSigning(hasIdentity: Boolean, linkerSignatureValid: Boolean): MacSigning = when {
        hasIdentity -> MacSigning.DeveloperId
        linkerSignatureValid -> MacSigning.KeepLinkerSignature
        else -> MacSigning.ReSignAdHoc
    }

    fun architecture(konanTargetName: String): String =
        if (konanTargetName.contains("arm64", ignoreCase = true)) "arm64" else "amd64"
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
