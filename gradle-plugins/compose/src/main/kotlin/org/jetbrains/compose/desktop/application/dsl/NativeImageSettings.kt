/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.dsl

import org.gradle.api.Action
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * The application as one GraalVM native image executable, with no Java runtime beside it.
 *
 * `packageNativeImage` builds it, `runNativeImageAgent` collects the reachability metadata
 * it needs by running the application on GraalVM's JVM. macOS arm64 only so far.
 */
abstract class NativeImageSettings @Inject constructor(objects: ObjectFactory) {
    /**
     * A checkout of compose-multiplatform-core-extended. Its `extended/window` C and
     * Objective-C sources are compiled into an [ApplicationWindowing.AwtFree] image.
     */
    val windowSourcesDirectory: DirectoryProperty = objects.directoryProperty()

    /**
     * The GraalVM that builds the image. On macOS it has to be a distribution that ships the
     * JDK's AWT as static archives, which Liberica NIK Full does. Defaults to GRAALVM_HOME.
     */
    val graalvmHome: Property<String> = objects.property(String::class.java)

    /** The executable's name. Defaults to the application's package name. */
    val imageName: Property<String> = objects.property(String::class.java)

    /**
     * Where the application's reachability metadata lives, which `runNativeImageAgent` writes
     * and `packageNativeImage` reads. Defaults to `src/main/native-image`.
     */
    val metadataDirectory: DirectoryProperty = objects.directoryProperty()

    /**
     * skiko's natives as a static archive, as `build-skiko-static-jvm.sh` in
     * compose-multiplatform-core-extended lays them out: `libskiko-static.a` and a `skia/`
     * directory beside it. Required: a single executable cannot load Skia from a file.
     */
    val skikoStaticDirectory: DirectoryProperty = objects.directoryProperty()

    /**
     * Windows only: the application manifest embedded in the executable, replacing the one
     * this plugin embeds. A process without a manifest is DPI unaware, and Windows stretches
     * its drawing to a scaled display; the built-in one declares what the JDK's java.exe does.
     * Only one manifest can be embedded, so setting this leaves the built-in one out.
     */
    val windowsManifest: RegularFileProperty = objects.fileProperty()

    /**
     * Arguments passed to native-image after the ones this plugin needs, as they are given.
     * An application that calls native code through the Foreign Function and Memory API adds
     * `--enable-native-access=ALL-UNNAMED` here (and to `jvmArgs`, which `runNativeImageAgent`
     * passes on). A library's own `META-INF/native-image` metadata, `foreign` registrations
     * included, needs nothing here: native-image reads it from the library's jar.
     */
    val buildArgs: ListProperty<String> = objects.listProperty(String::class.java)

    internal val besideExecutableSpecs: MutableList<Action<in CopySpec>> = mutableListOf()

    /**
     * Files the application needs beside its executable at run time, such as a language
     * runtime's prefix directory. `stageNativeImage` copies the executable and these files
     * into `build/compose/native-image-staged/<app>`, laid out as this spec says, and
     * `packageApplication` then makes that directory.
     *
     * Opt-in: an application that names nothing here gets no staged directory, and
     * `packageApplication` is the one executable alone. `packageNativeImage`'s own output is
     * the one executable either way, so what it makes is still checked as one file.
     *
     * ```
     * nativeImage {
     *     besideExecutable {
     *         from("python/prefix") { into("python") }
     *     }
     * }
     * ```
     */
    fun besideExecutable(action: Action<in CopySpec>) {
        besideExecutableSpecs += action
    }
}
