/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.extended

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
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
import org.jetbrains.compose.desktop.tasks.AbstractComposeDesktopTask
import org.jetbrains.compose.internal.utils.ioFile
import org.jetbrains.compose.internal.utils.property
import java.io.File
import java.net.URI
import java.time.LocalDate
import javax.imageio.ImageIO

/*
 * One bundle holds one application: the payload directory the application was laid out in
 * (the same layout on the JVM path and on the Kotlin/Native path), an icon and metadata. A
 * checksum of each file that is distributed is recorded beside it as `<file>.sha256`.
 */

private fun File.makeExecutable() = apply { setExecutable(true, false) }

private fun File.copyDirectory(into: File) {
    into.mkdirs()
    // `copyRecursively` keeps no permissions, and the executable bit is what has to survive.
    walkTopDown().forEach { source ->
        val target = into.resolve(source.relativeTo(this).path)
        when {
            source.isDirectory -> target.mkdirs()
            else -> {
                target.parentFile.mkdirs()
                java.nio.file.Files.copy(
                    source.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.COPY_ATTRIBUTES,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                )
            }
        }
    }
}

/** The release date written into AppStream metadata: `SOURCE_DATE_EPOCH` when set, today otherwise. */
internal fun defaultReleaseDate(): String =
    System.getenv("SOURCE_DATE_EPOCH")?.toLongOrNull()
        ?.let { java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString() }
        ?: LocalDate.now().toString()

internal fun findTool(tool: String, vararg extensions: String): File? =
    System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
        .flatMap { dir -> (listOf("") + extensions).map { File(dir, tool + it) } }
        .firstOrNull { it.isFile && it.canExecute() }

/** Downloads a tool at a pinned version and refuses it when the digest differs. */
internal object PinnedDownload {
    fun fetch(url: String, sha256: String, target: File): File {
        if (target.isFile && BundleChecksum.sha256(target) == sha256) return target
        target.parentFile.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        val got = BundleChecksum.sha256(part)
        check(got == sha256) {
            "${target.name} downloaded from $url has digest $got, but $sha256 is pinned. " +
                "Either the release was replaced or the download is damaged."
        }
        part.renameTo(target)
        return target.makeExecutable()
    }
}

/** The metadata every bundle task needs, and the layout of what it packages. */
@DisableCachingByDefault(because = "Stages files and runs platform tools")
abstract class AbstractBundleTask : AbstractComposeDesktopTask() {
    /** The directory the application is laid out in. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val payloadDir: DirectoryProperty = objects.directoryProperty()

    /** The executable's path inside [payloadDir], with forward slashes. */
    @get:Input
    val executablePath: Property<String> = objects.property()

    @get:Input
    val appName: Property<String> = objects.property()

    @get:Input
    val appVersion: Property<String> = objects.property()

    @get:Input
    @get:Optional
    val appDescription: Property<String> = objects.property()

    @get:Input
    @get:Optional
    val vendor: Property<String> = objects.property()

    @get:Input
    val architecture: Property<String> = objects.property<String>().value(AppImageNames.architecture(System.getProperty("os.arch")))

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val iconFile: RegularFileProperty = objects.fileProperty()

    @get:OutputDirectory
    val destinationDir: DirectoryProperty = objects.directoryProperty()

    @get:Internal
    protected val executableName: String get() = executablePath.get().substringAfterLast('/')

    @get:Internal
    protected val stagingDir: File get() = project.layout.buildDirectory.dir("compose/tmp/$name").get().asFile

    protected fun requireIcon(format: String): File =
        iconFile.orNull?.asFile?.takeIf { it.isFile }
            ?: error("$format needs a PNG icon. Set nativeDistributions.linux.iconFile (or windows.msix { logoFile }), or leave it unset to use the default icon.")

    protected fun requirePayload(): File {
        val dir = payloadDir.ioFile
        val exe = dir.resolve(executablePath.get())
        check(exe.isFile) { "The payload has no executable at ${executablePath.get()} (looked in ${dir.absolutePath})" }
        return dir
    }

    protected fun finish(file: File) {
        val sum = BundleChecksum.record(file)
        logger.lifecycle("The distribution is written to ${file.canonicalPath}")
        logger.lifecycle("Checksum: ${sum.readText().trim()}")
    }
}

// region Flatpak

/**
 * The Flatpak manifest, and the directory it builds from: the payload, the desktop entry, the
 * AppStream metainfo and the icon. A payload archive with its digest is written beside it, and
 * when `flatpak { payloadUrl }` says where the archive will be published, a second manifest in the
 * shape Flathub accepts (the payload fetched by URL and checked by digest).
 */
@DisableCachingByDefault(because = "Stages files")
abstract class AbstractFlatpakManifestTask : AbstractBundleTask() {
    @get:Input
    val appId: Property<String> = objects.property()

    @get:Input
    val runtime: Property<String> = objects.property()

    @get:Input
    val runtimeVersion: Property<String> = objects.property()

    @get:Input
    val finishArgs: ListProperty<String> = objects.listProperty(String::class.java)

    @get:Input
    @get:Optional
    val appCategory: Property<String> = objects.property()

    @get:Input
    val mimeTypes: ListProperty<String> = objects.listProperty(String::class.java)

    @get:Input
    @get:Optional
    val summary: Property<String> = objects.property()

    @get:Input
    @get:Optional
    val developerName: Property<String> = objects.property()

    @get:Input
    @get:Optional
    val homepage: Property<String> = objects.property()

    @get:Input
    val license: Property<String> = objects.property()

    @get:Input
    val metadataLicense: Property<String> = objects.property()

    @get:Input
    val releaseNotes: ListProperty<String> = objects.listProperty(String::class.java)

    @get:Input
    @get:Optional
    val payloadUrl: Property<String> = objects.property()

    @get:Input
    val releaseDate: Property<String> = objects.property<String>().value(defaultReleaseDate())

    @get:Internal
    val manifestFile: File get() = destinationDir.ioFile.resolve("${appId.get()}.json")

    @TaskAction
    fun run() {
        val id = appId.get()
        check(FlatpakNames.isValidAppId(id)) {
            "`$id` is not a Flatpak application id. Use reverse DNS form with at least two elements, " +
                "such as org.example.hello, in nativeDistributions.linux.flatpak { appId }."
        }
        if (id.startsWith("org.example.")) {
            logger.warn("The application id $id builds and installs, but Flathub refuses ids under example.org. Set nativeDistributions.linux.flatpak { appId }.")
        }
        val payload = requirePayload()
        val icon = requireIcon("A Flatpak")
        val name = appName.get()
        val version = appVersion.get()
        val arch = architecture.get()

        val out = destinationDir.ioFile.apply { deleteRecursively(); mkdirs() }
        payload.copyDirectory(out.resolve("payload"))
        out.resolve("payload/${executablePath.get()}").makeExecutable()

        val packaging = out.resolve("packaging").apply { mkdirs() }
        val desktopFile = "$id.desktop"
        packaging.resolve(desktopFile).writeText(
            LinuxDesktopEntry.render(
                name = name, exec = name, icon = id, description = appDescription.orNull,
                categories = appCategory.orNull, mimeTypes = mimeTypes.getOrElse(emptyList())
            )
        )
        val metainfoFile = AppStreamMetainfo.fileName(id)
        val description = appDescription.orNull ?: name
        packaging.resolve(metainfoFile).writeText(
            AppStreamMetainfo.render(
                appId = id, name = name, summary = summary.orNull ?: description.lineSequence().first(),
                description = description.lines().filter { it.isNotBlank() }, version = version,
                date = releaseDate.get(), developerName = developerName.orNull ?: vendor.orNull ?: name,
                metadataLicense = metadataLicense.get(), projectLicense = license.get(),
                homepage = homepage.orNull, executable = name, releaseNotes = releaseNotes.getOrElse(emptyList()),
            )
        )
        val size = runCatching { ImageIO.read(icon)?.let { "${it.width}x${it.height}" } }.getOrNull() ?: "256x256"
        val iconPath = "share/icons/hicolor/$size/apps/$id.png"
        icon.copyTo(packaging.resolve(iconPath).apply { parentFile.mkdirs() }, overwrite = true)

        fun manifest(source: FlatpakPayloadSource) = FlatpakManifest.render(
            appId = id, executableName = name, executableRelativePath = executablePath.get(),
            runtime = runtime.get(), runtimeVersion = runtimeVersion.get(),
            finishArgs = FlatpakManifest.finishArgs(finishArgs.getOrElse(emptyList())),
            source = source, iconInstallPaths = listOf(iconPath), desktopFile = desktopFile, metainfoFile = metainfoFile,
        )
        manifestFile.writeText(manifest(FlatpakPayloadSource.Directory("payload")))

        // The archive Flathub fetches. Its top directory is stripped on extraction.
        val archive = out.resolve(FlatpakNames.payloadArchiveName(name, version, arch))
        val top = "$name-$version"
        val mac = System.getProperty("os.name").startsWith("Mac")
        runExternalTool(
            findTool("tar") ?: error("tar was not found; it makes the payload archive."),
            // The archive has one top directory, which flatpak-builder strips on extraction.
            // GNU tar renames with --transform, and the bsdtar of macOS with -s.
            listOf("-czf", archive.absolutePath) +
                (if (mac) listOf("-s", ",^\\.,$top,") else listOf("--transform", "s|^\\.|$top|")) +
                listOf("-C", payload.absolutePath, ".")
        )
        BundleChecksum.record(archive)
        payloadUrl.orNull?.let { url ->
            val flathub = out.resolve("$id.flathub.json")
            flathub.writeText(manifest(FlatpakPayloadSource.Archive(url, BundleChecksum.sha256(archive))))
            logger.lifecycle("The Flathub manifest is written to ${flathub.canonicalPath}")
        }
        logger.lifecycle("The Flatpak manifest is written to ${manifestFile.canonicalPath}")
    }
}

/** Builds the manifest with `flatpak-builder` and exports the result as a single-file `.flatpak` bundle. */
@DisableCachingByDefault(because = "Runs flatpak-builder")
abstract class AbstractFlatpakBundleTask : AbstractComposeDesktopTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val manifestDir: DirectoryProperty = objects.directoryProperty()

    @get:Input
    val appId: Property<String> = objects.property()

    @get:Input
    val appName: Property<String> = objects.property()

    @get:Input
    val appVersion: Property<String> = objects.property()

    @get:Input
    val architecture: Property<String> = objects.property()

    @get:Input
    val runtimeRemote: Property<String> = objects.property<String>().value("flathub")

    @get:Internal
    val flatpakBuilder: Property<File> = objects.property()

    @get:Internal
    val flatpak: Property<File> = objects.property()

    @get:OutputDirectory
    val destinationDir: DirectoryProperty = objects.directoryProperty()

    @TaskAction
    fun run() {
        check(System.getProperty("os.name").startsWith("Linux")) {
            "A Flatpak bundle is built on Linux with flatpak-builder. The manifest task runs anywhere, and " +
                "the manifest it writes can be built on a Linux machine."
        }
        val builder = flatpakBuilder.orNull ?: findTool("flatpak-builder")
            ?: error("flatpak-builder was not found. Install it (Debian and Ubuntu: apt-get install flatpak flatpak-builder).")
        val flatpakTool = flatpak.orNull ?: findTool("flatpak") ?: error("flatpak was not found.")
        val work = project.layout.buildDirectory.dir("compose/tmp/$name").get().asFile.apply { deleteRecursively(); mkdirs() }
        val repo = work.resolve("repo")
        val manifest = manifestDir.ioFile.resolve("${appId.get()}.json")
        val out = destinationDir.ioFile.apply { mkdirs() }
        val bundle = out.resolve(FlatpakNames.bundleFileName(appName.get(), appVersion.get(), architecture.get()))
        bundle.delete()

        runExternalTool(
            tool = builder,
            args = listOf(
                "--user", "--force-clean", "--disable-rofiles-fuse",
                "--install-deps-from=${runtimeRemote.get()}",
                "--state-dir=${work.resolve("state").absolutePath}",
                "--repo=${repo.absolutePath}",
                work.resolve("build").absolutePath, manifest.absolutePath,
            ),
            workingDir = manifestDir.ioFile,
        )
        runExternalTool(
            tool = flatpakTool,
            args = listOf("build-bundle", repo.absolutePath, bundle.absolutePath, appId.get()),
        )
        check(bundle.isFile) { "flatpak build-bundle wrote no ${bundle.name}" }
        val sum = BundleChecksum.record(bundle)
        logger.lifecycle("The distribution is written to ${bundle.canonicalPath}")
        logger.lifecycle("Checksum: ${sum.readText().trim()}")
        logger.lifecycle("Install it with: flatpak install --user ${bundle.name}")
    }
}

// endregion

// region MSIX

/** Finds the Windows SDK tool `name` in the newest SDK, or on `PATH`. */
internal fun findWindowsSdkTool(name: String): File? {
    findTool(name, ".exe")?.let { return it }
    val roots = listOfNotNull(System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"))
        .map { File(it, "Windows Kits/10") }
    for (root in roots) {
        val bin = root.resolve("bin")
        val versions = bin.listFiles { f -> f.isDirectory && f.name.startsWith("10.") }.orEmpty().sortedByDescending { it.name }
        for (version in versions) {
            val tool = version.resolve("x64/$name.exe")
            if (tool.isFile) return tool
        }
    }
    return null
}

/**
 * An `.msix` package of the Windows application folder, built unsigned. The folder is laid
 * out as the package root, with `AppxManifest.xml` and the tile images made from the icon.
 * The version comes from the application's version, and the package is a full-trust desktop
 * application (`runFullTrust`), which is how a Win32 executable is packaged.
 *
 * An unsigned package cannot be installed by double-clicking it. [INSTALL_FILE] beside the
 * package says how: register the loose layout in developer mode, or sign the package, with
 * a test certificate to install it, or with the certificate the Store gives for a submission.
 */
@DisableCachingByDefault(because = "Runs makeappx")
abstract class AbstractMsixTask : AbstractBundleTask() {
    @get:Input
    val identityName: Property<String> = objects.property()

    @get:Input
    val publisher: Property<String> = objects.property()

    @get:Input
    val publisherDisplayName: Property<String> = objects.property()

    @get:Input
    val displayName: Property<String> = objects.property()

    @get:Input
    val minVersion: Property<String> = objects.property()

    @get:Input
    val maxVersionTested: Property<String> = objects.property()

    @get:Input
    val languages: ListProperty<String> = objects.listProperty(String::class.java)

    @get:Input
    val capabilities: ListProperty<String> = objects.listProperty(String::class.java)

    @get:Internal
    val makeAppx: Property<File> = objects.property()

    @get:Internal
    val outputFileName: String
        get() = "${AppImageNames.fileSafe(appName.get())}-${MsixVersion.fromApplicationVersion(appVersion.get())}-${architecture.get()}.msix"

    @TaskAction
    fun run() {
        val version = MsixVersion.fromApplicationVersion(appVersion.get())
        val payload = requirePayload()
        val out = destinationDir.ioFile.apply { mkdirs() }
        val layout = out.resolve("layout").apply { deleteRecursively(); mkdirs() }
        payload.copyDirectory(layout)

        val source = requireIcon("An MSIX package").let {
            checkNotNull(runCatching { ImageIO.read(it) }.getOrNull()) {
                "${it.absolutePath} is not a PNG the plugin can read. Set nativeDistributions.windows.msix { logoFile } to a PNG."
            }
        }
        val assets = layout.resolve(MsixAssets.DIR).apply { mkdirs() }
        for (asset in MsixAssets.all) MsixImages.write(source, asset, assets.resolve(asset.file))

        layout.resolve("AppxManifest.xml").writeText(
            MsixManifest.render(
                identityName = identityName.get(), publisher = publisher.get(), version = version,
                architecture = architecture.get(), displayName = displayName.get(),
                publisherDisplayName = publisherDisplayName.get(),
                description = appDescription.orNull ?: displayName.get(),
                executable = executablePath.get().replace('/', '\\'),
                minVersion = minVersion.get(), maxVersionTested = maxVersionTested.get(),
                languages = languages.get(), capabilities = capabilities.getOrElse(emptyList()),
            )
        )

        val tool = makeAppx.orNull ?: findWindowsSdkTool("makeappx")
            ?: error(
                "makeappx.exe was not found. It is part of the Windows SDK: install it with Visual Studio " +
                    "(the Windows 10/11 SDK component), or set nativeDistributions.windows.msix { makeAppx }."
            )
        val msix = out.resolve(outputFileName)
        msix.delete()
        runExternalTool(tool, listOf("pack", "/d", layout.absolutePath, "/p", msix.absolutePath, "/o"))
        check(msix.isFile) { "makeappx wrote no ${msix.name}" }
        finish(msix)
        out.resolve(INSTALL_FILE).writeText(installInstructions(msix.name, layout.name, publisher.get(), version))
        logger.lifecycle("The package is unsigned. How to install or submit it: ${out.resolve(INSTALL_FILE).canonicalPath}")
    }

    companion object {
        const val INSTALL_FILE = "INSTALL-msix.txt"

        fun installInstructions(msix: String, layout: String, publisher: String, version: MsixVersion) = """
            $msix is an unsigned MSIX package, version $version.

            Windows installs a package only when it is signed. Pick one of these.

            1. Try it on your own machine (developer mode)
               Settings > System > For developers > Developer Mode: on. Then, in PowerShell:
                   Add-AppxPackage -Register "$layout\AppxManifest.xml"
               This registers the loose files of the package without signing. Keep the "$layout"
               folder where it is. Remove it with Remove-AppxPackage.

            2. Sign it and install the package itself (sideload)
               The certificate subject must equal the package publisher: $publisher
                   ${'$'}cert = New-SelfSignedCertificate -Type Custom -Subject "$publisher" -KeyUsage DigitalSignature ``
                       -CertStoreLocation Cert:\CurrentUser\My ``
                       -TextExtension @("2.5.29.37={text}1.3.6.1.5.5.7.3.3", "2.5.29.19={text}")
                   signtool sign /fd SHA256 /sha1 ${'$'}cert.Thumbprint $msix
               Trust the certificate (Import-Certificate into Cert:\LocalMachine\TrustedPeople as an
               administrator), then double-click the package or run Add-AppxPackage -Path $msix

            3. Submit it to the Microsoft Store
               Reserve the app name in Partner Center and copy the Identity name and Publisher it
               shows into nativeDistributions.windows.msix { identityName, publisher }, then rebuild.
               Upload the unsigned package: the Store signs it. The Store reserves the fourth part of
               the version, so it stays 0.
        """.trimIndent() + "\n"
    }
}

internal object MsixImages {
    /** Scales [source] to fit the asset, centred on a transparent canvas. */
    fun write(source: java.awt.image.BufferedImage, asset: MsixAsset, target: File) {
        val canvas = java.awt.image.BufferedImage(asset.width, asset.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val side = minOf(asset.width, asset.height)
        val scale = minOf(side.toDouble() / source.width, side.toDouble() / source.height)
        val w = maxOf(1, (source.width * scale).toInt())
        val h = maxOf(1, (source.height * scale).toInt())
        val g = canvas.createGraphics()
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(source, (asset.width - w) / 2, (asset.height - h) / 2, w, h, null)
        } finally {
            g.dispose()
        }
        target.parentFile.mkdirs()
        check(ImageIO.write(canvas, "png", target)) { "no PNG writer for ${target.name}" }
    }
}

/**
 * Runs the Windows App Certification Kit, the check Partner Center runs on a submission, when
 * it is installed. The kit tests a signed package, so a copy is signed with a throwaway test
 * certificate for the run. A failed required test fails the task; without the kit the task says
 * so and ends successfully, so the same build runs on a machine that has no Windows SDK.
 */
@DisableCachingByDefault(because = "Runs the Windows App Certification Kit")
abstract class AbstractMsixCertifyTask : AbstractComposeDesktopTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val msix: RegularFileProperty = objects.fileProperty()

    @get:Input
    val publisher: Property<String> = objects.property()

    @get:org.gradle.api.tasks.OutputFile
    val report: RegularFileProperty = objects.fileProperty()

    @TaskAction
    fun run() {
        val appcert = listOfNotNull(System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"))
            .map { File(it, "Windows Kits/10/App Certification Kit/appcert.exe") }.firstOrNull { it.isFile }
        if (!System.getProperty("os.name").startsWith("Windows") || appcert == null) {
            logger.lifecycle(
                "Skipping the Windows App Certification Kit: it runs on Windows with the Windows SDK installed " +
                    "(appcert.exe in the Windows Kits folder)."
            )
            return
        }
        val signtool = findWindowsSdkTool("signtool") ?: error("signtool.exe was not found in the Windows SDK.")
        val work = project.layout.buildDirectory.dir("compose/tmp/$name").get().asFile.apply { deleteRecursively(); mkdirs() }
        val signed = work.resolve(msix.ioFile.name)
        msix.ioFile.copyTo(signed, overwrite = true)

        val thumbprint = StringBuilder()
        runExternalTool(
            tool = findTool("powershell", ".exe") ?: File("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe"),
            args = listOf(
                "-NoProfile", "-NonInteractive", "-Command",
                "(New-SelfSignedCertificate -Type Custom -Subject '${publisher.get().replace("'", "''")}' " +
                    "-KeyUsage DigitalSignature -FriendlyName 'compose msix certification' " +
                    "-CertStoreLocation Cert:\\CurrentUser\\My " +
                    "-TextExtension @('2.5.29.37={text}1.3.6.1.5.5.7.3.3','2.5.29.19={text}')).Thumbprint"
            ),
            processStdout = { thumbprint.append(it.trim()) },
        )
        runExternalTool(signtool, listOf("sign", "/fd", "SHA256", "/sha1", thumbprint.toString(), signed.absolutePath))

        val reportFile = report.ioFile.apply { parentFile.mkdirs(); delete() }
        runExternalTool(appcert, listOf("reset"), checkExitCodeIsNormal = false)
        runExternalTool(
            appcert, listOf("test", "-appxpackagepath", signed.absolutePath, "-reportoutputpath", reportFile.absolutePath),
            checkExitCodeIsNormal = false,
        )
        check(reportFile.isFile) { "The certification kit wrote no report." }
        val failures = MsixCertification.requiredFailures(reportFile.readText())
        check(failures.isEmpty()) {
            "The Windows App Certification Kit reports failed required tests: ${failures.joinToString()}. Report: ${reportFile.absolutePath}"
        }
        logger.lifecycle("The Windows App Certification Kit passed. Report: ${reportFile.absolutePath}")
    }
}

internal object MsixCertification {
    /** Names of the tests that failed and are not marked optional, from an `appcert` XML report. */
    fun requiredFailures(xml: String): List<String> {
        val test = Regex("<TEST\\s+([^>]*)>(.*?)</TEST>", RegexOption.DOT_MATCHES_ALL)
        return test.findAll(xml).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val optional = Regex("OPTIONAL=\"(\\w+)\"").find(attrs)?.groupValues?.get(1).equals("TRUE", ignoreCase = true)
            val result = Regex("<RESULT>\\s*(?:<!\\[CDATA\\[)?\\s*(\\w+)").find(match.groupValues[2])?.groupValues?.get(1)
            val name = Regex("NAME=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)
            name.takeIf { !optional && result.equals("FAIL", ignoreCase = true) }
        }.toList()
    }
}

// endregion
