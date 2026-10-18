/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.internal

/**
 * Which thread `main` runs on in an AWT-free native image on macOS.
 *
 * The AppKit window takes its calls on the process's first thread, and hands a call made on any
 * other thread to that one and waits for it. So `main` has to run on the first thread, which is
 * what `-H:-RunMainInNewThread` asks for. Liberica NIK 25 runs `main` on a new thread on macOS
 * whatever that option says, and keeps the first thread waiting for `main` to end; the first
 * window call that crosses then waits forever. Upstream GraalVM honours the option.
 */
internal object NativeImageMainThread {
    /** The options that keep `main` on the first thread, to be given while experimental options are unlocked. */
    val options: List<String> = listOf("-H:-RunMainInNewThread")

    /**
     * Why the GraalVM described by [release], the text of its `release` file, cannot build an
     * AWT-free macOS application, or null when it can.
     */
    fun problem(release: String): String? {
        val implementor = Regex("""^IMPLEMENTOR="?([^"\n]*)"?""", RegexOption.MULTILINE).find(release)?.groupValues?.get(1).orEmpty()
        val liberica = implementor.contains("BellSoft", ignoreCase = true) ||
            release.contains("Liberica", ignoreCase = true)
        if (!liberica) return null
        return "This GraalVM is Liberica NIK (IMPLEMENTOR=\"$implementor\"). On macOS it runs main on a new thread " +
            "whatever -H:-RunMainInNewThread says, and an AWT-free window then waits forever on its first call, " +
            "which has to run on the process's first thread. Build windowing = AwtFree on macOS with upstream " +
            "GraalVM 25 (graalvm-community-jdk-25), set as nativeImage.graalvmHome or GRAALVM_HOME. " +
            "Liberica NIK Full is what windowing = Awt needs, for the JDK's static AWT libraries."
    }
}
