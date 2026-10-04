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
            // Next to the app, so it goes into the disk image: how to open a copy Gatekeeper blocks.
            appDir.ioFile.resolve("If macOS will not open the app.txt").writeText(InstallNotes.macGatekeeper(packageName.get()))
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
            runExternalTool(
                tool = MacUtils.xcrun,
                args = listOf(
                    "notarytool", "submit", "--wait",
                    "--apple-id", validated.appleID,
                    "--team-id", validated.teamID,
                    file.absolutePath
                ),
                stdinStr = validated.password
            )
            runExternalTool(MacUtils.xcrun, listOf("stapler", "staple", file.absolutePath))
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

    /** Reverse DNS id of the application, for the metainfo, desktop file and Flatpak. */
    @get:Input
    val appId: Property<String> = objects.property()

    @get:Input
    @get:Optional
    val appSummary: Property<String> = objects.property()

    @get:Input
    @get:Optional
    val appLicense: Property<String> = objects.property()

    @get:Input
    @get:Optional
    val appHomepage: Property<String> = objects.property()

    @get:Input
    val fileAssociationMimeTypes: ListProperty<String> = objects.listProperty(String::class.java)

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val composeResourcesDirs: ConfigurableFileCollection = objects.fileCollection()

    protected val appName: String get() = packageName.get()

    protected fun linuxMetadata(): LinuxAppMetadata = LinuxAppMetadata(
        id = appId.get(),
        name = appName,
        summary = appSummary.orNull ?: appDescription.orNull?.lineSequence()?.firstOrNull() ?: appName,
        description = (appDescription.orNull ?: appName).lines().filter { it.isNotBlank() },
        version = packageVersion.get(),
        date = java.time.LocalDate.now().toString(),
        executable = appName,
        developerName = vendor.orNull ?: appName,
        license = appLicense.getOrElse("LicenseRef-proprietary"),
        homepage = appHomepage.orNull,
        categories = appCategory.orNull,
    )

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
 * AppImage and the Flatpak are made from.
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
        val meta = linuxMetadata()
        appDir.resolve("usr/share/metainfo/${AppStreamMetainfo.appdataFileName(meta)}").apply {
            parentFile.mkdirs()
            writeText(AppStreamMetainfo.render(meta))
        }
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

    /** Embedded update information, and a `.zsync` file written beside the AppImage. */
    @get:Input
    @get:Optional
    val updateInformation: Property<String> = objects.property()

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
            args = listOf("--appimage-extract-and-run") +
                (updateInformation.orNull?.let { listOf("-u", it) } ?: emptyList()) +
                listOf(dir.absolutePath, out.absolutePath),
            workingDir = destinationDir,
            environment = mapOf("ARCH" to architecture.get().appImageArch())
        )
        out.makeExecutable()
        NativeChecksums.write(destinationDir, listOf(out, File(out.path + ".zsync")))
        logger.lifecycle("The distribution is written to ${out.canonicalPath}")
    }

    private fun String.appImageArch() = if (this == "arm64") "aarch64" else "x86_64"
}

@DisableCachingByDefault(because = "Runs dpkg-deb")
abstract class AbstractNativeLinuxDebTask : AbstractNativeLinuxPackageTask() {
    override fun createPackage(destinationDir: File, workingDir: File) {
        val debName = DebControl.debianPackageName(appName)
        val stage = workingDir.resolve("deb-root").apply { deleteRecursively(); mkdirs() }
        val installDir = stage.resolve("opt/$debName").apply { mkdirs() }
        val exe = installDir.resolve(appName)
        executable.ioFile.copyTo(exe, overwrite = true)
        exe.makeExecutable()
        copyResources(installDir)
        iconFile.orNull?.asFile?.let {
            it.copyTo(stage.resolve("usr/share/icons/hicolor/256x256/apps/$debName.png").apply { parentFile.mkdirs() })
        }
        stage.resolve("usr/bin").mkdirs()
        java.nio.file.Files.createSymbolicLink(
            stage.resolve("usr/bin/$debName").toPath(),
            java.nio.file.Paths.get("/opt/$debName/$appName")
        )
        stage.resolve("usr/share/applications/$debName.desktop").apply {
            parentFile.mkdirs()
            writeText(desktopEntry(exec = "/opt/$debName/$appName", icon = debName))
        }
        val sizeKb = stage.walk().filter { it.isFile }.sumOf { it.length() } / 1024
        stage.resolve("DEBIAN/control").apply {
            parentFile.mkdirs()
            writeText(
                DebControl.render(
                    packageName = appName,
                    version = packageVersion.get(),
                    architecture = architecture.get(),
                    maintainer = maintainer.orNull ?: vendor.orNull ?: "Unknown <unknown@localhost>",
                    description = appDescription.orNull ?: appName,
                    installedSizeKb = sizeKb,
                    depends = listOf("libc6", "libx11-6", "libxext6", "libxi6", "libxrandr2", "libxcursor1"),
                )
            )
        }
        val deb = destinationDir.resolve("${debName}_${packageVersion.get()}_${architecture.get()}.deb")
        runExternalTool(
            tool = findOnPath("dpkg-deb") ?: error("dpkg-deb was not found; install the dpkg package."),
            args = listOf("--build", "--root-owner-group", stage.absolutePath, deb.absolutePath)
        )
        logger.lifecycle("The distribution is written to ${deb.canonicalPath}")
    }
}

/**
 * A `.flatpak` bundle: the executable and its resources, a Flatpak manifest, AppStream
 * metainfo and a desktop file, built with `flatpak-builder` and exported with
 * `flatpak build-bundle`. The manifest and the staged sources are kept beside the bundle, which
 * is what Flathub reviews.
 */
@DisableCachingByDefault(because = "Runs flatpak-builder")
abstract class AbstractNativeLinuxFlatpakTask : AbstractNativeLinuxPackageTask() {
    @get:Input
    val runtimeVersion: Property<String> = objects.property<String>().value(FlatpakManifest.DEFAULT_RUNTIME_VERSION)

    @get:Input
    val wayland: Property<Boolean> = objects.property<Boolean>().value(false)

    @get:Input
    val extraFinishArgs: ListProperty<String> = objects.listProperty(String::class.java)

    override fun createPackage(destinationDir: File, workingDir: File) {
        val meta = linuxMetadata()
        val source = destinationDir.resolve("flatpak-source").apply { deleteRecursively(); mkdirs() }
        val payload = source.resolve("payload").apply { mkdirs() }
        val exe = payload.resolve(appName)
        executable.ioFile.copyTo(exe, overwrite = true)
        exe.makeExecutable()
        copyResources(payload)
        // The module's source is the payload directory, so the packaging files sit inside it.
        payload.resolve("${meta.id}.desktop").writeText(desktopEntry(exec = appName, icon = meta.id))
        payload.resolve(AppStreamMetainfo.fileName(meta)).writeText(AppStreamMetainfo.render(meta))
        val icon = iconFile.orNull?.asFile?.also { it.copyTo(payload.resolve("${meta.id}.png"), overwrite = true) }
        source.resolve("${meta.id}.json").writeText(
            FlatpakManifest.render(
                meta,
                payloadDir = "payload",
                runtimeVersion = runtimeVersion.get(),
                wayland = wayland.get(),
                extraFinishArgs = extraFinishArgs.getOrElse(emptyList()),
                iconFile = icon?.let { "${meta.id}.png" },
            )
        )
        val builder = findOnPath("flatpak-builder")
            ?: error("flatpak-builder was not found; install the flatpak-builder package and the ${FlatpakManifest.DEFAULT_RUNTIME}/${FlatpakManifest.DEFAULT_SDK} ${runtimeVersion.get()} runtime and SDK.")
        val repo = workingDir.resolve("repo")
        val build = workingDir.resolve("build")
        runExternalTool(
            builder,
            listOf("--force-clean", "--disable-rofiles-fuse", "--repo=${repo.absolutePath}", build.absolutePath, source.resolve("${meta.id}.json").absolutePath),
            workingDir = source
        )
        val bundle = destinationDir.resolve("${meta.id}-${packageVersion.get()}-${architecture.get()}.flatpak")
        runExternalTool(
            findOnPath("flatpak") ?: error("flatpak was not found; install the flatpak package."),
            listOf("build-bundle", repo.absolutePath, bundle.absolutePath, meta.id)
        )
        NativeChecksums.write(destinationDir, listOf(bundle))
        logger.lifecycle("The distribution is written to ${bundle.canonicalPath}")
    }
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
        NativeChecksums.write(dir, listOf(exe))
    }
}

/** `<name>-<version>.zip` of the application folder, the portable form of the exe. */
@DisableCachingByDefault(because = "Zips files")
abstract class AbstractNativeWindowsZipTask : AbstractNativeMacApplicationPackageTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val appDir: DirectoryProperty = objects.directoryProperty()

    override fun createPackage(destinationDir: File, workingDir: File) {
        val zip = destinationDir.resolve("${fullPackageName.get()}.zip")
        java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { out ->
            val root = appDir.ioFile
            root.walk().filter { it.isFile }.forEach { file ->
                out.putNextEntry(java.util.zip.ZipEntry(file.relativeTo(root.parentFile).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        logger.lifecycle("The distribution is written to ${zip.canonicalPath}")
    }
}

/** An msi from the application folder, through WiX 3 (`candle` and `light`). */
@DisableCachingByDefault(because = "Runs the WiX toolset")
abstract class AbstractNativeWindowsMsiTask : AbstractNativeMacApplicationPackageTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val appDir: DirectoryProperty = objects.directoryProperty()

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val wixToolsetDir: DirectoryProperty = objects.directoryProperty()

    @get:Input
    @get:Optional
    val vendor: Property<String> = objects.property()

    @get:Input
    @get:Optional
    val upgradeUuid: Property<String> = objects.property()

    @get:Input
    val perUserInstall: Property<Boolean> = objects.property<Boolean>().value(false)

    @get:Input
    val shortcut: Property<Boolean> = objects.property<Boolean>().value(false)

    override fun createPackage(destinationDir: File, workingDir: File) {
        val name = packageName.get()
        val wixDir = wixToolsetDir.orNull?.asFile
            ?: System.getenv("WIX_PATH")?.let(::File)
            ?: error("The WiX toolset was not found. Set WIX_PATH to a WiX 3 binaries directory.")
        val candle = wixDir.resolve("candle.exe")
        val light = wixDir.resolve("light.exe")
        check(candle.isFile && light.isFile) { "candle.exe and light.exe are not in $wixDir" }

        val root = appDir.ioFile.resolve(name)
        val files = root.walk().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        val source = workingDir.resolve("$name.wxs")
        source.writeText(
            WindowsInstallerSource.render(
                productName = name,
                version = packageVersion.get(),
                manufacturer = vendor.orNull ?: name,
                exeName = "$name.exe",
                upgradeCode = upgradeUuid.orNull ?: WindowsInstallerSource.guid(name),
                perUser = perUserInstall.get(),
                shortcut = shortcut.get(),
                files = files,
            )
        )
        val obj = workingDir.resolve("$name.wixobj")
        val msi = destinationDir.resolve("${fullPackageName.get()}.msi")
        runExternalTool(candle, listOf("-nologo", "-out", obj.absolutePath, source.absolutePath), workingDir = root)
        runExternalTool(
            light,
            listOf("-nologo", "-sval", "-out", msi.absolutePath, "-b", root.absolutePath, obj.absolutePath),
            workingDir = root
        )
        logger.lifecycle("The distribution is written to ${msi.canonicalPath}")
    }
}

/**
 * An `.msix` and an `.msixbundle` of the application folder, packed with MakeAppx. The
 * package is signed when `msix { certificateFile }` is set. Without a certificate it is left
 * unsigned and an install note is written beside it. The SHA-256 of each file is recorded in
 * `checksums.sha256`.
 */
@DisableCachingByDefault(because = "Runs the Windows SDK's MakeAppx")
abstract class AbstractNativeWindowsMsixTask : AbstractNativeMacApplicationPackageTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val appDir: DirectoryProperty = objects.directoryProperty()

    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val iconPng: RegularFileProperty = objects.fileProperty()

    @get:Input
    val identityName: Property<String> = objects.property()

    @get:Input
    val publisher: Property<String> = objects.property()

    @get:Input
    val publisherDisplayName: Property<String> = objects.property()

    @get:Input
    @get:Optional
    val appDescription: Property<String> = objects.property()

    @get:Input
    val channel: Property<MsixChannel> = objects.property()

    @get:Input
    @get:Optional
    val revision: Property<Int> = objects.property()

    @get:Input
    val capabilities: ListProperty<String> = objects.listProperty(String::class.java)

    @get:Input
    val languages: ListProperty<String> = objects.listProperty(String::class.java)

    @get:Input
    val architecture: Property<String> = objects.property<String>().value("x64")

    @get:Internal
    internal var certificateFile: String? = null

    @get:Internal
    internal var certificatePassword: String? = null

    override fun createPackage(destinationDir: File, workingDir: File) {
        val name = packageName.get()
        val version = MsixVersion.fromSemver(packageVersion.get(), channel.get(), revision.orNull)
        val arch = if (architecture.get() == "arm64") MsixArch.Arm64 else MsixArch.X64
        val meta = MsixMetadata(
            displayName = name,
            publisherDisplayName = publisherDisplayName.get(),
            description = appDescription.getOrElse(name),
            identityName = identityName.get(),
            publisher = publisher.get(),
            languages = languages.get(),
            capabilities = capabilities.get(),
        )
        val layout = workingDir.resolve("layout").apply { deleteRecursively(); mkdirs() }
        appDir.ioFile.resolve(name).copyRecursively(layout, overwrite = true)
        layout.resolve("AppxManifest.xml").writeText(MsixManifest.render(meta, version, arch, "$name.exe"))
        MsixAssets.writeAll(iconPng.ioFile, layout.resolve(MsixAssets.DIR))

        val makeappx = WindowsSdk.findTool("makeappx.exe")
        val packageFile = destinationDir.resolve("${meta.identityName}_${version}_${arch.text}.msix")
        runExternalTool(makeappx, listOf("pack", "/o", "/d", layout.absolutePath, "/p", packageFile.absolutePath))
        val bundleSource = workingDir.resolve("bundle-src").apply { deleteRecursively(); mkdirs() }
        packageFile.copyTo(bundleSource.resolve(packageFile.name))
        val bundle = destinationDir.resolve("${meta.identityName}_$version.msixbundle")
        runExternalTool(makeappx, listOf("bundle", "/o", "/bv", version.toString(), "/d", bundleSource.absolutePath, "/p", bundle.absolutePath))

        val certificate = certificateFile
        if (certificate != null) {
            val signtool = WindowsSdk.findTool("signtool.exe")
            for (file in listOf(packageFile, bundle)) {
                runExternalTool(
                    signtool,
                    listOfNotNull("sign", "/fd", "SHA256", "/f", certificate, certificatePassword?.let { "/p" }, certificatePassword, file.absolutePath)
                )
            }
        } else {
            destinationDir.resolve("INSTALL.txt").writeText(InstallNotes.unsignedMsix(packageFile.name))
            logger.lifecycle("No certificate is set: ${packageFile.name} is unsigned. See INSTALL.txt beside it.")
        }
        NativeChecksums.write(destinationDir, listOf(packageFile, bundle))
        logger.lifecycle("The distribution is written to ${packageFile.canonicalPath}")
    }
}

/**
 * Runs the Windows App Certification Kit on the package, the check Partner Center runs on a
 * submission. It needs Windows, the SDK's kit, and a package signed with a certificate the
 * machine trusts.
 */
@DisableCachingByDefault(because = "Runs the certification kit")
abstract class AbstractNativeWindowsWackTask : AbstractComposeDesktopTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val packageFiles: ConfigurableFileCollection = objects.fileCollection()

    @get:OutputFile
    val report: RegularFileProperty = objects.fileProperty()

    @TaskAction
    fun run() {
        val file = packageFiles.files.firstOrNull { it.name.endsWith(".msix") || it.name.endsWith(".msixbundle") }
            ?: error("There is no .msix or .msixbundle to certify.")
        val appcert = WindowsSdk.findKitTool("App Certification Kit", "appcert.exe")
        val out = report.ioFile.apply { parentFile.mkdirs(); delete() }
        runExternalTool(appcert, listOf("reset"))
        val result = runExternalTool(
            appcert,
            listOf("test", "-appxpackagepath", file.absolutePath, "-reportoutputpath", out.absolutePath),
            checkExitCodeIsNormal = false
        )
        check(out.isFile) { "The certification kit wrote no report (exit ${result.exitValue})." }
        val text = out.readText()
        val failed = Regex("""RESULT="FAIL"""").containsMatchIn(text) || Regex("""OVERALL_RESULT="FAIL"""").containsMatchIn(text)
        check(!failed) { "The Windows App Certification Kit reported failures; see ${out.absolutePath}" }
        logger.lifecycle("The certification report is written to ${out.absolutePath}")
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

/** Finding the Windows SDK's packaging tools. */
internal object WindowsSdk {
    /** `10.0.22621.0` as numbers, or null when it is not four of them. */
    fun sdkVersion(name: String): List<Int>? =
        name.split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.size == 4 }

    /** The newest `<kits>/bin/<version>/<host>/<tool>`. */
    fun newestInKits(kits: File, host: String, tool: String): File? =
        kits.resolve("bin").listFiles().orEmpty()
            .mapNotNull { dir -> sdkVersion(dir.name)?.let { it to dir.resolve(host).resolve(tool) } }
            .filter { it.second.isFile }
            .maxWithOrNull(compareBy<Pair<List<Int>, File>> { it.first[0] }.thenBy { it.first[1] }.thenBy { it.first[2] }.thenBy { it.first[3] })
            ?.second

    /** `COMPOSE_WINDOWS_SDK_BIN`, else the newest installed Windows 10/11 SDK, else `PATH`. */
    fun findTool(tool: String): File {
        System.getenv("COMPOSE_WINDOWS_SDK_BIN")?.let {
            val candidate = File(it, tool)
            check(candidate.isFile) { "COMPOSE_WINDOWS_SDK_BIN is set, and $candidate is not there" }
            return candidate
        }
        val host = if (System.getProperty("os.arch").lowercase().contains("aarch64")) "arm64" else "x64"
        for (root in listOf("ProgramFiles(x86)", "ProgramFiles")) {
            System.getenv(root)?.let { newestInKits(File(it, "Windows Kits/10"), host, tool) }?.let { return it }
        }
        findOnPath(tool)?.let { return it }
        error("$tool was not found: install the Windows SDK, or set COMPOSE_WINDOWS_SDK_BIN to the directory that holds it.")
    }

    fun findKitTool(kit: String, tool: String): File {
        for (root in listOf("ProgramFiles(x86)", "ProgramFiles")) {
            System.getenv(root)?.let { File(it, "Windows Kits/10/$kit/$tool") }?.takeIf { it.isFile }?.let { return it }
        }
        error("$tool was not found: the $kit ships with the Windows SDK.")
    }
}
