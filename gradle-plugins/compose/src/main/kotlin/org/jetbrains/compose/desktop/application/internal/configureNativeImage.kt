/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.internal

import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec
import org.jetbrains.compose.desktop.application.dsl.ApplicationWindowing
import org.jetbrains.compose.desktop.application.tasks.AbstractNativeImageTask
import org.jetbrains.compose.desktop.application.tasks.CheckNativeImageNoAwtTask
import org.jetbrains.compose.internal.utils.OS
import org.jetbrains.compose.internal.utils.currentOS
import java.io.File

/**
 * `packageNativeImage` and `runNativeImageAgent`: the application as one GraalVM native image.
 *
 * Registered once per application rather than per build type: a native image is optimised by
 * the GraalVM compiler whatever the build type, and ProGuard's output is not what an image
 * should be analysed from.
 */
internal fun JvmApplicationContext.configureNativeImage() {
    val settings = app.nativeImage
    val graalvmHome = settings.graalvmHome.orElse(project.providers.environmentVariable("GRAALVM_HOME"))
    val metadataDirectory = settings.metadataDirectory.convention(project.layout.projectDirectory.dir("src/main/native-image"))

    // The one entry point for whichever output the application chose; the metadata they share
    // comes from nativeDistributions.
    project.tasks.register("packageApplication") { task ->
        task.group = "compose desktop"
        task.description = "Packages the application as the output the application block names."
        task.dependsOn(app.output.map { output ->
            if (project.tasks.findByName(output.packageTask) == null) {
                throw GradleException("output = $output needs the task ${output.packageTask}, which this build does not define.")
            }
            output.packageTask
        })
    }

    val packageTask = tasks.register<AbstractNativeImageTask>(taskNameAction = "package", taskNameObject = "nativeImage") {
        description = "Builds the application as one GraalVM native image executable."
        useAppRuntimeFiles { (runtimeJars, _) -> runtimeClasspath.from(runtimeJars) }
        mainClass.set(nullableProvider { app.mainClass })
        this.graalvmHome.set(graalvmHome)
        imageName.set(settings.imageName.orElse(packageNameProvider))
        this.metadataDirectory.set(metadataDirectory.map { if (it.asFile.isDirectory) it else null })
        skikoStaticDirectory.set(settings.skikoStaticDirectory)
        windowing.set(app.windowing)
        windowSourcesDirectory.set(settings.windowSourcesDirectory)
        buildArgs.set(settings.buildArgs)
        windowsManifest.set(settings.windowsManifest)
        destinationDir.set(project.layout.buildDirectory.dir("compose/native-image/${appDirName}"))
    }

    // An AwtFree image is checked once it is linked: a symbol or library name of AWT in the
    // executable fails the build. The type check runs inside native-image itself.
    val checkNoAwt = tasks.register<CheckNativeImageNoAwtTask>(taskNameAction = "check", taskNameObject = "nativeImageNoAwt") {
        description = "Fails when the native image executable still refers to AWT."
        onlyIf { app.windowing.get() == ApplicationWindowing.AwtFree }
        executable.set(
            packageTask.flatMap { image ->
                image.imageName.zip(image.destinationDir) { name, dir ->
                    dir.file(if (currentOS == OS.Windows) "$name.exe" else name)
                }
            }
        )
    }
    packageTask.configure { it.finalizedBy(checkNoAwt) }

    // The application run on GraalVM's JVM with the tracing agent, writing the reachability
    // metadata the image is built from. The agent writes on a clean exit, so the application
    // has to be closed rather than killed. An Exec rather than a JavaExec, because the Kotlin
    // plugin gives every JavaExec a toolchain launcher, and the agent belongs to GraalVM's java.
    tasks.register<Exec>(taskNameAction = "run", taskNameObject = "nativeImageAgent") {
        description = "Runs the application with GraalVM's tracing agent to collect reachability metadata."
        val runtime = project.objects.fileCollection()
        useAppRuntimeFiles { (runtimeJars, _) -> runtime.from(runtimeJars) }
        doFirst {
            val home = graalvmHome.orNull
                ?: throw GradleException("Set nativeImage.graalvmHome or GRAALVM_HOME to a GraalVM.")
            val main = app.mainClass ?: throw GradleException("compose.desktop.application.mainClass is not set.")
            val output = metadataDirectory.get().asFile.apply { mkdirs() }
            commandLine(
                listOf(
                    File(home, "bin/java").absolutePath,
                    "-agentlib:native-image-agent=config-merge-dir=${output.absolutePath}",
                    "-cp", runtime.files.joinToString(File.pathSeparator),
                    main,
                ) + app.args
            )
        }
    }
}
