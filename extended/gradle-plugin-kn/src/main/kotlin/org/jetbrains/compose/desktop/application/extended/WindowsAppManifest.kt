/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.extended

import java.io.File

/**
 * The application manifest every Windows output of the plugin declares: per-monitor DPI
 * awareness, common controls 6 and the supported Windows versions. The GraalVM native image
 * task embeds it at link time, and the Kotlin/Native Windows packaging uses the same text, so
 * both outputs behave alike on a scaled display. Without a manifest a process is DPI unaware
 * and Windows stretches its 96 DPI drawing, which blurs the text.
 */
internal object WindowsAppManifest {
    private const val RESOURCE = "/org/jetbrains/compose/desktop/nativeimage/windows-app.manifest"

    /** The plugin's own manifest text. */
    fun defaultText(): String =
        WindowsAppManifest::class.java.getResource(RESOURCE)?.readText()
            ?: error("the plugin is missing its windows-app.manifest resource")

    /**
     * The manifest to embed: [override] when the build supplies one, otherwise the default
     * written into [workDir]. An executable can carry only one embedded manifest, so the
     * result is always a single file.
     */
    fun resolve(override: File?, workDir: File): File =
        override ?: workDir.resolve("windows-app.manifest").apply {
            parentFile.mkdirs()
            writeText(defaultText())
        }
}
