/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.extended.msvc

import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import java.io.File
import javax.inject.Inject

/**
 * One Windows executable, made from a Kotlin/Native mingwX64 target and linked with MSVC.
 *
 * ```
 * kotlinMsvc {
 *     executable("hello") {
 *         entryPoint = "hello.main"
 *     }
 * }
 * ```
 */
open class MsvcExecutable @Inject constructor(private val executableName: String, objects: ObjectFactory) : Named {
    override fun getName(): String = executableName

    /** The function that starts the program, with its package: `hello.main`. A `main` with no package is just `main`. */
    val entryPoint: Property<String> = objects.property(String::class.java)

    /** Set when the entry function is `main(args: Array<String>)`. */
    val passArguments: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /** `Console` opens a console window and `Windows` does not. Console is the default. */
    val subsystem: Property<WindowsSubsystem> = objects.property(WindowsSubsystem::class.java).convention(WindowsSubsystem.Console)

    /** An application manifest to embed (DPI awareness, for example). Optional. */
    val manifest: RegularFileProperty = objects.fileProperty()

    /** A `.ico` file to embed as the program icon. Optional. */
    val icon: RegularFileProperty = objects.fileProperty()

    /** The Kotlin/Native target to link, `mingwX64` unless the target was renamed. */
    val target: Property<String> = objects.property(String::class.java).convention("mingwX64")

    /** The file name without `.exe`. Defaults to the executable's name. */
    val outputName: Property<String> = objects.property(String::class.java).convention(executableName)

    /** Prebuilt MSVC `.lib` files to link, such as a C++ library. Their runtime guards are blanked. */
    val libraries: ConfigurableFileCollection = objects.fileCollection()

    /** Extra Windows import libraries, for example `d3d12.lib`. */
    val systemLibraries: ListProperty<String> = objects.listProperty(String::class.java)

    /**
     * Link MSVC's C++ standard library into the program. Off by default: Kotlin/Native code does
     * not need it. Turn it on when the program links C++ built with MSVC.
     */
    val linkCppStandardLibrary: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /**
     * Rewrite the MinGW conventions so MSVC's linker accepts the objects. On by default and
     * needed for a working program. Turning it off is a diagnostic: the program then fails to
     * link, or hangs or crashes when Kotlin starts or throws.
     */
    val rewriteMingwObjects: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

    /** Require clang-cl to be installed. Off by default, on for builds that compile C++ with it. */
    val requireClangCl: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
}

open class KotlinMsvcExtension @Inject constructor(objects: ObjectFactory) {
    val executables: NamedDomainObjectContainer<MsvcExecutable> = objects.domainObjectContainer(MsvcExecutable::class.java)

    /**
     * The oldest MSVC toolset accepted, for example `14.44`. The default is the oldest one this
     * plugin has been linked and run with in CI; see [MsvcVerifiedVersions].
     */
    val minimumToolset: Property<String> = objects.property(String::class.java)

    fun executable(name: String, configure: Action<MsvcExecutable>) {
        configure.execute(executables.maybeCreate(name))
    }
}

abstract class CheckMsvcToolchain : DefaultTask() {
    @get:Input @get:Optional abstract val minimumToolset: Property<String>
    @get:Input abstract val requireClangCl: Property<Boolean>

    @TaskAction
    fun check() {
        val toolchain = locateOrExplain(minimumToolset.orNull, requireClangCl.get())
        logger.lifecycle("MSVC toolset ${toolchain.toolsetVersion}, Windows SDK ${toolchain.windowsSdkVersion}" +
            (toolchain.clangCl?.let { ", clang-cl" } ?: ""))
    }
}

internal fun locateOrExplain(minimum: String?, requireClangCl: Boolean): MsvcToolchain {
    val os = System.getProperty("os.name").orEmpty()
    if (!os.startsWith("Windows")) {
        throw GradleException(
            "A Windows executable is linked with Microsoft's linker, so it can only be built on Windows " +
                "(this machine runs $os). Kotlin/Native itself can still compile the mingwX64 library here."
        )
    }
    try {
        return MsvcDetector.locate(
            RealMsvcHost,
            MsvcRequirements(minimum ?: MsvcRequirements().minimumToolset, requireClangCl),
        )
    } catch (e: MsvcNotFoundException) {
        throw GradleException(e.message ?: "MSVC was not found", e)
    }
}

abstract class GenerateMsvcEntry : DefaultTask() {
    @get:Input abstract val entryPoint: Property<String>
    @get:Input abstract val passArguments: Property<Boolean>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val dir = outputDirectory.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "KotlinMsvcEntry.kt").writeText(MsvcEntryCode.kotlinSource(entryPoint.get(), passArguments.get()))
    }
}

abstract class LinkMsvcExecutable : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val staticLibrary: RegularFileProperty
    @get:Input abstract val executableName: Property<String>
    @get:Input abstract val subsystem: Property<WindowsSubsystem>
    @get:Input abstract val linkCppStandardLibrary: Property<Boolean>
    @get:Input abstract val requireClangCl: Property<Boolean>
    @get:Input abstract val rewriteMingwObjects: Property<Boolean>
    @get:Input @get:Optional abstract val minimumToolset: Property<String>
    @get:Input abstract val systemLibraries: ListProperty<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val libraries: ConfigurableFileCollection
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val manifest: RegularFileProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val icon: RegularFileProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty
    @get:Input abstract val workDirectory: Property<String>

    @TaskAction
    fun link() {
        val toolchain = locateOrExplain(minimumToolset.orNull, requireClangCl.get())
        val konanData = File(System.getenv("KONAN_DATA_DIR") ?: (System.getProperty("user.home") + "/.konan"))
        val gcc = KonanMingwRuntime.find(konanData) ?: throw GradleException(
            "Kotlin/Native's MinGW toolchain was not found under $konanData/dependencies. " +
                "Kotlin/Native downloads it the first time it compiles for mingwX64, so build the mingwX64 target once."
        )
        val spec = MsvcLinkSpec(
            name = executableName.get(),
            toolchain = toolchain,
            staticLibrary = staticLibrary.get().asFile,
            gccRuntimeDir = gcc,
            workDir = File(workDirectory.get()),
            outputFile = outputFile.get().asFile,
            subsystem = subsystem.get(),
            extraLibraries = libraries.files.toList(),
            linkCppStandardLibrary = linkCppStandardLibrary.get(),
            manifest = manifest.asFile.orNull,
            icon = icon.asFile.orNull,
            systemLibraries = systemLibraries.get(),
            rewriteMingwObjects = rewriteMingwObjects.get(),
        )
        try {
            MsvcLinkPipeline(spec).run()
        } catch (e: MsvcLinkException) {
            throw GradleException(e.message ?: "the MSVC link failed", e)
        }
    }
}

private fun String.capitalized() = replaceFirstChar { it.uppercase() }

/** Registers the `kotlinMsvc` extension and, for each executable, its generate, link and run tasks. */
fun Project.configureKotlinMsvc() {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        val extension = extensions.create("kotlinMsvc", KotlinMsvcExtension::class.java)
        afterEvaluate {
            if (extension.executables.isEmpty()) return@afterEvaluate
            val kotlin = extensions.getByType(KotlinMultiplatformExtension::class.java)
            for (exe in extension.executables) registerExecutable(kotlin, extension, exe)
        }
    }
}

private fun Project.registerExecutable(kotlin: KotlinMultiplatformExtension, extension: KotlinMsvcExtension, exe: MsvcExecutable) {
    val suffix = exe.name.capitalized()
    val target = kotlin.targets.withType(KotlinNativeTarget::class.java).findByName(exe.target.get())
        ?: throw GradleException(
            "kotlinMsvc executable '${exe.name}' needs a Kotlin/Native target named '${exe.target.get()}', " +
                "and the project has none. Add `mingwX64()` to the kotlin { } block."
        )
    if (!exe.entryPoint.isPresent) {
        throw GradleException("kotlinMsvc executable '${exe.name}' has no entryPoint. Set it to your main function, for example entryPoint = \"hello.main\".")
    }

    val check = tasks.register("checkMsvcToolchain$suffix", CheckMsvcToolchain::class.java) {
        it.group = "msvc"
        it.description = "Finds the MSVC build tools and stops with install instructions when they are missing."
        it.minimumToolset.set(extension.minimumToolset)
        it.requireClangCl.set(exe.requireClangCl)
    }
    val generate = tasks.register("generateMsvcEntry$suffix", GenerateMsvcEntry::class.java) {
        it.entryPoint.set(exe.entryPoint)
        it.passArguments.set(exe.passArguments)
        it.outputDirectory.set(layout.buildDirectory.dir("generated/kotlinMsvc/${exe.name}"))
    }
    target.compilations.getByName("main").defaultSourceSet.kotlin.srcDir(generate.flatMap { it.outputDirectory })

    target.binaries.staticLib(exe.name, listOf(NativeBuildType.RELEASE)) {
        baseName = exe.name
    }
    val library = target.binaries.getStaticLib(exe.name, NativeBuildType.RELEASE)
    library.linkTaskProvider.configure { it.dependsOn(check) }

    val link = tasks.register("linkMsvcExecutable$suffix", LinkMsvcExecutable::class.java) {
        it.group = "msvc"
        it.description = "Links ${exe.outputName.get()}.exe with Microsoft's linker."
        it.dependsOn(library.linkTaskProvider)
        it.staticLibrary.set(layout.file(library.linkTaskProvider.flatMap { task -> task.outputFile }))
        it.executableName.set(exe.outputName)
        it.subsystem.set(exe.subsystem)
        it.linkCppStandardLibrary.set(exe.linkCppStandardLibrary)
        it.requireClangCl.set(exe.requireClangCl)
        it.rewriteMingwObjects.set(exe.rewriteMingwObjects)
        it.minimumToolset.set(extension.minimumToolset)
        it.systemLibraries.set(exe.systemLibraries)
        it.libraries.from(exe.libraries)
        it.manifest.set(exe.manifest)
        it.icon.set(exe.icon)
        it.outputFile.set(layout.buildDirectory.file("bin/msvc/${exe.name}/${exe.outputName.get()}.exe"))
        it.workDirectory.set(layout.buildDirectory.dir("msvc/${exe.name}").map { d -> d.asFile.path })
    }
    tasks.register("runMsvcExecutable$suffix", Exec::class.java) {
        it.group = "msvc"
        it.description = "Runs ${exe.outputName.get()}.exe."
        it.dependsOn(link)
        it.executable = layout.buildDirectory.file("bin/msvc/${exe.name}/${exe.outputName.get()}.exe").get().asFile.path
    }
}
