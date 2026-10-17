/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.internal

import org.gradle.api.GradleException
import org.gradle.api.Action
import org.gradle.api.file.CopySpec
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.jetbrains.compose.desktop.application.dsl.ApplicationOutput
import org.jetbrains.compose.desktop.application.tasks.AbstractNativeImageTask
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

    // The executable and what nativeImage.besideExecutable names, in a directory of their own,
    // so packageNativeImage's output stays the one executable.
    val stageTask = tasks.register<Sync>(taskNameAction = "stage", taskNameObject = "nativeImage") {
        description = "Copies the native image executable and the files nativeImage.besideExecutable names into one directory."
        configureNativeImageStaging(
            this,
            packageTask.flatMap { it.destinationDir },
            settings.besideExecutableSpecs,
            project.layout.buildDirectory.dir("compose/native-image-staged/${appDirName}"),
        )
    }

    // The one entry point for whichever output the application chose; the metadata they share
    // comes from nativeDistributions. A native image with files beside it is the staged directory.
    project.tasks.register("packageApplication") { task ->
        task.group = "compose desktop"
        task.description = "Packages the application as the output the application block names."
        task.dependsOn(app.output.map { output ->
            if (project.tasks.findByName(output.packageTask) == null) {
                throw GradleException("output = $output needs the task ${output.packageTask}, which this build does not define.")
            }
            if (output == ApplicationOutput.NativeImage && settings.besideExecutableSpecs.isNotEmpty()) stageTask.name else output.packageTask
        })
    }

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
            commandLine(nativeImageAgentCommandLine(File(home, "bin/java"), output, runtime.files, main, app.jvmArgs, app.args))
        }
    }
}

/**
 * The application on GraalVM's JVM with the tracing agent. The application's `jvmArgs` come
 * before the classpath, so a flag the image is built with, such as
 * `--enable-native-access=ALL-UNNAMED`, applies to the run that collects its metadata too.
 */
internal fun nativeImageAgentCommandLine(
    java: File,
    metadataDirectory: File,
    classpath: Collection<File>,
    mainClass: String,
    jvmArgs: List<String>,
    args: List<String>,
): List<String> =
    listOf(java.absolutePath, "-agentlib:native-image-agent=config-merge-dir=${metadataDirectory.absolutePath}") +
        jvmArgs +
        listOf("-cp", classpath.joinToString(File.pathSeparator) { it.absolutePath }, mainClass) +
        args

/**
 * `stageNativeImage`: the executable at the top of [destination], and each of [specs] as a child
 * copy spec beside it, so an `into` in one of them names a directory inside [destination]
 * rather than moving the whole stage. The task does nothing when [specs] is empty.
 */
internal fun configureNativeImageStaging(
    sync: Sync,
    executableDirectory: Provider<Directory>,
    specs: List<Action<in CopySpec>>,
    destination: Provider<Directory>,
) {
    // Read once the build script has run (the task is configured when it is first needed), and
    // kept as a plain value so the configuration cache does not hold the specs themselves.
    val staged = specs.isNotEmpty()
    sync.onlyIf { staged }
    sync.from(executableDirectory)
    for (spec in specs) sync.with(sync.project.copySpec(spec))
    sync.into(destination)
}
