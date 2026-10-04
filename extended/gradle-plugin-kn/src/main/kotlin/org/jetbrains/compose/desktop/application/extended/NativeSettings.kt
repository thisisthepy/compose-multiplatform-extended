/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.extended

/** `nativeDistributions { msix { ... } }`: the Windows package of a Kotlin/Native window app. */
open class NativeMsixSettings {
    /** The package identity name. Partner Center shows the value to use for a reserved app name. */
    var identityName: String? = null

    /** The publisher, `CN=...`. For the Store, the value Partner Center shows. */
    var publisher: String? = null
    var publisherDisplayName: String? = null

    /** [MsixChannel.Sideload] (the default) or [MsixChannel.Store], which keeps the revision at zero. */
    var channel: MsixChannel = MsixChannel.Sideload

    /** The build number, the fourth part of the version, for the sideload channel. */
    var revision: Int? = null
    var capabilities: List<String> = listOf("internetClient")
    var languages: List<String> = listOf("en-us")

    /** A PNG the tiles are drawn from. Defaults to the Linux icon. */
    var iconFile: String? = null

    /** Signs the package when set. An unsigned package comes with install instructions. */
    var certificateFile: String? = null
    var certificatePassword: String? = null
}

/** `nativeDistributions { flatpak { ... } }`. */
open class NativeFlatpakSettings {
    var runtimeVersion: String = FlatpakManifest.DEFAULT_RUNTIME_VERSION

    /** Give the sandbox a Wayland socket, with X11 as the fallback. */
    var wayland: Boolean = false
    var finishArgs: List<String> = emptyList()
    var summary: String? = null
    var license: String = "LicenseRef-proprietary"
    var homepage: String? = null
}

/** `nativeDistributions { appImage { ... } }`. */
open class NativeAppImageSettings {
    /**
     * The update information embedded in the AppImage, which also makes `appimagetool` write a
     * `.zsync` file. For GitHub Releases: `gh-releases-zsync|owner|repo|latest|name-*-x86_64.AppImage.zsync`.
     */
    var updateInformation: String? = null
}
