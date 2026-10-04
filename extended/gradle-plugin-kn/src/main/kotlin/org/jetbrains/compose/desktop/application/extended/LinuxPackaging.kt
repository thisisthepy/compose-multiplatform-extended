/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.extended

import java.io.File
import java.security.MessageDigest

/** What the Linux metainfo and Flatpak manifest say about the application. */
internal data class LinuxAppMetadata(
    /** Reverse DNS id, `org.example.hello`. */
    val id: String,
    val name: String,
    val summary: String,
    val description: List<String>,
    val version: String,
    val date: String,
    val executable: String,
    val developerName: String,
    val license: String = "LicenseRef-proprietary",
    val metadataLicense: String = "CC0-1.0",
    val homepage: String? = null,
    val categories: String? = null,
)

internal object AppStreamMetainfo {
    fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    /** `<id>.metainfo.xml`, the name Flathub expects under `share/metainfo`. */
    fun fileName(meta: LinuxAppMetadata) = "${meta.id}.metainfo.xml"

    /** `<id>.appdata.xml`, the older suffix `appimagetool` looks for. */
    fun appdataFileName(meta: LinuxAppMetadata) = "${meta.id}.appdata.xml"

    fun render(meta: LinuxAppMetadata): String = buildString {
        fun element(depth: Int, name: String, text: String) =
            appendLine("${"  ".repeat(depth)}<$name>${escape(text)}</$name>")
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<component type="desktop-application">""")
        element(1, "id", meta.id)
        element(1, "metadata_license", meta.metadataLicense)
        element(1, "project_license", meta.license)
        element(1, "name", meta.name)
        element(1, "summary", meta.summary)
        appendLine("""  <developer id="${escape(meta.id)}">""")
        element(2, "name", meta.developerName)
        appendLine("  </developer>")
        appendLine("  <description>")
        meta.description.forEach { element(2, "p", it) }
        appendLine("  </description>")
        appendLine("""  <launchable type="desktop-id">${escape(meta.id)}.desktop</launchable>""")
        meta.homepage?.let { appendLine("""  <url type="homepage">${escape(it)}</url>""") }
        appendLine("""  <content_rating type="oars-1.1"/>""")
        appendLine("  <provides>")
        element(2, "binary", meta.executable)
        appendLine("  </provides>")
        appendLine("  <releases>")
        appendLine("""    <release version="${escape(meta.version)}" date="${escape(meta.date)}"/>""")
        appendLine("  </releases>")
        appendLine("</component>")
    }
}

/**
 * The Flatpak manifest, in the JSON form `flatpak-builder` reads, for a prebuilt Kotlin/Native
 * executable. The payload directory holds the executable and its resources, and is copied
 * into `/app/lib/<exec>` with a launcher in `/app/bin`.
 */
internal object FlatpakManifest {
    const val DEFAULT_RUNTIME = "org.freedesktop.Platform"
    const val DEFAULT_SDK = "org.freedesktop.Sdk"
    const val DEFAULT_RUNTIME_VERSION = "24.08"

    /** The window is X11's (Wayland reaches it through XWayland): the socket, shared IPC for its shared memory, and the GPU. */
    fun finishArgs(wayland: Boolean, extra: List<String> = emptyList()): List<String> {
        val base = if (wayland) {
            listOf("--share=ipc", "--socket=wayland", "--socket=fallback-x11", "--device=dri")
        } else {
            listOf("--share=ipc", "--socket=x11", "--device=dri")
        }
        return base + extra.filter { it !in base }
    }

    fun render(
        meta: LinuxAppMetadata,
        payloadDir: String,
        runtimeVersion: String = DEFAULT_RUNTIME_VERSION,
        wayland: Boolean = false,
        extraFinishArgs: List<String> = emptyList(),
        iconFile: String? = null,
    ): String {
        val lib = "/app/lib/${meta.executable}"
        val commands = buildList {
            add("mkdir -p $lib /app/bin /app/share/applications /app/share/metainfo")
            add("cp -a . $lib/")
            add("printf '#!/bin/sh\\nexec $lib/${meta.executable} \"\$@\"\\n' > /app/bin/${meta.executable}")
            add("chmod +x /app/bin/${meta.executable} $lib/${meta.executable}")
            add("install -Dm644 ${meta.id}.desktop /app/share/applications/${meta.id}.desktop")
            add("install -Dm644 ${AppStreamMetainfo.fileName(meta)} /app/share/metainfo/${AppStreamMetainfo.fileName(meta)}")
            if (iconFile != null) {
                add("install -Dm644 $iconFile /app/share/icons/hicolor/256x256/apps/${meta.id}.png")
            }
        }
        val j = Json
        return j.obj(
            "app-id" to j.str(meta.id),
            "runtime" to j.str(DEFAULT_RUNTIME),
            "runtime-version" to j.str(runtimeVersion),
            "sdk" to j.str(DEFAULT_SDK),
            "command" to j.str(meta.executable),
            "finish-args" to j.array(finishArgs(wayland, extraFinishArgs).map(j::str)),
            "modules" to j.array(
                listOf(
                    j.obj(
                        "name" to j.str(meta.executable),
                        "buildsystem" to j.str("simple"),
                        "build-commands" to j.array(commands.map(j::str)),
                        "sources" to j.array(
                            listOf(
                                j.obj("type" to j.str("dir"), "path" to j.str(payloadDir)),
                            )
                        ),
                    )
                )
            ),
        ) + "\n"
    }

    /** A just-enough JSON writer: the manifest has strings, arrays and objects only. */
    object Json {
        fun str(text: String): String = buildString {
            append('"')
            for (c in text) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }

        fun array(items: List<String>) = items.joinToString(",\n", "[\n", "\n]")

        fun obj(vararg entries: Pair<String, String>) =
            entries.joinToString(",\n", "{\n", "\n}") { (k, v) -> "  ${str(k)}: ${v.replace("\n", "\n  ")}" }
    }
}

internal object AppImageUpdateInfo {
    /**
     * The update information `appimagetool -u` embeds, which makes it write a `.zsync` file
     * beside the AppImage so a tool such as AppImageUpdate can fetch only what changed. This
     * is the GitHub Releases form.
     */
    fun githubReleases(owner: String, repo: String, name: String, arch: String): String =
        "gh-releases-zsync|$owner|$repo|latest|$name-*-$arch.AppImage.zsync"
}

/** SHA-256 sums of the files an output made, recorded beside them. */
internal object NativeChecksums {
    const val FILE_NAME = "checksums.sha256"

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** `<hash>  <name>` lines in the format `sha256sum -c` reads. */
    fun render(entries: Map<String, String>): String =
        entries.entries.sortedBy { it.key }.joinToString("") { "${it.value}  ${it.key}\n" }

    /** Writes `checksums.sha256` into [dir] for each of [files], named relative to [dir]. */
    fun write(dir: File, files: List<File>): File = File(dir, FILE_NAME).also { out ->
        out.writeText(render(files.filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to sha256(it) }))
    }
}

/** The text that ships beside an unsigned or ad hoc signed output, so a person can open it. */
internal object InstallNotes {
    fun macGatekeeper(appName: String): String = """
If macOS will not open the app
==============================

This app is signed, but not by a developer Apple has verified, so the first time you open
a copy you downloaded, macOS asks you to confirm. You do this once. After that the app
opens normally.

First drag the app into Applications.

macOS 15 (Sequoia) and newer
----------------------------
1. Open the app. macOS says it could not verify the app and does not open it. Click Done.
2. Open System Settings, choose Privacy & Security, and scroll down to Security.
   Next to the line saying the app was blocked, click Open Anyway.
   The button is there for about an hour after step 1; if it is gone, repeat step 1.
3. Click Open Anyway again in the dialog that follows, and enter your login password.

macOS 14 (Sonoma) and older
---------------------------
Control-click (or right-click) the app in Applications, choose Open, then click Open in
the dialog.

Any version, from Terminal
--------------------------
    xattr -dr com.apple.quarantine "/Applications/$appName.app"

This removes the mark your browser put on the download. Only do this for an app you
downloaded from a source you trust.
""".trimStart()

    fun unsignedMsix(packageFile: String): String = """
Installing an unsigned package
==============================

$packageFile is not signed. Windows installs an unsigned package only in Developer Mode, and
only with the AllowUnsigned switch.

1. Turn on Developer Mode: Settings, Privacy & security, For developers, Developer Mode.
2. In PowerShell:

       Add-AppxPackage -Path .\$packageFile -AllowUnsigned

To install it without Developer Mode, sign it first with a certificate the machine trusts
(set msix { certificateFile, certificatePassword } and build again), or publish it through
the Microsoft Store, which signs it for you.

The SHA-256 of the file is in ${NativeChecksums.FILE_NAME}. Check it with:

       Get-FileHash .\$packageFile -Algorithm SHA256
""".trimStart()
}
