/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.tasks

import org.jetbrains.compose.desktop.application.extended.WindowsAppManifest
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.compose.desktop.application.dsl.ApplicationWindowing
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
@DisableCachingByDefault(
    because = "The image depends on the GraalVM at graalvmHome and the host's C toolchain, neither of which is an input"
)
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
    abstract val windowing: Property<ApplicationWindowing>

    /** A compose-multiplatform-core-extended checkout, the source of the window layers' C code. */
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val windowSourcesDirectory: DirectoryProperty

    @get:Input
    abstract val buildArgs: ListProperty<String>

    /** Windows only: an application manifest to embed instead of the plugin's own. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val windowsManifest: RegularFileProperty

    @get:OutputDirectory
    abstract val destinationDir: DirectoryProperty

    @get:Internal
    val workDir: File get() = temporaryDir

    @TaskAction
    fun build() {
        val platform = when {
            currentOS == OS.MacOS && currentArch == Arch.Arm64 -> Platform.MacosArm64
            currentOS == OS.Windows && currentArch == Arch.X64 -> Platform.WindowsX64
            currentOS == OS.Linux && currentArch == Arch.X64 -> Platform.LinuxX64
            else -> throw GradleException(
                "packageNativeImage builds macOS arm64, Linux x64 and Windows x64 so far (this is $currentOS $currentArch)."
            )
        }
        val graalvm = File(graalvmHome.get())
        val nativeImage = graalvm.resolve(if (platform == Platform.WindowsX64) "bin/native-image.cmd" else "bin/native-image")
        if (!nativeImage.isFile) {
            throw GradleException("$nativeImage does not exist. graalvmHome has to name a GraalVM with native-image.")
        }
        val staticJdk = graalvm.resolve(platform.staticJdkDirectory)
        val awt = windowing.get() == ApplicationWindowing.Awt
        for (archive in if (awt) platform.staticJdkArchives else emptyList()) {
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
        if (!awt) checkSkikoNeedsNoJawt(skikoArchive)
        val supportJar = buildSupportJar(graalvm)

        val output = destinationDir.get().asFile
        output.deleteRecursively()
        output.mkdirs()

        val classpath = (runtimeClasspath.files + supportJar).joinToString(File.pathSeparator)
        val (linked, link) = when (platform) {
            Platform.MacosArm64 -> macosLink(graalvm, staticJdk, skikoArchive, skiaArchives, awt)
            Platform.WindowsX64 -> windowsLink(graalvm, staticJdk, skikoArchive, skiaArchives, awt)
            Platform.LinuxX64 -> linuxLink(graalvm, staticJdk, skikoArchive, skiaArchives, awt)
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
        if (!awt) {
            // A CEntryPointLiteral is resolved while the image is built, so the classes that
            // hold the upcall tables have to be initialized then.
            val upcalls = when (platform) {
                Platform.MacosArm64 -> listOf("AppKitUpcalls", "AppKitUpcallSlots").map { "org.thisisthepy.compose.window.graalvm.macos.$it" }
                Platform.LinuxX64 -> listOf("org.thisisthepy.compose.window.graalvm.linux.X11Upcalls")
                Platform.WindowsX64 -> emptyList()
            }
            if (upcalls.isNotEmpty()) args += "--initialize-at-build-time=${upcalls.joinToString(",")}"
        }
        args += buildArgs.get()
        args += listOf("-o", output.resolve(imageName.get()).absolutePath, mainClass.get())

        logger.lifecycle("native-image: building ${imageName.get()}, which takes a few minutes")
        // Through an argument file: a classpath alone runs past the 8191 characters Windows
        // allows a command line through native-image's .cmd launcher.
        val argFile = workDir.resolve("native-image.args")
        argFile.writeText(args.drop(1).joinToString("\n") { quoteArgument(it) })
        logger.info("native-image arguments:\n" + args.drop(1).joinToString("\n"))
        run(listOf(args.first(), "@${argFile.absolutePath}"), workDir.resolve("native-image.log"))
        if (platform == Platform.LinuxX64) removeUnneededLinuxLibraries(output)
        logger.lifecycle("The executable is written to ${output.resolve(imageName.get())}")
    }

    private fun macosLink(graalvm: File, staticJdk: File, skikoArchive: File, skiaArchives: List<File>, awt: Boolean): Pair<List<String>, List<String>> {
        val onLoad = compileC(graalvm, resourceText("static_onload.c"), "static_onload")
        val stubs = compileC(graalvm, foreignStubs(skikoArchive), "foreign_stubs")
        val skikoLibrary = "skiko:org_jetbrains_skia|org_jetbrains_skiko"
        val linked = if (awt) listOf(
            "awt_lwawt:sun_lwawt|sun_java2d_metal|sun_java2d_opengl|sun_font|sun_awt",
            "osxui:com_apple_laf",
            skikoLibrary,
        ) else listOf(skikoLibrary)
        val link = mutableListOf<String>()
        fun linker(vararg options: String) = options.forEach { link += "-H:NativeLinkerOption=$it" }
        for (archive in if (awt) Platform.MacosArm64.staticJdkArchives else emptyList()) {
            linker("-Wl,-force_load,${staticJdk.resolve(archive)}")
        }
        linker("-Wl,-force_load,$skikoArchive")
        // libskia first, so Skia's core objects come from it rather than a module archive.
        skiaArchives.sortedBy { if (it.name == "libskia.a") 0 else 1 }.forEach { linker(it.absolutePath) }
        linker(onLoad.absolutePath, stubs.absolutePath)
        for (framework in listOf("Metal", "MetalKit", "IOKit")) {
            linker("-framework", framework)
        }
        if (!awt) {
            windowLayerObjects("appkit_window.m", "appkit_resize.h").forEach { linker(it.absolutePath) }
            for (framework in listOf("AppKit", "Carbon", "QuartzCore")) {
                linker("-framework", framework)
            }
            // Skia and skiko's bindings are C++; with AWT the JDK's archives bring the library in.
            linker("-lc++")
        }
        for (library in if (awt) listOf("awt_lwawt", "osxui", "skiko") else listOf("skiko")) {
            linker("-Wl,-exported_symbol,_JNI_OnLoad_$library")
        }
        return linked to link
    }

    /**
     * Linux: GraalVM copies AWT's shared libraries beside the image. Registered as built in
     * instead, with the packages whose native methods each holds, they are linked from NIK's
     * static archives by GraalVM itself, as ordinary archives: every native method the image
     * can reach and every `JNI_OnLoad_<name>` of theirs is referred to by symbol, so nothing
     * is forced. JAWT, which nothing registers, is linked with Skia, and the AWT archives are
     * named again there because JAWT reaches into them after their first scan.
     *
     * GraalVM still writes the shared libraries it would have used beside the executable
     * (the `java` and `jvm` shims, `awt_headless`, `freetype`); the executable needs none of
     * them, and [removeUnneededLinuxLibraries] takes them away.
     */
    private fun linuxLink(graalvm: File, staticJdk: File, skikoArchive: File, skiaArchives: List<File>, awt: Boolean): Pair<List<String>, List<String>> {
        val onLoad = compileC(graalvm, resourceText("static_onload.c"), "static_onload")
        // sun.font declares a Windows-only native, and the prefix that makes sun.font built in
        // makes the image refer to it.
        val stubs = compileC(graalvm, foreignStubs(skikoArchive, listOf("Java_sun_font_FileFontStrike__1getGlyphImageFromWindows")), "foreign_stubs")
        val skikoLibrary = "skiko:org_jetbrains_skia|org_jetbrains_skiko"
        val linked = if (awt) listOf(
            "awt:java_awt|sun_awt|sun_java2d|sun_print",
            "awt_xawt",
            "fontmanager:sun_font",
            "javajpeg:com_sun_imageio_plugins_jpeg|sun_awt_image_jpeg",
            "lcms:sun_java2d_cmm_lcms",
            "mlib_image:sun_awt_image_ImagingLib",
            skikoLibrary,
        ) else listOf(skikoLibrary)
        val link = mutableListOf<String>()
        fun linker(vararg options: String) = options.forEach { link += "-H:NativeLinkerOption=$it" }
        linker("-Wl,--whole-archive", skikoArchive.absolutePath, "-Wl,--no-whole-archive")
        // Skia's archives refer to one another in both directions. FreeType comes after them,
        // so it fills only what neither AWT's font code nor Skia defines.
        linker("-Wl,--start-group")
        if (awt) linker(staticJdk.resolve("libjawt.a").absolutePath)
        skiaArchives.sortedBy { if (it.name == "libskia.a") 0 else 1 }.forEach { linker(it.absolutePath) }
        for (archive in if (awt) listOf("libawt_xawt.a", "libawt.a", "libfreetype.a") else emptyList()) {
            linker(staticJdk.resolve(archive).absolutePath)
        }
        linker("-Wl,--end-group")
        linker(onLoad.absolutePath, stubs.absolutePath)
        if (!awt) windowLayerObjects("x11_window.c").forEach { linker(it.absolutePath) }
        for (library in listOf("stdc++", "GL", "X11", "Xext", "Xi", "Xrender", "Xtst", "fontconfig", "dl", "m", "pthread")) {
            linker("-l$library")
        }
        return linked to link
    }

    private fun removeUnneededLinuxLibraries(output: File) {
        for (library in listOf("libjava.so", "libjvm.so", "libawt_headless.so", "libfreetype.so")) {
            val file = output.resolve(library)
            if (file.delete()) logger.info("native-image: removed $file, which the executable does not load")
        }
    }

    /**
     * Windows: the JDK's static libraries are built against the C runtime DLL (/MD), and
     * JetBrains' Skia against the static one (/MT). The MSVC linker refuses to mix them on a
     * guard each C++ object carries, `/FAILIFMISMATCH:"RuntimeLibrary=..."`. The guard is there
     * for code that hands C runtime objects across the boundary (a FILE*, a heap pointer one
     * side frees), and Skia and the JDK share none: they meet only through skiko's JNI calls
     * and JAWT's window handle. So every C++ library is linked from a copy with the guard
     * blanked out, and Skia's copies also lose their static runtime defaults.
     *
     * The runtime that results: the UCRT from the DLL, which is part of Windows 10 and later,
     * and vcruntime and the C++ standard library linked in, because a clean Windows has no
     * VCRUNTIME140.dll or MSVCP140.dll. The JDK's objects call the C++ library through
     * `__imp_` pointers, and the linker binds those to the linked-in definitions.
     */
    private fun windowsLink(graalvm: File, staticJdk: File, skikoArchive: File, skiaArchives: List<File>, awt: Boolean): Pair<List<String>, List<String>> {
        // TODO(windows window layer): compile win32_window.c and win32_dcomp.cpp from the core-extended
        // windows-shared module with cl.exe (/MD) and link dcomp.lib, d3d11.lib, dxgi.lib, user32.lib
        // and gdi32.lib. Until the Windows worker adds that, fail instead of producing a windowless image.
        if (!awt) throw GradleException("AWT-free images are not built on Windows yet: the win32 window layer is not linked.")
        val onLoad = compileC(graalvm, resourceText("static_onload.c"), "static_onload")
        val stubs = compileC(graalvm, foreignStubs(skikoArchive), "foreign_stubs")
        val relinked = workDir.resolve("relinked").apply { mkdirs() }
        // The JDK's and Skia's libraries in separate directories, as both could name one freetype.lib.
        fun rewritten(library: File, into: String = "skia"): File = relinked.resolve("$into/${library.name}").also { copy ->
            copy.parentFile.mkdirs()
            copy.writeBytes(blankRuntimeDirectives(library.readBytes()))
        }
        val skikoLibrary = "skiko:org_jetbrains_skia|org_jetbrains_skiko"
        val linked = if (awt) listOf(
            "awt:java_awt|sun_awt|sun_java2d|sun_print",
            "fontmanager:sun_font",
            "javajpeg:com_sun_imageio_plugins_jpeg|sun_awt_image_jpeg",
            "lcms:sun_java2d_cmm_lcms",
            "mlib_image:sun_awt_image_ImagingLib",
            skikoLibrary,
        ) else listOf(skikoLibrary)
        val link = mutableListOf<String>()
        fun linker(vararg options: String) = options.forEach { link += "-H:NativeLinkerOption=$it" }
        for (archive in if (awt) Platform.WindowsX64.staticJdkArchives else emptyList()) {
            linker("/WHOLEARCHIVE:${rewritten(staticJdk.resolve(archive), into = "jdk")}")
        }
        linker("/WHOLEARCHIVE:${rewritten(skikoArchive)}")
        skiaArchives.sortedBy { if (it.name == "skia.lib") 0 else 1 }.forEach { linker(rewritten(it).absolutePath) }
        linker(onLoad.absolutePath, stubs.absolutePath)
        for (library in listOf(
            "user32", "gdi32", "ole32", "oleaut32", "imm32", "shell32", "advapi32", "comdlg32", "winspool",
            "uuid", "d3d12", "dxgi", "d3dcompiler", "dxguid", "dwrite", "usp10", "fontsub", "windowscodecs",
            "dwmapi", "uxtheme", "ws2_32", "bcrypt", "shlwapi", "winmm", "comctl32", "propsys",
        )) {
            linker("$library.lib")
        }
        linker("/EXPORT:JNI_OnLoad_skiko")
        // vcruntime and the C++ library linked in rather than imported; see above. GraalVM
        // adds libraries of its own built against the DLL runtime (sunmscapi.lib) that cannot
        // be rewritten here, so it is the C++ library's copy that loses its guard instead.
        linker("/NODEFAULTLIB:vcruntime.lib", "libvcruntime.lib", "/NODEFAULTLIB:msvcprt.lib", "/NODEFAULTLIB:libcpmt.lib")
        val cxxRuntime = rewritten(msvcLibrary("libcpmt.lib"), into = "msvc")
        linker(cxxRuntime.absolutePath)
        checkRuntimeHelpers(skiaArchives + skikoArchive, listOf(cxxRuntime, msvcLibrary("libvcruntime.lib")))
        // Without a manifest a process is DPI unaware, and Windows stretches its 96 DPI
        // drawing to a scaled display, which blurs the text. This is what java.exe declares.
        val manifest = WindowsAppManifest.resolve(windowsManifest.orNull?.asFile, workDir)
        linker("/MANIFEST:EMBED", "/MANIFESTINPUT:${manifest.absolutePath}")
        // No opengl32.lib: skiko defines the few OpenGL entry points it calls itself and
        // resolves them from opengl32.dll at run time, so the import library defines them twice.
        return linked to link
    }

    /** A library from the MSVC installation, found on LIB the way the linker finds it. */
    private fun msvcLibrary(name: String): File =
        (System.getenv("LIB") ?: "").split(';').filter { it.isNotBlank() }
            .map { File(it, name) }.firstOrNull { it.isFile }
            ?: error("$name is not in any directory on LIB; run from a Developer prompt or after vcvars64.bat")

    /**
     * Stops before the link when the MSVC C and C++ runtimes are older than the toolset Skia
     * was built with. Skia's archives call the runtime's vectorised algorithm helpers, and a
     * helper added after this toolset is simply not there: the link then fails on a bare
     * `__std_*` name that says nothing about what to do. The one seen was
     * `__std_find_first_of_trivial_pos_1`, in 14.51 but not in 14.43.
     *
     * Both runtime archives are read, not only the C++ one: the type_info helpers Skia also
     * calls live in libvcruntime, so libcpmt alone reports them missing on a toolset that links.
     */
    private fun checkRuntimeHelpers(referencing: List<File>, runtimes: List<File>) {
        val referenced = referencing.flatMapTo(mutableSetOf()) { undefinedRuntimeHelpers(it) }
        val defined = runtimes.flatMapTo(mutableSetOf()) { definedSymbols(it) }
        val missing = missingRuntimeHelpers(referenced, defined)
        if (missing.isNotEmpty()) throw GradleException(runtimeHelpersMessage(missing, runtimes))
    }

    /** The `__std_*` runtime helpers an archive calls without defining them itself. */
    private fun undefinedRuntimeHelpers(archive: File): Set<String> {
        val listing = workDir.resolve("${archive.name}-undefined.txt")
        run(listOf("dumpbin.exe", "/nologo", "/symbols", archive.absolutePath), listing, quiet = true)
        return listing.useLines { lines ->
            lines.filter { "UNDEF" in it }
                .flatMap { line -> RUNTIME_HELPER.findAll(line).map { it.value } }
                .toSet()
        }
    }

    /**
     * The same bytes with the C runtime directives blanked to spaces: the `RuntimeLibrary`
     * mismatch guard, either value, and the default libraries of the static runtime. Blanked
     * rather than removed, so no offset in the file moves.
     */
    private fun blankRuntimeDirectives(bytes: ByteArray): ByteArray {
        val directives = listOf(
            "/FAILIFMISMATCH:\"RuntimeLibrary=MT_StaticRelease\"",
            "/FAILIFMISMATCH:\"RuntimeLibrary=MD_DynamicRelease\"",
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
        LinuxX64(
            "lib/static/linux-amd64/glibc",
            listOf("libawt.a", "libawt_xawt.a", "libfontmanager.a", "libfreetype.a", "libjavajpeg.a", "liblcms.a", "libmlib_image.a", "libjawt.a"),
            "libskiko-static.a",
            ".a",
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

    /**
     * Compiles the core-extended window layer's C or Objective-C source for this platform and
     * returns its object files. The window Kotlin wrappers call these functions through
     * `@CFunction`, which GraalVM resolves by name at link time, so linking the objects is the
     * whole registration. The source is found by file name under `extended/window` in the
     * checkout named by [windowSourcesDirectory], and the directory holding it is on the
     * include path so its headers (`appkit_resize.h`) are found.
     */
    private fun windowLayerObjects(source: String, vararg headers: String): List<File> {
        val checkout = windowSourcesDirectory.orNull?.asFile ?: throw GradleException(
            "nativeImage.windowSourcesDirectory is not set. An AWT-free image links the window layer's C sources, " +
                "which come from a compose-multiplatform-core-extended checkout."
        )
        val root = checkout.resolve("extended/window")
        val file = findWindowSource(root, source)
            ?: throw GradleException("$source was not found under $root. windowSourcesDirectory has to be a core-extended checkout that has the window modules.")
        for (header in headers) {
            if (!file.resolveSibling(header).isFile && findWindowSource(root, header) == null) {
                throw GradleException("$header was not found under $root, and $source includes it.")
            }
        }
        val includes = (listOf(file.parentFile) + headers.mapNotNull { h -> findWindowSource(root, h)?.parentFile })
            .distinct().map { "-I${it.absolutePath}" }
        val name = file.nameWithoutExtension
        val obj = workDir.resolve("window_$name.o")
        val command = when (currentOS) {
            OS.MacOS -> listOf("cc", "-c", "-O2", "-fobjc-arc", "-arch", "arm64") + includes + listOf(file.absolutePath, "-o", obj.absolutePath)
            OS.Linux -> listOf("cc", "-c", "-O2", "-fPIC") + includes + listOf(file.absolutePath, "-o", obj.absolutePath)
            else -> throw GradleException("The window layer is not compiled on $currentOS.")
        }
        run(command, workDir.resolve("window_$name.log"))
        return listOf(obj)
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
        } else if (currentOS == OS.Linux) {
            val obj = workDir.resolve("$name.o")
            run(
                listOf("cc", "-c", "-O2", "-fPIC", "-I${graalvm.resolve("include")}", "-I${graalvm.resolve("include/linux")}",
                    file.absolutePath, "-o", obj.absolutePath),
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

    /**
     * An AWT-free image has no JAWT to link, so a skiko archive that still calls `JAWT_GetAWT`
     * fails the link on a symbol that says nothing about the cause. Say it before the build.
     */
    private fun checkSkikoNeedsNoJawt(skikoArchive: File) {
        val listing = workDir.resolve("${skikoArchive.name}-undefined-jawt.txt")
        if (currentOS == OS.Windows) {
            run(listOf("dumpbin.exe", "/nologo", "/symbols", skikoArchive.absolutePath), listing, quiet = true)
        } else {
            run(listOf("nm", "-u", skikoArchive.absolutePath), listing, quiet = true)
        }
        if (listing.useLines { lines -> lines.any { "JAWT_GetAWT" in it && (currentOS != OS.Windows || "UNDEF" in it) } }) {
            throw GradleException(
                "$skikoArchive was built for an AWT window: it refers to JAWT_GetAWT, which an AWT-free application does not have. " +
                    "Build the archive with `build-skiko-static-jvm.sh --no-jawt <work-dir>` from compose-multiplatform-core-extended, " +
                    "or set windowing = ApplicationWindowing.Awt to keep AWT."
            )
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
                // Mach-O prefixes C names with an underscore; ELF does not.
                if (parts.size == 3 && parts[1] == "T") {
                    if (currentOS == OS.MacOS) parts[2].removePrefix("_") else parts[2]
                } else null
            }.toSet()
        }
    }

    /**
     * Stops for the skiko JNI methods this platform does not implement: every native method
     * declared in skiko's classes, by its JNI name, less the ones the archive defines, and
     * any [extra] names the caller knows the image refers to without defining.
     */
    private fun foreignStubs(skikoArchive: File, extra: List<String> = emptyList()): String {
        val defined = definedSymbols(skikoArchive)
        val declared = declaredSkikoNatives() + extra
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
        /** The MSVC runtime's vectorised algorithm and type_info helpers, by name. */
        val RUNTIME_HELPER = Regex("__std_[A-Za-z0-9_]+")

        /** Which of the helpers the archives call no runtime being linked defines. */
        fun missingRuntimeHelpers(referenced: Set<String>, defined: Set<String>): List<String> =
            referenced.filter { it !in defined }.sorted()

        /** What to tell someone whose toolset is too old, naming it and what is missing. */
        fun runtimeHelpersMessage(missing: List<String>, runtimes: List<File>): String =
            buildString {
                appendLine("The MSVC toolset is older than the one Skia was built with.")
                appendLine("Update the Visual Studio C++ toolset, then build again.")
                appendLine()
                appendLine("Runtime libraries read:")
                runtimes.forEach { appendLine("    " + it.absolutePath) }
                appendLine()
                appendLine("Helpers Skia calls that none of them define:")
                missing.forEach { appendLine("    " + it) }
            }

        /** The JNI short name mangling: '/' is '_', and '_', ';', '[' and non-ASCII are escaped. */
        /** The file called [name] anywhere under [root], or null. */
        fun findWindowSource(root: File, name: String): File? =
            root.takeIf { it.isDirectory }?.walkTopDown()?.firstOrNull { it.isFile && it.name == name }

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
