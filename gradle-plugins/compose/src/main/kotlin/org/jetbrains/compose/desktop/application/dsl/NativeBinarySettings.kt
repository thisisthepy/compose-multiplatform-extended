package org.jetbrains.compose.desktop.application.dsl

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

/**
 * What the Kotlin/Native executable of a `compose.nativeApplication` links beyond the
 * Kotlin klibs: the window system libraries of the platform, the Skia static archives, and
 * any options the application adds.
 */
abstract class NativeBinarySettings @Inject constructor(objects: ObjectFactory) {
    /** The function that starts the application, for example `hello.main`. Empty keeps the Kotlin default. */
    val entryPoint: Property<String> = objects.property(String::class.java)

    /** Extra linker options, appended after the ones the plugin adds for the target. */
    val linkerOpts: ListProperty<String> = objects.listProperty(String::class.java)

    /**
     * A directory holding skiko's static archives for the target (libskiko-*.a and Skia).
     * When set it is passed to the linker with `-L` and the archives are linked in.
     */
    val nativeSkikoDirectory: DirectoryProperty = objects.directoryProperty()

    /** Link the X11 and GL libraries on Linux. On by default; an application with its own window layer turns it off. */
    val linkWindowSystem: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
}
