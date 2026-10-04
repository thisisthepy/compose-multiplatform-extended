/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.extended

import java.io.File

/**
 * `nativeDistributions { linux { flatpak { ... } } }`: settings of the Flatpak bundle
 * (`TargetFormat.Flatpak`).
 */
open class FlatpakSettings {
    /**
     * The application id, in reverse DNS form (`org.example.hello`). Flathub requires the id
     * to name a domain the submitter controls. Defaults to `org.example.<package name>`, which
     * builds and installs but is refused by Flathub.
     */
    var appId: String? = null

    var runtime: String = "org.freedesktop.Platform"
    var runtimeVersion: String = FlatpakManifest.DEFAULT_RUNTIME_VERSION

    /** Sandbox permissions added to the defaults (X11, shared IPC, GPU), as `flatpak build-finish` arguments. */
    val finishArgs: MutableList<String> = mutableListOf()

    var summary: String? = null
    var developerName: String? = null
    var homepage: String? = null

    /** SPDX id of the application's license, for the AppStream metadata. */
    var license: String = "LicenseRef-proprietary"
    var metadataLicense: String = "CC0-1.0"
    val releaseNotes: MutableList<String> = mutableListOf()

    /**
     * Where the payload archive (`<name>-<version>-<arch>.tar.gz`, next to the bundle) will be
     * published. When set, a manifest in the shape Flathub accepts is written beside the local
     * one, with that URL and the archive's digest as its source.
     */
    var payloadUrl: String? = null

    /** `flatpak-builder` and `flatpak` to use; both are found on `PATH` when not set. */
    var flatpakBuilder: File? = null
    var flatpak: File? = null

    /** The remote the runtime and SDK are installed from when missing. */
    var runtimeRemote: String = "flathub"
}

/**
 * `nativeDistributions { windows { msix { ... } } }`: settings of the `.msix` package
 * (`TargetFormat.Msix`). The package is built unsigned.
 */
open class MsixSettings {
    /** The package identity name. Defaults to `<vendor>.<package name>`. */
    var identityName: String? = null

    /**
     * The package publisher, as the distinguished name of the certificate that will sign the
     * package, such as `CN=Example Corp`. Defaults to `CN=<vendor>`. For a Store submission it is
     * the value Partner Center shows for the reserved app.
     */
    var publisher: String? = null
    var publisherDisplayName: String? = null
    var displayName: String? = null

    var minVersion: String = MsixManifest.DEFAULT_MIN_VERSION
    var maxVersionTested: String = MsixManifest.DEFAULT_MAX_VERSION_TESTED
    val languages: MutableList<String> = mutableListOf("en-us")

    /** Capabilities besides `runFullTrust`, which a desktop executable always needs. */
    val capabilities: MutableList<String> = mutableListOf()

    /**
     * A PNG to make the tile and store images from. Without it the PNG of `linux.iconFile` is
     * used when there is one, and the plugin's default icon otherwise.
     */
    var logoFile: File? = null

    /** `makeappx.exe` to use. Without it the newest one in the Windows SDK is used. */
    var makeAppx: File? = null
}
