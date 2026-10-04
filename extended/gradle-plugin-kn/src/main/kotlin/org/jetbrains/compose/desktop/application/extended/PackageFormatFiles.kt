/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.extended

import java.io.File
import java.security.MessageDigest

/*
 * The text and names of the AppImage, Flatpak and MSIX bundles. Everything here is a pure
 * function of the application's metadata, so it is tested without running a packaging tool.
 */

internal fun xmlEscape(text: String): String = buildString(text.length) {
    for (c in text) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&apos;")
        else -> append(c)
    }
}

/** What every bundle records about the files it holds. */
internal object BundleChecksum {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The `sha256sum` line: the digest, two spaces, the file name. */
    fun line(file: File): String = "${sha256(file)}  ${file.name}"

    /** Writes `<file>.sha256` beside [file] and returns it. */
    fun record(file: File): File = File(file.parentFile, file.name + ".sha256").also {
        it.writeText(line(file) + "\n")
    }
}

internal object AppImageNames {
    /** Anything but letters, digits, `.`, `_` and `-` becomes `_`. */
    fun fileSafe(name: String): String = name.map {
        if (it.isLetterOrDigit() && it.code < 128 || it in "._-") it else '_'
    }.joinToString("")

    fun architecture(konanOrOsArch: String): String = when {
        konanOrOsArch.contains("arm64", ignoreCase = true) || konanOrOsArch.contains("aarch64", ignoreCase = true) -> "aarch64"
        else -> "x86_64"
    }
}

// region AppStream

/** The AppStream metainfo of a desktop application, which is what a store page is built from. */
internal object AppStreamMetainfo {
    fun fileName(appId: String) = "$appId.metainfo.xml"

    /** `appimagetool` only knows the older suffix. */
    fun appDataFileName(appId: String) = "$appId.appdata.xml"

    fun render(
        appId: String,
        name: String,
        summary: String,
        description: List<String>,
        version: String,
        date: String,
        developerName: String,
        metadataLicense: String = "CC0-1.0",
        projectLicense: String = "LicenseRef-proprietary",
        homepage: String? = null,
        executable: String,
        releaseNotes: List<String> = emptyList(),
    ): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<component type="desktop-application">""")
        appendLine("  <id>${xmlEscape(appId)}</id>")
        appendLine("  <metadata_license>${xmlEscape(metadataLicense)}</metadata_license>")
        appendLine("  <project_license>${xmlEscape(projectLicense)}</project_license>")
        appendLine("  <name>${xmlEscape(name)}</name>")
        appendLine("  <summary>${xmlEscape(summary)}</summary>")
        appendLine("""  <developer id="${xmlEscape(developerId(appId))}">""")
        appendLine("    <name>${xmlEscape(developerName)}</name>")
        appendLine("  </developer>")
        appendLine("  <description>")
        for (paragraph in description.ifEmpty { listOf(summary) }) appendLine("    <p>${xmlEscape(paragraph)}</p>")
        appendLine("  </description>")
        appendLine("""  <launchable type="desktop-id">${xmlEscape(appId)}.desktop</launchable>""")
        if (!homepage.isNullOrBlank()) appendLine("""  <url type="homepage">${xmlEscape(homepage)}</url>""")
        appendLine("""  <content_rating type="oars-1.1"/>""")
        appendLine("  <provides>")
        appendLine("    <binary>${xmlEscape(executable)}</binary>")
        appendLine("  </provides>")
        appendLine("  <releases>")
        if (releaseNotes.isEmpty()) {
            appendLine("""    <release version="${xmlEscape(version)}" date="${xmlEscape(date)}"/>""")
        } else {
            appendLine("""    <release version="${xmlEscape(version)}" date="${xmlEscape(date)}">""")
            appendLine("      <description>")
            releaseNotes.forEach { appendLine("        <p>${xmlEscape(it)}</p>") }
            appendLine("      </description>")
            appendLine("    </release>")
        }
        appendLine("  </releases>")
        appendLine("</component>")
    }

    /** The reverse-DNS prefix of the id, which is the developer's domain. */
    fun developerId(appId: String): String = appId.substringBeforeLast('.', appId)
}

// endregion

// region Flatpak

internal object FlatpakNames {
    /** A reverse-DNS id: at least two dots-separated elements, each starting with a letter. */
    private val idPattern = Regex("^[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*)+$")

    fun isValidAppId(id: String) = id.length <= 255 && idPattern.matches(id)

    fun architecture(konanOrOsArch: String): String =
        if (AppImageNames.architecture(konanOrOsArch) == "aarch64") "aarch64" else "x86_64"

    fun bundleFileName(name: String, version: String, arch: String) =
        "${AppImageNames.fileSafe(name)}-$version-$arch.flatpak"

    fun payloadArchiveName(name: String, version: String, arch: String) =
        "${AppImageNames.fileSafe(name)}-$version-$arch.tar.gz"
}

/** One source of the application module: a directory beside the manifest, or an archive fetched by URL. */
internal sealed interface FlatpakPayloadSource {
    /** Used for a local build, where the payload sits beside the manifest. */
    data class Directory(val path: String) : FlatpakPayloadSource

    /** What Flathub requires: fetched by URL and checked against the digest. */
    data class Archive(val url: String, val sha256: String) : FlatpakPayloadSource
}

/** The manifest `flatpak-builder` builds, in the shape Flathub reviews. */
internal object FlatpakManifest {
    const val DEFAULT_RUNTIME_VERSION = "25.08"

    /**
     * The window is drawn through X11 (Wayland sessions reach it through XWayland). X11's
     * shared memory transport needs shared IPC; without it every frame goes through the
     * socket. The GPU is for the renderer.
     */
    val defaultFinishArgs = listOf("--share=ipc", "--socket=x11", "--device=dri")

    fun finishArgs(extra: List<String>): List<String> = (defaultFinishArgs + extra).distinct()

    fun render(
        appId: String,
        executableName: String,
        executableRelativePath: String,
        runtime: String,
        runtimeVersion: String,
        finishArgs: List<String>,
        source: FlatpakPayloadSource,
        iconInstallPaths: List<String>,
        desktopFile: String,
        metainfoFile: String,
    ): String {
        val lib = "/app/lib/$executableName"
        val commands = buildList {
            add("mkdir -p $lib /app/bin")
            add("cp -a payload/. $lib/")
            add("test -x $lib/$executableRelativePath")
            // The launcher of a jpackage image finds its libraries relative to its own path, so
            // /app/bin holds a script that starts the real one instead of a symbolic link.
            add("printf '#!/bin/sh\\nexec $lib/$executableRelativePath \"\$@\"\\n' > /app/bin/$executableName")
            add("chmod 755 /app/bin/$executableName")
            add("install -Dm644 packaging/$desktopFile /app/share/applications/$desktopFile")
            add("install -Dm644 packaging/$metainfoFile /app/share/metainfo/$metainfoFile")
            for (icon in iconInstallPaths) add("install -Dm644 packaging/$icon /app/$icon")
        }
        val sources = buildList {
            add(
                when (source) {
                    is FlatpakPayloadSource.Directory -> Json.obj(
                        "type" to Json.str("dir"), "path" to Json.str(source.path), "dest" to Json.str("payload")
                    )
                    is FlatpakPayloadSource.Archive -> Json.obj(
                        "type" to Json.str("archive"), "url" to Json.str(source.url),
                        "sha256" to Json.str(source.sha256), "dest" to Json.str("payload")
                    )
                }
            )
            add(Json.obj("type" to Json.str("dir"), "path" to Json.str("packaging"), "dest" to Json.str("packaging")))
        }
        val module = Json.obj(
            "name" to Json.str(executableName),
            "buildsystem" to Json.str("simple"),
            "build-commands" to Json.Array(commands.map { Json.str(it) }),
            "sources" to Json.Array(sources),
        )
        return Json.obj(
            "id" to Json.str(appId),
            "runtime" to Json.str(runtime),
            "runtime-version" to Json.str(runtimeVersion),
            "sdk" to Json.str("org.freedesktop.Sdk"),
            "command" to Json.str(executableName),
            "finish-args" to Json.Array(finishArgs.map { Json.str(it) }),
            "modules" to Json.Array(listOf(module)),
        ).render(0) + "\n"
    }
}

/** Just enough JSON to write a manifest whose keys stay in the order a reader expects. */
internal sealed interface Json {
    data class Str(val value: String) : Json
    data class Array(val items: List<Json>) : Json
    data class Obj(val fields: List<Pair<String, Json>>) : Json

    fun render(depth: Int): String {
        val pad = "    ".repeat(depth + 1)
        val close = "    ".repeat(depth)
        return when (this) {
            is Str -> quote(value)
            is Array -> if (items.isEmpty()) "[]" else
                items.joinToString(",\n", "[\n", "\n$close]") { pad + it.render(depth + 1) }
            is Obj -> if (fields.isEmpty()) "{}" else
                fields.joinToString(",\n", "{\n", "\n$close}") { (k, v) -> pad + quote(k) + ": " + v.render(depth + 1) }
        }
    }

    companion object {
        fun str(value: String): Json = Str(value)
        fun obj(vararg fields: Pair<String, Json>): Json = Obj(fields.toList())

        private fun quote(value: String): String = buildString {
            append('"')
            for (c in value) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c.code < 0x20 -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }
    }
}

// endregion

// region MSIX

/** The four-part version an MSIX package carries, taken from the application's version. */
internal data class MsixVersion(val major: Int, val minor: Int, val build: Int, val revision: Int = 0) {
    override fun toString() = "$major.$minor.$build.$revision"

    companion object {
        /**
         * `1.2.3` becomes `1.2.3.0`, and `1.2` becomes `1.2.0.0`. The Store reserves the fourth
         * part, so it is zero. A pre-release (`1.2.0-beta.3`) is refused: the Store orders
         * packages by version alone, so it and `1.2.0` would be the same package version.
         */
        fun fromApplicationVersion(version: String): MsixVersion {
            val trimmed = version.trim().substringBefore('+')
            require('-' !in trimmed) {
                "msix version `$version`: a pre-release cannot be a package version, because `${trimmed.substringBefore('-')}` " +
                    "would be the same package version. Give the pre-release its own patch number."
            }
            val parts = trimmed.split('.')
            require(parts.size in 1..4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
                "msix version `$version` is not numeric parts separated by dots, such as 1.2.3"
            }
            val numbers = parts.map {
                it.toIntOrNull()?.takeIf { n -> n <= 65535 }
                    ?: error("msix version `$version`: part `$it` is larger than 65535")
            }
            require(numbers.size < 4 || numbers[3] == 0) {
                "msix version `$version`: the fourth part must be 0 because the Store reserves it"
            }
            return MsixVersion(numbers[0], numbers.getOrElse(1) { 0 }, numbers.getOrElse(2) { 0 }, 0)
        }
    }
}

/** A generated tile or store image. */
internal data class MsixAsset(val file: String, val width: Int, val height: Int)

internal object MsixAssets {
    const val DIR = "Assets"
    val storeLogo = MsixAsset("StoreLogo.png", 50, 50)
    val square44 = MsixAsset("Square44x44Logo.png", 44, 44)
    val square150 = MsixAsset("Square150x150Logo.png", 150, 150)
    val wide310 = MsixAsset("Wide310x150Logo.png", 310, 150)
    val all = listOf(storeLogo, square44, square150, wide310)
}

internal object MsixManifest {
    const val DEFAULT_MIN_VERSION = "10.0.17763.0"
    const val DEFAULT_MAX_VERSION_TESTED = "10.0.26100.0"

    /** Package identity names are 3 to 50 characters of letters, digits, `.` and `-`. */
    fun identityName(vendor: String?, name: String): String {
        fun clean(s: String) = s.filter { it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '-' }
        val parts = listOfNotNull(vendor?.let(::clean)?.takeIf { it.isNotEmpty() }, clean(name).takeIf { it.isNotEmpty() })
        val joined = parts.joinToString(".").ifEmpty { "Application" }
        val padded = if (joined.length < 3) joined.padEnd(3, 'x') else joined
        return padded.take(50)
    }

    /** The application id inside the package: a letter followed by letters and digits. */
    fun applicationId(displayName: String): String {
        val id = displayName.filter { it.isLetterOrDigit() && it.code < 128 }.take(64)
        return if (id.firstOrNull()?.isLetter() == true) id else "App"
    }

    private val uapCapabilities = setOf(
        "appointments", "blockedChatMessages", "chat", "contacts", "documentsLibrary", "enterpriseAuthentication",
        "musicLibrary", "objects3D", "phoneCall", "picturesLibrary", "removableStorage", "sharedUserCertificates",
        "userAccountInformation", "videosLibrary", "voipCall",
    )

    fun render(
        identityName: String,
        publisher: String,
        version: MsixVersion,
        architecture: String,
        displayName: String,
        publisherDisplayName: String,
        description: String,
        executable: String,
        minVersion: String = DEFAULT_MIN_VERSION,
        maxVersionTested: String = DEFAULT_MAX_VERSION_TESTED,
        languages: List<String> = listOf("en-us"),
        capabilities: List<String> = emptyList(),
        backgroundColor: String = "transparent",
    ): String = buildString {
        val e = ::xmlEscape
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("<Package")
        appendLine("""  xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"""")
        appendLine("""  xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"""")
        appendLine("""  xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"""")
        appendLine("""  IgnorableNamespaces="uap rescap">""")
        appendLine("""  <Identity Name="${e(identityName)}" Publisher="${e(publisher)}" Version="$version" ProcessorArchitecture="$architecture" />""")
        appendLine("  <Properties>")
        appendLine("    <DisplayName>${e(displayName)}</DisplayName>")
        appendLine("    <PublisherDisplayName>${e(publisherDisplayName)}</PublisherDisplayName>")
        appendLine("    <Logo>${MsixAssets.DIR}\\${MsixAssets.storeLogo.file}</Logo>")
        appendLine("  </Properties>")
        appendLine("  <Dependencies>")
        appendLine("""    <TargetDeviceFamily Name="Windows.Desktop" MinVersion="${e(minVersion)}" MaxVersionTested="${e(maxVersionTested)}" />""")
        appendLine("  </Dependencies>")
        appendLine("  <Resources>")
        languages.forEach { appendLine("""    <Resource Language="${e(it)}" />""") }
        appendLine("  </Resources>")
        appendLine("  <Applications>")
        appendLine("""    <Application Id="${applicationId(displayName)}" Executable="${e(executable)}" EntryPoint="Windows.FullTrustApplication">""")
        appendLine(
            """      <uap:VisualElements DisplayName="${e(displayName)}" Description="${e(description)}" BackgroundColor="${e(backgroundColor)}" """ +
                """Square150x150Logo="${MsixAssets.DIR}\${MsixAssets.square150.file}" Square44x44Logo="${MsixAssets.DIR}\${MsixAssets.square44.file}">"""
        )
        appendLine("""        <uap:DefaultTile Wide310x150Logo="${MsixAssets.DIR}\${MsixAssets.wide310.file}" />""")
        appendLine("      </uap:VisualElements>")
        appendLine("    </Application>")
        appendLine("  </Applications>")
        appendLine("  <Capabilities>")
        // The schema wants every Capability before any DeviceCapability.
        capabilities.filter { it !in uapCapabilities && it != "runFullTrust" }
            .forEach { appendLine("""    <Capability Name="${e(it)}" />""") }
        capabilities.filter { it in uapCapabilities }
            .forEach { appendLine("""    <uap:Capability Name="${e(it)}" />""") }
        appendLine("""    <rescap:Capability Name="runFullTrust" />""")
        appendLine("  </Capabilities>")
        appendLine("</Package>")
    }

    fun architecture(konanOrOsArch: String): String =
        if (konanOrOsArch.contains("arm64", ignoreCase = true) || konanOrOsArch.contains("aarch64", ignoreCase = true)) "arm64" else "x64"
}

// endregion
