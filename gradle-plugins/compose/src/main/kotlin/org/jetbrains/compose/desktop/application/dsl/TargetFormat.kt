/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.dsl

import org.jetbrains.compose.internal.utils.OS
import org.jetbrains.compose.internal.utils.currentOS

enum class TargetFormat(
    internal val id: String,
    internal val targetOS: OS
) {
    AppImage("app-image", currentOS),
    Deb("deb", OS.Linux),
    Rpm("rpm", OS.Linux),
    Dmg("dmg", OS.MacOS),
    Pkg("pkg", OS.MacOS),
    Exe("exe", OS.Windows),
    Msi("msi", OS.Windows),

    /** A `.flatpak` bundle, with a manifest for Flathub. Built by `extended/gradle-plugin-kn`. */
    Flatpak("flatpak", OS.Linux),

    /** An unsigned `.msix` package, for sideloading or the Microsoft Store. Built by `extended/gradle-plugin-kn`. */
    Msix("msix", OS.Windows);

    val isCompatibleWithCurrentOS: Boolean by lazy { isCompatibleWith(currentOS) }

    internal fun isCompatibleWith(os: OS): Boolean = os == targetOS

    val outputDirName: String
        get() = when (this) {
            AppImage -> "app"
            else -> id
        }

    val fileExt: String
        get() {
            check(this != AppImage) { "$this cannot have a file extension" }
            return ".$id"
        }
}