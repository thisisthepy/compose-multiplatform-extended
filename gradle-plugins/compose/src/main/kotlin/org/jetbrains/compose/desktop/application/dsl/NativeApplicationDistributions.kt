/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.dsl

import org.gradle.api.Action
import java.util.*

abstract class NativeApplicationDistributions : AbstractDistributions() {
    private val supportedFormats = EnumSet.of(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Exe)

    override fun targetFormats(vararg formats: TargetFormat) {
        val unsupportedFormats = formats.filter { it !in supportedFormats }
        if (unsupportedFormats.isNotEmpty()) {
            error(
                "nativeApplication.distributions.targetFormats " +
                    "does not support the following formats: " +
                    unsupportedFormats.joinToString(", ")
            )
        }
        super.targetFormats(*formats)
    }

    val msix: NativeMsixSettings = objects.newInstance(NativeMsixSettings::class.java)
    open fun msix(fn: Action<NativeMsixSettings>) {
        fn.execute(msix)
    }

    val flatpak: NativeFlatpakSettings = objects.newInstance(NativeFlatpakSettings::class.java)
    open fun flatpak(fn: Action<NativeFlatpakSettings>) {
        fn.execute(flatpak)
    }

    val appImage: NativeAppImageSettings = objects.newInstance(NativeAppImageSettings::class.java)
    open fun appImage(fn: Action<NativeAppImageSettings>) {
        fn.execute(appImage)
    }

    val linux: LinuxPlatformSettings = objects.newInstance(LinuxPlatformSettings::class.java)
    open fun linux(fn: Action<LinuxPlatformSettings>) {
        fn.execute(linux)
    }

    val windows: WindowsPlatformSettings = objects.newInstance(WindowsPlatformSettings::class.java)
    open fun windows(fn: Action<WindowsPlatformSettings>) {
        fn.execute(windows)
    }

    val macOS: NativeApplicationMacOSPlatformSettings = objects.newInstance(NativeApplicationMacOSPlatformSettings::class.java)
    open fun macOS(fn: Action<NativeApplicationMacOSPlatformSettings>) {
        fn.execute(macOS)
    }
}