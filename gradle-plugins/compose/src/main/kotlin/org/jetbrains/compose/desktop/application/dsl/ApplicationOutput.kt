/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.dsl

/**
 * What `packageApplication` makes. The metadata every output shares (name, version, vendor,
 * description, icons, resources) is read from `nativeDistributions`, so switching the output
 * does not mean describing the application again.
 */
enum class ApplicationOutput(internal val packageTask: String) {
    /** An application with a Java runtime beside it, packaged by jpackage. */
    Jvm("packageDistributionForCurrentOS"),

    /** One GraalVM native image executable, `packageNativeImage`. */
    NativeImage("packageNativeImage"),

    /** One Kotlin/Native executable, `packageKotlinNative`. */
    KotlinNative("packageKotlinNative"),
}

/** How an application gets its window. */
enum class ApplicationWindowing {
    /** The JDK's AWT, an ordinary Compose desktop window. */
    Awt,

    /** No AWT: the window comes from the extended window modules of compose-multiplatform-core-extended. */
    AwtFree,
}
