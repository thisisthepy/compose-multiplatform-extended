/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.extended

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.compose.desktop.application.dsl.MacOSNotarizationSettings
import org.jetbrains.compose.desktop.application.dsl.MacOSSigningSettings
import org.jetbrains.compose.desktop.application.internal.MacSigner
import org.jetbrains.compose.desktop.application.internal.MacSignerImpl
import org.jetbrains.compose.desktop.application.internal.NoCertificateSigner
import org.jetbrains.compose.desktop.application.internal.validation.validate
import org.jetbrains.compose.desktop.application.tasks.AbstractNativeMacApplicationPackageTask
import org.jetbrains.compose.desktop.tasks.AbstractComposeDesktopTask
import org.jetbrains.compose.internal.utils.MacUtils
import org.jetbrains.compose.internal.utils.ioFile
import org.jetbrains.compose.internal.utils.property
import java.io.File

private fun Any.resourceText(name: String): String =
    javaClass.getResource("/org/jetbrains/compose/desktop/nativeapp/$name")?.readText()
        ?: error("the plugin is missing its resource $name")

private fun File.makeExecutable() = apply { setExecutable(true, false) }

// region macOS

/**
 * Signs the `.app` that `createDistributableNative` made: the executable first, then the
 * bundle. A Developer ID identity is used when `nativeDistributions.macOS.signing` names one,
 * and an ad hoc signature otherwise, which is what Apple Silicon needs to run a local build.
 */
@DisableCachingByDefault(because = "Signs with a keychain identity of the local machine")
abstract class AbstractNativeMacSignTask : AbstractComposeDesktopTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val appDir: DirectoryProperty = objects.directoryProperty()

    @get:Input
    val packageName: Property<String> = objects.property()

    @get:Input
    val bundleID: Property<String> = objects.property()

    @get:Input
    @get:Optional
    val entitlements: Property<String> = objects.property()

    @get:Internal
    internal var signingSettings: MacOSSigningSettings? = null

    @TaskAction
    fun run() {
        val app = appDir.ioFile.resolve("${packageName.get()}.app")
        check(app.isDirectory) { "No application bundle to sign at ${app.absolutePath}" }
        val settings = signingSettings
        val signer: MacSigner = if (settings != null && !settings.identity.orNull.isNullOrEmpty()) {
            MacSignerImpl(
                settings.validate(bundleID, project, project.provider { false }),
                runExternalTool
            )
        } else {
            logger.lifecycle("No signing identity is set: signing ${app.name} ad hoc")
            NoCertificateSigner(runExternalTool)
        }
        val entitlementsFile = entitlements.orNull?.let(::File)
            ?: project.layout.buildDirectory.file("compose/tmp/$name/native-entitlements.plist").get().asFile.apply {
                parentFile.mkdirs()
                writeText(resourceText("native-entitlements.plist"))
            }
        val macOSDir = app.resolve("Contents/MacOS")
        macOSDir.listFiles()?.filter { it.isFile }?.forEach { signer.sign(it, entitlementsFile) }
        signer.sign(app, entitlementsFile, forceEntitlements = true)
        runExternalTool(MacUtils.codesign, listOf("--verify", "--deep", "--strict", app.absolutePath))
    }
}

/**
 * Submits the disk image to Apple's notarization service and staples the ticket. Without
 * credentials (CI has none) the task says so and ends successfully, so the same build runs
 * with and without them.
 */
@DisableCachingByDefault(because = "Communicates with Apple's notarization service")
abstract class AbstractNativeMacNotarizeTask : AbstractComposeDesktopTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val packageFiles: ConfigurableFileCollection = objects.fileCollection()

    @get:Internal
    internal var notarizationSettings: MacOSNotarizationSettings? = null

    /** Staple the ticket to the file. A bare executable cannot carry one, so it is not stapled. */
    @get:Input
    val staple: Property<Boolean> = objects.property<Boolean>().value(true)

    /** Submit a zip of the file, which is what Apple accepts for an executable that is not a disk image or a package. */
    @get:Input
    val zipBeforeSubmit: Property<Boolean> = objects.property<Boolean>().value(false)

    @TaskAction
    fun run() {
        val settings = notarizationSettings
        val missing = NativeNotarization.missingCredentials(
            settings?.appleID?.orNull, settings?.password?.orNull, settings?.teamID?.orNull
        )
        if (missing.isNotEmpty()) {
            logger.lifecycle(NativeNotarization.skipMessage(missing))
            return
        }
        val validated = settings.validate()
        for (file in packageFiles.files.filter { it.isFile }) {
            val submitted = if (zipBeforeSubmit.get()) {
                project.layout.buildDirectory.file("compose/tmp/$name/${file.name}.zip").get().asFile.also {
                    it.parentFile.mkdirs()
                    it.delete()
                    runExternalTool(
                        File("/usr/bin/ditto"),
                        listOf("-c", "-k", "--keepParent", file.absolutePath, it.absolutePath)
                    )
                }
            } else file
            runExternalTool(
                tool = MacUtils.xcrun,
                args = listOf(
                    "notarytool", "submit", "--wait",
                    "--apple-id", validated.appleID,
                    "--team-id", validated.teamID,
                    submitted.absolutePath
                ),
                stdinStr = validated.password
            )
            if (staple.get()) runExternalTool(MacUtils.xcrun, listOf("stapler", "staple", file.absolutePath))
        }
    }
}

/** Joins the arm64 and x64 executables of one application into a universal executable. */
@DisableCachingByDefault(because = "Runs lipo")
abstract class AbstractNativeMacLipoTask : AbstractComposeDesktopTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val executables: ConfigurableFileCollection = objects.fileCollection()

    @get:OutputFile
    val universalExecutable: RegularFileProperty = objects.fileProperty()

    @TaskAction
    fun run() {
        val output = universalExecutable.ioFile
        output.parentFile.mkdirs()
        runExternalTool(
            File("/usr/bin/lipo"),
            listOf("-create", "-output", output.absolutePath) + executables.files.map { it.absolutePath }
        )
        output.makeExecutable()
    }
}

// endregion

// region CLI executables

/**
 * Copies the linked executable to `<name>.kexe` (`<name>.exe` on Windows). There is no
 * packaging: the executable is the output. Nothing is changed in it, so the linker's own
 * signature on macOS stays valid.
 */
@DisableCachingByDefault(because = "Copies one file")
abstract class AbstractNativeCliExecutableTask : AbstractComposeDesktopTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val executable: RegularFileProperty = objects.fileProperty()

    @get:Input
    val outputName: Property<String> = objects.property()

    @get:OutputFile
    val outputFile: RegularFileProperty = objects.fileProperty()

    @TaskAction
    fun run() {
        val out = outputFile.ioFile
        out.parentFile.mkdirs()
        executable.ioFile.copyTo(out, overwrite = true)
        out.makeExecutable()
        logger.lifecycle("The executable is written to ${out.canonicalPath}")
    }
}

/**
 * Signs a macOS `.kexe`. A Developer ID identity signs it with the hardened runtime. Without
 * one the linker's ad hoc signature is kept while it verifies, and the file is signed ad hoc
 * again when it no longer does (after a post-link edit).
 */
@DisableCachingByDefault(because = "Signs with a keychain identity of the local machine")
abstract class AbstractNativeMacKexeSignTask : AbstractComposeDesktopTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val kexe: RegularFileProperty = objects.fileProperty()

    @get:Input
    val bundleID: Property<String> = objects.property()

    @get:Internal
    internal var signingSettings: MacOSSigningSettings? = null

    @TaskAction
    fun run() {
        val file = kexe.ioFile
        val settings = signingSettings
        val hasIdentity = settings != null && !settings.identity.orNull.isNullOrEmpty()
        val valid = runExternalTool(
            MacUtils.codesign, listOf("--verify", "--strict", file.absolutePath), checkExitCodeIsNormal = false
        ).exitValue == 0
        when (NativeCliOutput.macSigning(hasIdentity, valid)) {
            NativeCliOutput.MacSigning.DeveloperId ->
                MacSignerImpl(settings!!.validate(bundleID, project, project.provider { false }), runExternalTool).sign(file)
            NativeCliOutput.MacSigning.KeepLinkerSignature ->
                logger.lifecycle("Keeping the linker's signature on ${file.name}")
            NativeCliOutput.MacSigning.ReSignAdHoc -> {
                logger.lifecycle("The signature on ${file.name} no longer verifies: signing it ad hoc")
                NoCertificateSigner(runExternalTool).sign(file)
            }
        }
    }
}

// endregion

// region Linux

/** Settings the Linux tasks share. */
@DisableCachingByDefault(because = "Stages files and runs platform tools")
abstract class AbstractNativeLinuxPackageTask : AbstractNativeMacApplicationPackageTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val executable: RegularFileProperty = objects.fileProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val iconFile: RegularFileProperty = objects.fileProperty()

    @get:Input
    @get:Optional
    val appDescription: Property<String> = objects.property()

    @get:Input
    @get:Optional
    val appCategory: Property<String> = objects.property()

    @get:Input
    @get:Optional
    val vendor: Property<String> = objects.property()

    @get:Input
    val architecture: Property<String> = objects.property<String>().value("amd64")

    @get:Input
    val fileAssociationMimeTypes: ListProperty<String> = objects.listProperty(String::class.java)

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val composeResourcesDirs: ConfigurableFileCollection = objects.fileCollection()

    protected val appName: String get() = packageName.get()

    protected fun desktopEntry(exec: String, icon: String): String = LinuxDesktopEntry.render(
        name = appName,
        exec = exec,
        icon = icon,
        description = appDescription.orNull,
        categories = appCategory.orNull,
        mimeTypes = fileAssociationMimeTypes.getOrElse(emptyList()),
    )

    protected fun copyResources(into: File) {
        if (!composeResourcesDirs.isEmpty) {
            fileOperations.copy { spec ->
                spec.from(composeResourcesDirs)
                spec.into(into.resolve("compose-resources").apply { mkdirs() })
            }
        }
    }
}

/**
 * The AppDir layout: `usr/bin/<name>`, its resources beside it, `<name>.desktop`, the icon,
 * `.DirIcon` and an `AppRun` that starts the executable. It runs as it is, and it is what the
 * AppImage is made from.
 */
@DisableCachingByDefault(because = "Stages files")
abstract class AbstractNativeLinuxAppDirTask : AbstractNativeLinuxPackageTask() {
    @get:Internal
    val appDirName: String get() = "$appName.AppDir"

    override fun createPackage(destinationDir: File, workingDir: File) {
        val appDir = destinationDir.resolve(appDirName).apply { mkdirs() }
        val bin = appDir.resolve("usr/bin").apply { mkdirs() }
        val exe = bin.resolve(appName)
        executable.ioFile.copyTo(exe, overwrite = true)
        exe.makeExecutable()
        copyResources(bin)
        iconFile.orNull?.asFile?.let {
            it.copyTo(appDir.resolve("$appName.png"), overwrite = true)
            it.copyTo(appDir.resolve(".DirIcon"), overwrite = true)
        }
        appDir.resolve("$appName.desktop").writeText(desktopEntry(exec = appName, icon = appName))
        appDir.resolve("AppRun").apply {
            writeText("#!/bin/sh\nHERE=\"\$(dirname \"\$(readlink -f \"\$0\")\")\"\nexec \"\$HERE/usr/bin/$appName\" \"\$@\"\n")
            makeExecutable()
        }
    }
}

@DisableCachingByDefault(because = "Runs appimagetool")
abstract class AbstractNativeLinuxAppImageTask : AbstractNativeLinuxPackageTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val appDir: DirectoryProperty = objects.directoryProperty()

    /** `appimagetool`; found on `PATH` when not set. */
    @get:Input
    @get:Optional
    val appImageTool: Property<String> = objects.property()

    override fun createPackage(destinationDir: File, workingDir: File) {
        val tool = appImageTool.orNull?.let(::File) ?: findOnPath("appimagetool")
            ?: error(
                "appimagetool was not found. Install it from https://github.com/AppImage/appimagetool " +
                    "(or set it on the task) and make it executable and available on PATH."
            )
        val out = destinationDir.resolve("$appName-${packageVersion.get()}-${architecture.get().appImageArch()}.AppImage")
        val dir = appDir.ioFile.resolve("$appName.AppDir")
        runExternalTool(
            tool = tool,
            args = listOf("--appimage-extract-and-run", dir.absolutePath, out.absolutePath),
            environment = mapOf("ARCH" to architecture.get().appImageArch())
        )
        out.makeExecutable()
        logger.lifecycle("The distribution is written to ${out.canonicalPath}")
    }

    private fun String.appImageArch() = if (this == "arm64") "aarch64" else "x86_64"
}

// endregion

// region Windows

/** The Windows application folder: the executable, its resources, icon and manifest. */
@DisableCachingByDefault(because = "Stages files")
abstract class AbstractNativeWindowsAppDirTask : AbstractNativeMacApplicationPackageTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val executable: RegularFileProperty = objects.fileProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val iconFile: RegularFileProperty = objects.fileProperty()

    /** Replaces the plugin's manifest. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val windowsManifest: RegularFileProperty = objects.fileProperty()

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val composeResourcesDirs: ConfigurableFileCollection = objects.fileCollection()

    override fun createPackage(destinationDir: File, workingDir: File) {
        val name = packageName.get()
        val dir = destinationDir.resolve(name).apply { mkdirs() }
        val exe = dir.resolve("$name.exe")
        executable.ioFile.copyTo(exe, overwrite = true)
        iconFile.orNull?.asFile?.copyTo(dir.resolve("$name.ico"), overwrite = true)
        // An embedded manifest takes precedence over this one, so it costs nothing when the
        // link already embedded it, and it keeps the process DPI aware when it did not.
        WindowsAppManifest.resolve(windowsManifest.orNull?.asFile, workingDir)
            .copyTo(dir.resolve("$name.exe.manifest"), overwrite = true)
        if (!composeResourcesDirs.isEmpty) {
            fileOperations.copy { spec ->
                spec.from(composeResourcesDirs)
                spec.into(dir.resolve("compose-resources").apply { mkdirs() })
            }
        }
    }
}

// endregion

/** Starts an executable, for the run tasks. */
@DisableCachingByDefault(because = "Runs the application")
abstract class AbstractNativeRunTask : AbstractComposeDesktopTask() {
    @get:Internal
    val executable: RegularFileProperty = objects.fileProperty()

    @get:Input
    val arguments: ListProperty<String> = objects.listProperty(String::class.java)

    @TaskAction
    fun run() {
        val exe = executable.ioFile
        check(exe.isFile) { "There is no executable to run at ${exe.absolutePath}" }
        execOperations.exec { spec ->
            spec.executable = exe.absolutePath
            spec.args(arguments.getOrElse(emptyList()))
            spec.workingDir(exe.parentFile)
        }
    }
}

internal fun findOnPath(tool: String): File? =
    System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
        .map { File(it, tool) }.firstOrNull { it.isFile && it.canExecute() }
