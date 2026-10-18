/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.thisisthepy.kotlin.gradle.nativedesktop

import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
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
import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.konan.target.Family
import org.thisisthepy.kotlin.gradle.nativedesktop.msvc.KonanMingwRuntime
import org.thisisthepy.kotlin.gradle.nativedesktop.msvc.MainSignature
import org.thisisthepy.kotlin.gradle.nativedesktop.msvc.MsvcDetector
import org.thisisthepy.kotlin.gradle.nativedesktop.msvc.MsvcEntryCode
import org.thisisthepy.kotlin.gradle.nativedesktop.msvc.MsvcLinkBundle
import org.thisisthepy.kotlin.gradle.nativedesktop.msvc.MsvcLinkException
import org.thisisthepy.kotlin.gradle.nativedesktop.msvc.MsvcLinkPipeline
import org.thisisthepy.kotlin.gradle.nativedesktop.msvc.MsvcLinkSpec
import org.thisisthepy.kotlin.gradle.nativedesktop.msvc.MsvcNotFoundException
import org.thisisthepy.kotlin.gradle.nativedesktop.msvc.MsvcRequirements
import org.thisisthepy.kotlin.gradle.nativedesktop.msvc.MsvcToolchain
import org.thisisthepy.kotlin.gradle.nativedesktop.msvc.RealMsvcHost
import org.thisisthepy.kotlin.gradle.nativedesktop.msvc.WindowsSubsystem
import java.io.File
import javax.inject.Inject

/**
 * Kotlin/Native desktop programs, with or without Compose.
 *
 * On Windows every `mingwX64` executable is linked by MSVC: the program is built as a
 * Kotlin/Native static library and linked with Microsoft's linker into the file Kotlin's link
 * task names, in place of Kotlin/Native's MinGW link. The tasks keep Kotlin's own names
 * (`linkReleaseExecutableMingwX64`, `runReleaseExecutableMingwX64`), and the settings live in
 * the `kotlin` block:
 *
 * ```
 * kotlin {
 *     mingwX64 { binaries { executable { entryPoint = "hello.main" } } }
 *     msvc { subsystem = WindowsSubsystem.Windows }
 * }
 * ```
 */
class NativeDesktopPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        var applied = false
        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            applied = true
            val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
            val settings = (kotlin as ExtensionAware).extensions.create("msvc", MsvcSettings::class.java)
            project.afterEvaluate { configureMingwExecutables(project, kotlin, settings) }
        }
        project.afterEvaluate {
            if (!applied) {
                throw GradleException(
                    "org.thisisthepy.kotlin.native.desktop works on Kotlin Multiplatform projects. " +
                        "Add `kotlin(\"multiplatform\")` to the plugins block before it."
                )
            }
        }
    }
}

/** Settings for the MSVC link of every `mingwX64` executable, in `kotlin { msvc { } }`. */
open class MsvcSettings @Inject constructor(objects: ObjectFactory) {
    /**
     * The oldest MSVC toolset accepted, for example `14.44`. The default is the oldest one this
     * plugin has been linked and run with in CI.
     */
    val minimumToolset: Property<String> = objects.property(String::class.java)

    /** `Console` opens a console window and `Windows` does not. Console is the default. */
    val subsystem: Property<WindowsSubsystem> = objects.property(WindowsSubsystem::class.java).convention(WindowsSubsystem.Console)

    /** An application manifest to embed (DPI awareness, for example). Optional. */
    val manifest: RegularFileProperty = objects.fileProperty()

    /** A `.ico` file to embed as the program icon. Optional. */
    val icon: RegularFileProperty = objects.fileProperty()

    /** Prebuilt MSVC `.lib` files to link, such as a C++ library. Their runtime guards are blanked. */
    val libraries: ConfigurableFileCollection = objects.fileCollection()

    /**
     * Libraries whose MSVC half is linked when the program depends on them, as `group:module`.
     * Each publishes it beside its klib as a zip with the classifier `msvc` ([MsvcLinkBundle]).
     * The default names skiko for mingwX64 as this fork publishes it, which carries skiko's C++
     * half and Skia.
     */
    val linkBundles: ListProperty<String> = objects.listProperty(String::class.java)
        .convention(listOf("org.thisisthepy.compose.skiko:skiko-mingwx64"))

    /** Extra Windows import libraries, for example `d3d12.lib`. */
    val systemLibraries: ListProperty<String> = objects.listProperty(String::class.java)

    /**
     * Link MSVC's C++ standard library into the program. Off by default: Kotlin/Native code does
     * not need it. Turn it on when the program links C++ built with MSVC.
     */
    val linkCppStandardLibrary: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /** Require clang-cl to be installed. Off by default, on for builds that compile C++ with it. */
    val requireClangCl: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /**
     * Rewrite the MinGW conventions so MSVC's linker accepts the objects. On by default and
     * needed for a working program. Turning it off is a diagnostic: the program then fails to
     * link, or hangs or crashes when Kotlin starts or throws.
     */
    val rewriteMingwObjects: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

    fun subsystem(value: WindowsSubsystem) = subsystem.set(value)
}

abstract class CheckMsvcToolchain : DefaultTask() {
    @get:Input @get:Optional abstract val minimumToolset: Property<String>
    @get:Input abstract val requireClangCl: Property<Boolean>

    @TaskAction
    fun check() {
        val toolchain = locateOrExplain(minimumToolset.orNull, requireClangCl.get())
        logger.lifecycle(
            "MSVC toolset ${toolchain.toolsetVersion}, Windows SDK ${toolchain.windowsSdkVersion}" +
                (if (toolchain.clangCl != null) ", clang-cl" else "")
        )
    }
}

internal fun locateOrExplain(minimum: String?, requireClangCl: Boolean): MsvcToolchain {
    val os = System.getProperty("os.name").orEmpty()
    if (!os.startsWith("Windows")) {
        throw GradleException(
            "A Windows executable is linked with Microsoft's linker, so it can only be built on Windows " +
                "(this machine runs $os)."
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
    @get:Input abstract val symbol: Property<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val sources: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val dir = outputDirectory.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        val files = sources.files.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        val passArguments = MainSignature.takesArguments(entryPoint.get(), files.map { it.readText() })
        File(dir, "KotlinMsvcEntry_${symbol.get()}.kt")
            .writeText(MsvcEntryCode.kotlinSource(entryPoint.get(), passArguments, symbol.get()))
    }
}

private fun String.capitalized() = replaceFirstChar { it.uppercase() }

private fun String.identifier() = map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

private fun configureMingwExecutables(project: Project, kotlin: KotlinMultiplatformExtension, settings: MsvcSettings) {
    val targets = kotlin.targets.withType(KotlinNativeTarget::class.java).filter { it.konanTarget.family == Family.MINGW }
    for (target in targets) {
        for (executable in target.binaries.withType(Executable::class.java).toList()) {
            relinkWithMsvc(project, settings, target, executable)
        }
    }
}

private fun relinkWithMsvc(project: Project, settings: MsvcSettings, target: KotlinNativeTarget, executable: Executable) {
    val name = executable.name.capitalized() + target.name.capitalized()
    val symbol = "kotlin_msvc_entry_" + (target.name + executable.name).identifier()

    val check = project.tasks.register("checkMsvcToolchain$name", CheckMsvcToolchain::class.java) {
        it.group = "msvc"
        it.description = "Finds the MSVC build tools and stops with install instructions when they are missing."
        it.minimumToolset.set(settings.minimumToolset)
        it.requireClangCl.set(settings.requireClangCl)
    }

    val main = target.compilations.getByName("main")
    // The program's own sources: not the entries generated for its other executables, which are
    // added to the same compilation and are another task's output. Read when the task runs: the
    // Kotlin Gradle plugin adds commonMain to the compilation after this project is evaluated.
    val generatedEntries = project.layout.buildDirectory.dir("generated/msvcEntry").get().asFile
    val sourceDirs = project.provider {
        main.allKotlinSourceSets.flatMap { it.kotlin.srcDirs }
            .filterNot { it.toPath().startsWith(generatedEntries.toPath()) }
    }
    val generate = project.tasks.register("generateMsvcEntry$name", GenerateMsvcEntry::class.java) {
        it.entryPoint.set(project.provider { executable.entryPoint ?: "main" })
        it.symbol.set(symbol)
        it.sources.from(sourceDirs)
        it.outputDirectory.set(project.layout.buildDirectory.dir("generated/msvcEntry/$name"))
    }
    main.defaultSourceSet.kotlin.srcDir(generate.flatMap { it.outputDirectory })

    val prefix = "msvc" + executable.name.capitalized()
    target.binaries.staticLib(prefix, listOf(executable.buildType)) {
        baseName = executable.baseName
    }
    val library = target.binaries.getStaticLib(prefix, executable.buildType)
    library.linkTaskProvider.configure { it.dependsOn(check) }

    // The MSVC halves of the libraries the program depends on (MsvcLinkBundle): for each module
    // in linkBundles that the compilation resolves, its msvc zip at the same version. The
    // compilation's attributes pick the mingwX64 variant, whose artifact the classifier replaces.
    val compileDependencies = project.configurations.getByName(main.compileDependencyConfigurationName)
    val bundles = project.configurations.create("msvcLinkBundles$name") { configuration ->
        configuration.isCanBeConsumed = false
        configuration.isCanBeResolved = true
        configuration.isTransitive = false
        configuration.description = "The msvc zips of the libraries ${executable.name} for ${target.name} depends on."
        configuration.attributes { attributes ->
            for (key in compileDependencies.attributes.keySet()) {
                @Suppress("UNCHECKED_CAST")
                val attribute = key as Attribute<Any>
                compileDependencies.attributes.getAttribute(attribute)?.let { attributes.attribute(attribute, it) }
            }
        }
        configuration.withDependencies { dependencies ->
            val wanted = settings.linkBundles.get().toSet()
            compileDependencies.incoming.resolutionResult.allComponents
                .mapNotNull { it.id as? ModuleComponentIdentifier }
                .filter { "${it.group}:${it.module}" in wanted }
                .forEach {
                    dependencies.add(
                        project.dependencies.create("${it.group}:${it.module}:${it.version}:${MsvcLinkBundle.CLASSIFIER}@zip")
                    )
                }
        }
    }

    val workDirectory = project.layout.buildDirectory.dir("msvc/$name").get().asFile
    executable.linkTaskProvider.configure { task ->
        task.dependsOn(check, library.linkTaskProvider)
        // Kotlin/Native's MinGW link of the same program is not run: its output is replaced by
        // the MSVC link, and with a library whose C++ half is MSVC's (skiko's) it cannot link.
        task.actions.clear()
        // The relink is part of this task, so what it depends on decides whether the task reruns.
        task.inputs.property("msvc.rewriteMingwObjects", settings.rewriteMingwObjects)
        task.inputs.property("msvc.subsystem", settings.subsystem.map { it.name })
        task.inputs.property("msvc.linkCppStandardLibrary", settings.linkCppStandardLibrary)
        task.inputs.property("msvc.systemLibraries", settings.systemLibraries)
        task.inputs.property("msvc.minimumToolset", settings.minimumToolset).optional(true)
        task.inputs.files(settings.libraries).withPropertyName("msvc.libraries")
        task.inputs.files(bundles).withPropertyName("msvc.linkBundles")
        task.inputs.files(settings.manifest, settings.icon).withPropertyName("msvc.resources")
        task.inputs.files(library.linkTaskProvider.map { it.outputFile }).withPropertyName("msvc.staticLibrary")
        task.doLast {
            val toolchain = locateOrExplain(settings.minimumToolset.orNull, settings.requireClangCl.get())
            val konanData = File(System.getenv("KONAN_DATA_DIR") ?: (System.getProperty("user.home") + "/.konan"))
            val gcc = KonanMingwRuntime.find(konanData) ?: throw GradleException(
                "Kotlin/Native's MinGW toolchain was not found under $konanData/dependencies, " +
                    "though the mingwX64 build just ran."
            )
            val output = task.outputFile.get()
            val unpacked = bundles.files.sortedBy { it.name }.map {
                MsvcLinkBundle.unpack(it, File(workDirectory, "bundles/${it.nameWithoutExtension}"))
            }
            val spec = MsvcLinkSpec(
                name = executable.baseName,
                toolchain = toolchain,
                staticLibrary = library.linkTaskProvider.get().outputFile.get(),
                gccRuntimeDir = gcc,
                workDir = workDirectory,
                outputFile = output,
                subsystem = settings.subsystem.get(),
                extraLibraries = settings.libraries.files.toList() + unpacked.flatMap { it.libraries },
                linkCppStandardLibrary = settings.linkCppStandardLibrary.get() || unpacked.any { it.cppStandardLibrary },
                manifest = settings.manifest.asFile.orNull,
                icon = settings.icon.asFile.orNull,
                systemLibraries = (settings.systemLibraries.get() + unpacked.flatMap { it.systemLibraries }).distinct(),
                kotlinEntrySymbol = symbol,
                rewriteMingwObjects = settings.rewriteMingwObjects.get(),
            )
            try {
                MsvcLinkPipeline(spec).run()
            } catch (e: MsvcLinkException) {
                throw GradleException(e.message ?: "the MSVC link failed", e)
            }
        }
    }
}
