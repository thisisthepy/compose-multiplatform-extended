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
        val platform = when {
            currentOS == OS.MacOS && currentArch == Arch.Arm64 -> Platform.MacosArm64
            currentOS == OS.Windows && currentArch == Arch.X64 -> Platform.WindowsX64
            else -> throw GradleException(
                "packageNativeImage builds macOS arm64 and Windows x64 so far (this is $currentOS $currentArch)."
            )
        }
        val graalvm = File(graalvmHome.get())
        val nativeImage = graalvm.resolve(if (platform == Platform.WindowsX64) "bin/native-image.cmd" else "bin/native-image")
        if (!nativeImage.isFile) {
            throw GradleException("$nativeImage does not exist. graalvmHome has to name a GraalVM with native-image.")
        }
        val staticJdk = graalvm.resolve(platform.staticJdkDirectory)
        for (archive in platform.staticJdkArchives) {
            if (!staticJdk.resolve(archive).isFile) {
                throw GradleException(
                    "$graalvm has no ${staticJdk.resolve(archive)}. A single executable links AWT statically, " +
                        "and only a distribution that ships the JDK's static archives can: Liberica NIK Full does."
                )
            }
        }
        val skiko = skikoStaticDirectory.orNull?.asFile ?: throw GradleException(
            "nativeImage.skikoStaticDirectory is not set. A single executable cannot load Skia from a file; " +
                "build the archive with build-skiko-static-jvm.sh from compose-multiplatform-core-extended and point this at its output."
        )
        val skikoArchive = skiko.resolve(platform.skikoArchive)
        val skiaArchives = skiko.resolve("skia").listFiles { file -> file.name.endsWith(platform.archiveSuffix) }?.sortedBy { it.name }
        if (!skikoArchive.isFile || skiaArchives.isNullOrEmpty()) {
            throw GradleException("$skiko has to hold ${platform.skikoArchive} and skia/*${platform.archiveSuffix}, as build-skiko-static-jvm.sh lays them out.")
        }

        workDir.deleteRecursively()
        workDir.mkdirs()
        val supportJar = buildSupportJar(graalvm)

        val output = destinationDir.get().asFile
        output.deleteRecursively()
        output.mkdirs()

        val classpath = (runtimeClasspath.files + supportJar).joinToString(File.pathSeparator)
        val (linked, link) = when (platform) {
            Platform.MacosArm64 -> macosLink(graalvm, staticJdk, skikoArchive, skiaArchives)
            Platform.WindowsX64 -> windowsLink(graalvm, staticJdk, skikoArchive, skiaArchives)
        }

        val args = mutableListOf(
            nativeImage.absolutePath,
            "-cp", classpath,
            "--no-fallback",
            "-Dcompose.nativeimage.staticLibraries=${linked.joinToString(",")}",
        )
        metadataDirectory.orNull?.asFile?.takeIf { it.isDirectory }?.let {
            args += "-H:ConfigurationFileDirectories=${it.absolutePath}"
        }
        args += link
        args += buildArgs.get()
        args += listOf("-o", output.resolve(imageName.get()).absolutePath, mainClass.get())

        logger.lifecycle("native-image: building ${imageName.get()}, which takes a few minutes")
        // Through an argument file: a classpath alone runs past the 8191 characters Windows
        // allows a command line through native-image's .cmd launcher.
        val argFile = workDir.resolve("native-image.args")
        argFile.writeText(args.drop(1).joinToString("\n") { quoteArgument(it) })
        run(listOf(args.first(), "@${argFile.absolutePath}"), workDir.resolve("native-image.log"))
        logger.lifecycle("The executable is written to ${output.resolve(imageName.get())}")
    }

    private fun macosLink(graalvm: File, staticJdk: File, skikoArchive: File, skiaArchives: List<File>): Pair<List<String>, List<String>> {
        val onLoad = compileC(graalvm, resourceText("static_onload.c"), "static_onload")
        val stubs = compileC(graalvm, foreignStubs(skikoArchive), "foreign_stubs")
        val linked = listOf(
            "awt_lwawt:sun_lwawt|sun_java2d_metal|sun_java2d_opengl|sun_font|sun_awt",
            "osxui:com_apple_laf",
            "skiko:org_jetbrains_skia|org_jetbrains_skiko",
        )
        val link = mutableListOf<String>()
        fun linker(vararg options: String) = options.forEach { link += "-H:NativeLinkerOption=$it" }
        for (archive in Platform.MacosArm64.staticJdkArchives) {
            linker("-Wl,-force_load,${staticJdk.resolve(archive)}")
        }
        linker("-Wl,-force_load,$skikoArchive")
        // libskia first, so Skia's core objects come from it rather than a module archive.
        skiaArchives.sortedBy { if (it.name == "libskia.a") 0 else 1 }.forEach { linker(it.absolutePath) }
        linker(onLoad.absolutePath, stubs.absolutePath)
        for (framework in listOf("Metal", "MetalKit", "IOKit")) {
            linker("-framework", framework)
        }
        for (library in listOf("awt_lwawt", "osxui", "skiko")) {
            linker("-Wl,-exported_symbol,_JNI_OnLoad_$library")
        }
        return linked to link
    }

    /**
     * Windows: the JDK's static libraries are built against the C runtime DLL (/MD), and
     * JetBrains' Skia against the static one (/MT). The MSVC linker refuses to mix them on a
     * guard each object carries, `/FAILIFMISMATCH:"RuntimeLibrary=..."`. The guard is there for
     * code that hands C runtime objects across the boundary (a FILE*, a heap pointer one side
     * frees), and Skia and the JDK share none: they meet only through skiko's JNI calls and
     * JAWT's window handle. So copies of skiko's and Skia's libraries are made with the guard
     * and their static runtime defaults blanked out, and the image links the DLL runtime.
     */
    private fun windowsLink(graalvm: File, staticJdk: File, skikoArchive: File, skiaArchives: List<File>): Pair<List<String>, List<String>> {
        val onLoad = compileC(graalvm, resourceText("static_onload.c"), "static_onload")
        val stubs = compileC(graalvm, foreignStubs(skikoArchive), "foreign_stubs")
        val relinked = workDir.resolve("relinked").apply { mkdirs() }
        fun rewritten(library: File): File = relinked.resolve(library.name).also { copy ->
            copy.writeBytes(blankStaticRuntimeDirectives(library.readBytes()))
        }
        val linked = listOf(
            "awt:java_awt|sun_awt|sun_java2d|sun_print",
            "fontmanager:sun_font",
            "javajpeg:com_sun_imageio_plugins_jpeg|sun_awt_image_jpeg",
            "lcms:sun_java2d_cmm_lcms",
            "mlib_image:sun_awt_image_ImagingLib",
            "skiko:org_jetbrains_skia|org_jetbrains_skiko",
        )
        val link = mutableListOf<String>()
        fun linker(vararg options: String) = options.forEach { link += "-H:NativeLinkerOption=$it" }
        for (archive in Platform.WindowsX64.staticJdkArchives) {
            linker("/WHOLEARCHIVE:${staticJdk.resolve(archive)}")
        }
        linker("/WHOLEARCHIVE:${rewritten(skikoArchive)}")
        skiaArchives.sortedBy { if (it.name == "skia.lib") 0 else 1 }.forEach { linker(rewritten(it).absolutePath) }
        linker(onLoad.absolutePath, stubs.absolutePath)
        for (library in listOf(
            "user32", "gdi32", "ole32", "oleaut32", "imm32", "shell32", "advapi32", "comdlg32", "winspool",
            "uuid", "d3d12", "dxgi", "d3dcompiler", "dxguid", "dwrite", "usp10", "fontsub", "windowscodecs",
            "opengl32", "dwmapi", "uxtheme", "ws2_32", "bcrypt",
        )) {
            linker("$library.lib")
        }
        linker("/EXPORT:JNI_OnLoad_skiko")
        return linked to link
    }

    /**
     * The same bytes with every static C runtime directive blanked to spaces: the
     * `RuntimeLibrary` mismatch guard and the default libraries of the static runtime. Blanked
     * rather than removed, so no offset in the file moves.
     */
    private fun blankStaticRuntimeDirectives(bytes: ByteArray): ByteArray {
        val directives = listOf(
            "/FAILIFMISMATCH:\"RuntimeLibrary=MT_StaticRelease\"",
            "/DEFAULTLIB:\"LIBCMT\"",
            "/DEFAULTLIB:\"libcpmt\"",
            "/DEFAULTLIB:\"LIBCPMT\"",
            "/DEFAULTLIB:\"libcmt.lib\"",
            "/DEFAULTLIB:\"libcpmt.lib\"",
        ).map { it.toByteArray(Charsets.US_ASCII) }
        val out = bytes.copyOf()
        var index = 0
        while (index < out.size) {
            val match = directives.firstOrNull { directive ->
                index + directive.size <= out.size && directive.indices.all { out[index + it] == directive[it] }
            }
            if (match != null) {
                match.indices.forEach { out[index + it] = ' '.code.toByte() }
                index += match.size
            } else {
                index++
            }
        }
        return out
    }

    private enum class Platform(
        val staticJdkDirectory: String,
        val staticJdkArchives: List<String>,
        val skikoArchive: String,
        val archiveSuffix: String,
    ) {
        MacosArm64("lib/static/darwin-aarch64", listOf("libawt_lwawt.a", "libosxui.a", "libjawt.a"), "libskiko-static.a", ".a"),
        WindowsX64(
            "lib/static/windows-amd64",
            listOf("awt.lib", "fontmanager.lib", "freetype.lib", "javajpeg.lib", "lcms.lib", "mlib_image.lib", "jawt.lib"),
            "skiko-static.lib",
            ".lib",
        ),
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
        return if (currentOS == OS.Windows) {
            // cl.exe from the MSVC environment native-image itself needs on Windows.
            val obj = workDir.resolve("$name.obj")
            run(
                listOf("cl.exe", "/nologo", "/c", "/O2", "/MD", "/I${graalvm.resolve("include")}", "/I${graalvm.resolve("include/win32")}",
                    file.absolutePath, "/Fo${obj.absolutePath}"),
                workDir.resolve("$name.log"),
            )
            obj
        } else {
            val obj = workDir.resolve("$name.o")
            run(
                listOf("cc", "-c", "-O2", "-arch", "arm64", "-I${graalvm.resolve("include")}", "-I${graalvm.resolve("include/darwin")}",
                    file.absolutePath, "-o", obj.absolutePath),
                workDir.resolve("$name.log"),
            )
            obj
        }
    }

    /** The external symbols an archive defines, without any platform prefix. */
    private fun definedSymbols(archive: File): Set<String> {
        val listing = workDir.resolve("${archive.name}-symbols.txt")
        return if (currentOS == OS.Windows) {
            run(listOf("dumpbin.exe", "/nologo", "/linkermember:1", archive.absolutePath), listing, quiet = true)
            listing.readLines().mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size == 2 && parts[0].all { it.isLetterOrDigit() }) parts[1] else null
            }.toSet()
        } else {
            run(listOf("nm", "-g", archive.absolutePath), listing, quiet = true)
            listing.readLines().mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size == 3 && parts[1] == "T") parts[2].removePrefix("_") else null
            }.toSet()
        }
    }

    /**
     * Stops for the skiko JNI methods this platform does not implement: every native method
     * declared in skiko's classes, by its JNI name, less the ones the archive defines.
     */
    private fun foreignStubs(skikoArchive: File): String {
        val defined = definedSymbols(skikoArchive)
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

    /** One argument as native-image reads it from an argument file. */
    private fun quoteArgument(argument: String): String =
        if (argument.any { it.isWhitespace() || it == '"' || it == '\\' })
            "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        else argument

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
