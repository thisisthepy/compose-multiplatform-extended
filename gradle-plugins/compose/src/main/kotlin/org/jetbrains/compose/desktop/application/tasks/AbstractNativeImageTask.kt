/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.tasks

import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.compose.desktop.tasks.AbstractComposeDesktopTask
import org.jetbrains.compose.internal.utils.OS
import org.jetbrains.compose.internal.utils.currentArch
import org.jetbrains.compose.internal.utils.Arch
import org.jetbrains.compose.internal.utils.currentOS
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.zip.ZipFile

/**
 * Builds the application as one GraalVM native image executable.
 *
 * A Compose desktop application reaches native code through JNI libraries a JVM opens as
 * files: AWT's platform toolkit, JAWT, and Skia. Here each is linked in from a static archive
 * and the image is told so, which is what makes the result one file:
 *
 * - AWT's toolkit and its helpers are linked forced, and a feature (compiled here, against the
 *   GraalVM doing the build) registers them as built in. AWT opens its toolkit by path from its
 *   own directory, so `System.load` is substituted to load a built-in library by name instead.
 * - GraalVM resolves `JNI_OnLoad_<name>` at link time only for a fixed list of its own
 *   libraries and looks every other one up by name, so those are exported.
 * - Skia comes from a static archive of skiko's natives. skiko's own bindings are linked
 *   forced, because their JNI entry points are reached by name; Skia's archives are linked as
 *   ordinary archives, because its module archives repeat its core objects.
 * - skiko declares every platform's JNI methods and the image refers to all of them. The
 *   ones this platform does not implement are defined as stops, found by reading skiko's
 *   classes for native methods and subtracting what the archive defines.
 */
abstract class AbstractNativeImageTask : AbstractComposeDesktopTask() {

    @get:Classpath
    val runtimeClasspath: ConfigurableFileCollection = objects.fileCollection()

    @get:Input
    abstract val mainClass: Property<String>

    @get:Input
    abstract val graalvmHome: Property<String>

    @get:Input
    abstract val imageName: Property<String>

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val metadataDirectory: DirectoryProperty

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val skikoStaticDirectory: DirectoryProperty

    @get:Input
    abstract val buildArgs: ListProperty<String>

    @get:OutputDirectory
    abstract val destinationDir: DirectoryProperty

    @get:Internal
    val workDir: File get() = temporaryDir

    @TaskAction
    fun build() {
        if (currentOS != OS.MacOS || currentArch != Arch.Arm64) {
            throw GradleException(
                "packageNativeImage builds macOS arm64 only so far (this is $currentOS $currentArch). " +
                    "On Windows the JDK's AWT is not shipped as static archives by GraalVM, which is the " +
                    "open question for a single Windows executable."
            )
        }
        val graalvm = File(graalvmHome.get())
        val nativeImage = graalvm.resolve("bin/native-image")
        if (!nativeImage.isFile) {
            throw GradleException("$nativeImage does not exist. graalvmHome has to name a GraalVM with native-image.")
        }
        val staticAwt = graalvm.resolve("lib/static/darwin-aarch64")
        for (archive in listOf("libawt_lwawt.a", "libosxui.a", "libjawt.a")) {
            if (!staticAwt.resolve(archive).isFile) {
                throw GradleException(
                    "$graalvm has no ${staticAwt.resolve(archive)}. A single executable links AWT statically, " +
                        "and only a distribution that ships the JDK's static archives can: Liberica NIK Full does."
                )
            }
        }
        val skiko = skikoStaticDirectory.orNull?.asFile ?: throw GradleException(
            "nativeImage.skikoStaticDirectory is not set. A single executable cannot load Skia from a file; " +
                "build the archive with build-skiko-static-jvm.sh from compose-multiplatform-core-extended and point this at its output."
        )
        val skikoArchive = skiko.resolve("libskiko-static.a")
        val skiaArchives = skiko.resolve("skia").listFiles { file -> file.name.endsWith(".a") }?.sortedBy { it.name }
        if (!skikoArchive.isFile || skiaArchives.isNullOrEmpty()) {
            throw GradleException("$skiko has to hold libskiko-static.a and skia/*.a, as build-skiko-static-jvm.sh lays them out.")
        }

        workDir.deleteRecursively()
        workDir.mkdirs()
        val supportJar = buildSupportJar(graalvm)
        val onLoad = compileC(graalvm, resourceText("static_onload.c"), "static_onload")
        val stubs = compileC(graalvm, foreignStubs(skikoArchive), "foreign_stubs")

        val output = destinationDir.get().asFile
        output.deleteRecursively()
        output.mkdirs()

        val classpath = (runtimeClasspath.files + supportJar).joinToString(File.pathSeparator)
        val linked = listOf(
            "awt_lwawt:sun_lwawt|sun_java2d_metal|sun_java2d_opengl|sun_font|sun_awt",
            "osxui:com_apple_laf",
            "skiko:org_jetbrains_skia|org_jetbrains_skiko",
        ).joinToString(",")
        val link = mutableListOf<String>()
        fun linker(vararg options: String) = options.forEach { link += "-H:NativeLinkerOption=$it" }
        for (archive in listOf("libawt_lwawt.a", "libosxui.a", "libjawt.a")) {
            linker("-Wl,-force_load,${staticAwt.resolve(archive)}")
        }
        linker("-Wl,-force_load,$skikoArchive")
        // libskia first, so Skia's core objects come from it rather than a module archive.
        val skia = skiaArchives.sortedBy { if (it.name == "libskia.a") 0 else 1 }
        skia.forEach { linker(it.absolutePath) }
        linker(onLoad.absolutePath, stubs.absolutePath)
        for (framework in listOf("Metal", "MetalKit", "IOKit")) {
            linker("-framework", framework)
        }
        for (library in listOf("awt_lwawt", "osxui", "skiko")) {
            linker("-Wl,-exported_symbol,_JNI_OnLoad_$library")
        }

        val args = mutableListOf(
            nativeImage.absolutePath,
            "-cp", classpath,
            "--no-fallback",
            "-Dcompose.nativeimage.staticLibraries=$linked",
        )
        metadataDirectory.orNull?.asFile?.takeIf { it.isDirectory }?.let {
            args += "-H:ConfigurationFileDirectories=${it.absolutePath}"
        }
        args += link
        args += buildArgs.get()
        args += listOf("-o", output.resolve(imageName.get()).absolutePath, mainClass.get())

        logger.lifecycle("native-image: building ${imageName.get()}, which takes a few minutes")
        run(args, workDir.resolve("native-image.log"))
        logger.lifecycle("The executable is written to ${output.resolve(imageName.get())}")
    }

    /** The feature and substitutions, compiled by the GraalVM doing the build. */
    private fun buildSupportJar(graalvm: File): File {
        val sources = workDir.resolve("support/src/org/jetbrains/compose/nativeimage").apply { mkdirs() }
        for (name in listOf("LinkedLibraries", "StaticDesktopLibrariesFeature", "Substitutions")) {
            sources.resolve("$name.java").writeText(resourceText("$name.java.txt"))
        }
        val classes = workDir.resolve("support/classes").apply { mkdirs() }
        run(
            listOf(graalvm.resolve("bin/javac").absolutePath, "-d", classes.absolutePath, "--add-modules", "org.graalvm.nativeimage") +
                sources.listFiles()!!.map { it.absolutePath },
            workDir.resolve("javac.log"),
        )
        classes.resolve("META-INF/native-image/org.jetbrains.compose/native-image-support").apply {
            mkdirs()
            resolve("native-image.properties").writeText(resourceText("native-image.properties"))
        }
        val jar = workDir.resolve("support/native-image-support.jar")
        run(listOf(graalvm.resolve("bin/jar").absolutePath, "cf", jar.absolutePath, "-C", classes.absolutePath, "."), workDir.resolve("jar.log"))
        return jar
    }

    private fun compileC(graalvm: File, source: String, name: String): File {
        val file = workDir.resolve("$name.c").apply { writeText(source) }
        val obj = workDir.resolve("$name.o")
        run(
            listOf("cc", "-c", "-O2", "-arch", "arm64", "-I${graalvm.resolve("include")}", "-I${graalvm.resolve("include/darwin")}",
                file.absolutePath, "-o", obj.absolutePath),
            workDir.resolve("$name.log"),
        )
        return obj
    }

    /**
     * Stops for the skiko JNI methods this platform does not implement: every native method
     * declared in skiko's classes, by its JNI name, less the ones the archive defines.
     */
    private fun foreignStubs(skikoArchive: File): String {
        val defined = workDir.resolve("skiko-symbols.txt").let { listing ->
            run(listOf("nm", "-g", skikoArchive.absolutePath), listing, quiet = true)
            listing.readLines().mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size == 3 && parts[1] == "T") parts[2].removePrefix("_") else null
            }.toSet()
        }
        val declared = declaredSkikoNatives()
        val foreign = (declared - defined).sorted()
        return buildString {
            appendLine("/* Generated by packageNativeImage: skiko JNI methods of other platforms, defined as stops. */")
            appendLine("#include <stdio.h>")
            appendLine("#include <stdlib.h>")
            appendLine("static void foreign_entry_point(const char *name) {")
            appendLine("    fprintf(stderr, \"%s belongs to another platform and was called on this one\\n\", name);")
            appendLine("    abort();")
            appendLine("}")
            for (name in foreign) appendLine("void $name(void) { foreign_entry_point(\"$name\"); }")
        }
    }

    private fun declaredSkikoNatives(): Set<String> {
        val jars = runtimeClasspath.files.filter { it.isFile && it.name.endsWith(".jar") }
        val loader = URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), null)
        val names = HashSet<String>()
        loader.use {
            for (jar in jars) {
                ZipFile(jar).use { zip ->
                    for (entry in zip.entries()) {
                        val path = entry.name
                        if (!path.endsWith(".class") || path.contains('-')) continue
                        if (!path.startsWith("org/jetbrains/skia/") && !path.startsWith("org/jetbrains/skiko/")) continue
                        val className = path.removeSuffix(".class").replace('/', '.')
                        val type = try {
                            Class.forName(className, false, loader)
                        } catch (_: Throwable) {
                            continue
                        }
                        val methods = try { type.declaredMethods } catch (_: Throwable) { continue }
                        for (method in methods) {
                            if (Modifier.isNative(method.modifiers)) {
                                names += "Java_" + jniMangle(className.replace('.', '/')) + "_" + jniMangle(method.name)
                            }
                        }
                    }
                }
            }
        }
        return names
    }

    private fun resourceText(name: String): String =
        AbstractNativeImageTask::class.java.getResource("/org/jetbrains/compose/desktop/nativeimage/$name")?.readText()
            ?: error("the plugin is missing its resource $name")

    private fun run(command: List<String>, log: File, quiet: Boolean = false) {
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
        val exit = process.waitFor()
        if (exit != 0) {
            val tail = log.readLines().takeLast(40).joinToString("\n")
            throw GradleException("${File(command.first()).name} failed with exit code $exit. Full log: $log\n$tail")
        }
        if (!quiet) logger.info(log.readText())
    }

    internal companion object {
        /** The JNI short name mangling: '/' is '_', and '_', ';', '[' and non-ASCII are escaped. */
        fun jniMangle(name: String): String = buildString {
            for (char in name) {
                when {
                    char == '/' -> append('_')
                    char == '_' -> append("_1")
                    char == ';' -> append("_2")
                    char == '[' -> append("_3")
                    char.code < 128 && (char.isLetterOrDigit()) -> append(char)
                    else -> append("_0").append(String.format("%04x", char.code))
                }
            }
        }
    }
}
