/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.compose.desktop.application.internal.NativeImageNoAwt

/**
 * Fails the build when an AwtFree native image executable still refers to AWT: a JNI symbol
 * of AWT, Java 2D or JAWT, or the name of one of their libraries.
 *
 * The other half of the check runs inside native-image, which stops the build when a
 * root type of AWT becomes reachable; see [NativeImageNoAwt.forbiddenTypes].
 */
@DisableCachingByDefault(because = "It only reads the executable")
abstract class CheckNativeImageNoAwtTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val executable: RegularFileProperty

    @TaskAction
    fun check() {
        val file = executable.get().asFile
        if (!file.isFile) throw GradleException("$file does not exist, so there is no executable to check.")
        val beside = NativeImageNoAwt.awtLibrariesBeside(file.parentFile)
        if (beside.isNotEmpty()) {
            throw GradleException(
                "native-image wrote ${beside.joinToString()} beside $file, which it does for the JDK's AWT libraries " +
                    "an image can reach. An AwtFree image reaches none."
            )
        }
        val found = NativeImageNoAwt.scanExecutable(file)
        if (found.isNotEmpty()) {
            throw GradleException(
                "$file is meant to hold no AWT but refers to ${found.joinToString { "'$it'" }}. " +
                    "Something on the application's classpath still reaches java.desktop; " +
                    "build with --verbose and read the native-image analysis to find what."
            )
        }
    }
}
