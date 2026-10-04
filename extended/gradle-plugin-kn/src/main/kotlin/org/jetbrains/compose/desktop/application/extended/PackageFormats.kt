/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.extended

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.compose.desktop.application.internal.JvmApplicationContext
import org.jetbrains.compose.desktop.application.internal.composeDesktopTaskGroup
import org.jetbrains.compose.desktop.application.tasks.AbstractCreateDistributableTask
import org.jetbrains.compose.desktop.tasks.AbstractUnpackDefaultComposeApplicationResourcesTask
import org.jetbrains.compose.internal.utils.uppercaseFirstChar
import org.jetbrains.compose.desktop.application.dsl.AbstractDistributions
import org.jetbrains.compose.desktop.application.dsl.LinuxPlatformSettings
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.dsl.WindowsPlatformSettings

/** The formats this file packages. Upstream's jpackage-based formats are not in it. */
internal val TargetFormat.isBundleFormat: Boolean
    get() = this == TargetFormat.AppImageFile || this == TargetFormat.Flatpak || this == TargetFormat.Msix

/**
 * What the AppImage, Flatpak and MSIX tasks need to know about one application, whichever way it
 * was built. On the JVM path the payload is the app-image directory jpackage made. On the
 * Kotlin/Native path it is the directory the plugin laid the executable out in. Both are a
 * directory with the executable at [executablePath] and everything it needs beside it.
 */
internal class BundleContext(
    val project: Project,
    val distributions: AbstractDistributions,
    val linux: LinuxPlatformSettings,
    val windows: WindowsPlatformSettings,
    val group: String,
    /** `taskName("package", "Flatpak")`: how the host path names its tasks. */
    val taskName: (action: String, format: String) -> String,
    /** The linux payload (`bin/<name>` and what it needs) and the windows payload (`<name>.exe` and what it needs). */
    val linuxPayload: Provider<Directory>,
    val linuxExecutable: Provider<String>,
    val windowsPayload: Provider<Directory>,
    val windowsExecutable: Provider<String>,
    /** The task that makes the payloads. */
    val payloadTask: TaskProvider<*>,
    val outputDir: (format: TargetFormat) -> Provider<Directory>,
    /** The PNG the plugin ships as its default icon. */
    val defaultPngIcon: Provider<RegularFile>,
    /** The CPU of the executable: a Kotlin/Native target name, or `os.arch`. */
    val architecture: String,
    /** The task that unpacks the default icon, which the bundle tasks depend on. */
    val unpackDefaultResources: TaskProvider<*>,
) {
    val appName: Provider<String> get() = project.provider { distributions.packageName ?: project.name }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> nullable(fn: () -> T?): Provider<T> = project.provider(fn) as Provider<T>

    fun versionFor(osVersion: String?): Provider<String> = project.provider {
        osVersion
            ?: distributions.packageVersion
            ?: project.version.toString().takeIf { it != "unspecified" }
            ?: "1.0.0"
    }

    fun linuxName(): Provider<String> = project.provider { linux.packageName ?: distributions.packageName ?: project.name }

    private inline fun <reified T : Task> register(action: String, format: String, noinline configure: T.() -> Unit): TaskProvider<T> =
        project.tasks.register(taskName(action, format), T::class.java).also {
            it.configure { task ->
                task.group = group
                task.configure()
            }
        }

    private fun AbstractBundleTask.common(
        payload: Provider<Directory>, executable: Provider<String>, name: Provider<String>,
        version: Provider<String>, format: TargetFormat,
    ) {
        dependsOn(payloadTask)
        dependsOn(unpackDefaultResources)
        enabled = format.isCompatibleWithCurrentOS
        payloadDir.set(payload)
        executablePath.set(executable)
        appName.set(name)
        appVersion.set(version)
        appDescription.set(nullable { distributions.description })
        vendor.set(nullable { distributions.vendor })
        destinationDir.set(outputDir(format))
    }

    fun register(format: TargetFormat): TaskProvider<*> = when (format) {
        TargetFormat.AppImageFile -> registerAppImage()
        TargetFormat.Flatpak -> registerFlatpak()
        TargetFormat.Msix -> registerMsix()
        else -> error("$format is made by jpackage or by the Kotlin/Native packager, not by the bundle tasks")
    }

    private fun linuxIcon(): Provider<RegularFile> = linux.iconFile.orElse(defaultPngIcon)

    private fun registerAppImage(): TaskProvider<*> {
        val settings = linux.appImage
        return register<AbstractAppImageFileTask>("package", "AppImageFile") {
            common(linuxPayload, linuxExecutable, linuxName(), versionFor(linux.packageVersion), TargetFormat.AppImageFile)
            architecture.set(AppImageNames.architecture(this@BundleContext.architecture))
            iconFile.set(linuxIcon())
            appCategory.set(nullable { linux.appCategory ?: linux.menuGroup })
            mimeTypes.set(project.provider { linux.fileAssociations.map { it.mimeType } })
            appImageTool.set(nullable { settings.appImageTool })
            toolsDirectory.set(nullable { settings.toolsDirectory })
        }
    }

    private fun registerFlatpak(): TaskProvider<*> {
        val settings = linux.flatpak
        val manifest = register<AbstractFlatpakManifestTask>("create", "FlatpakManifest") {
            common(linuxPayload, linuxExecutable, linuxName(), versionFor(linux.packageVersion), TargetFormat.Flatpak)
            architecture.set(FlatpakNames.architecture(this@BundleContext.architecture))
            iconFile.set(linuxIcon())
            val name = linuxName()
            appId.set(project.provider { settings.appId ?: defaultFlatpakId(name.get()) })
            runtime.set(project.provider { settings.runtime })
            runtimeVersion.set(project.provider { settings.runtimeVersion })
            finishArgs.set(project.provider { settings.finishArgs.toList() })
            appCategory.set(nullable { linux.appCategory ?: linux.menuGroup })
            mimeTypes.set(project.provider { linux.fileAssociations.map { it.mimeType } })
            summary.set(nullable { settings.summary })
            developerName.set(nullable { settings.developerName })
            homepage.set(nullable { settings.homepage })
            license.set(project.provider { settings.license })
            metadataLicense.set(project.provider { settings.metadataLicense })
            releaseNotes.set(project.provider { settings.releaseNotes.toList() })
            payloadUrl.set(nullable { settings.payloadUrl })
            destinationDir.set(outputDir(TargetFormat.Flatpak).map { it.dir("manifest") })
        }
        return register<AbstractFlatpakBundleTask>("package", "Flatpak") {
            dependsOn(manifest)
            enabled = TargetFormat.Flatpak.isCompatibleWithCurrentOS
            manifestDir.set(manifest.flatMap { it.destinationDir })
            appId.set(manifest.flatMap { it.appId })
            appName.set(manifest.flatMap { it.appName })
            appVersion.set(manifest.flatMap { it.appVersion })
            architecture.set(manifest.flatMap { it.architecture })
            runtimeRemote.set(project.provider { settings.runtimeRemote })
            flatpakBuilder.set(nullable { settings.flatpakBuilder })
            flatpak.set(nullable { settings.flatpak })
            destinationDir.set(outputDir(TargetFormat.Flatpak).map { it.dir("bundle") })
        }
    }

    private fun registerMsix(): TaskProvider<*> {
        val settings = windows.msix
        val name = appName
        val msix = register<AbstractMsixTask>("package", "Msix") {
            common(windowsPayload, windowsExecutable, name, versionFor(windows.packageVersion), TargetFormat.Msix)
            architecture.set(MsixManifest.architecture(this@BundleContext.architecture))
            iconFile.set(msixLogo(settings))
            identityName.set(project.provider { settings.identityName ?: MsixManifest.identityName(distributions.vendor, name.get()) })
            publisher.set(project.provider { settings.publisher ?: "CN=${distributions.vendor ?: name.get()}" })
            publisherDisplayName.set(project.provider { settings.publisherDisplayName ?: distributions.vendor ?: name.get() })
            displayName.set(project.provider { settings.displayName ?: name.get() })
            minVersion.set(project.provider { settings.minVersion })
            maxVersionTested.set(project.provider { settings.maxVersionTested })
            languages.set(project.provider { settings.languages.toList() })
            capabilities.set(project.provider { settings.capabilities.toList() })
            makeAppx.set(nullable { settings.makeAppx })
        }
        register<AbstractMsixCertifyTask>("certify", "Msix") {
            dependsOn(msix)
            enabled = TargetFormat.Msix.isCompatibleWithCurrentOS
            this.msix.set(msix.flatMap { task ->
                task.destinationDir.map { dir -> dir.file(task.outputFileName) }
            })
            publisher.set(msix.flatMap { it.publisher })
            report.set(project.layout.buildDirectory.file("compose/reports/msix-wack-report.xml"))
        }
        return msix
    }

    /** A PNG for the tile images: the MSIX logo, then the Windows icon if it is a PNG, then the Linux icon. */
    private fun msixLogo(settings: MsixSettings): Provider<RegularFile> {
        val configured = project.layout.file(nullable { settings.logoFile })
        val windowsPng = windows.iconFile.map { if (it.asFile.extension.equals("png", true)) it else null }
        return configured.orElse(windowsPng).orElse(linuxIcon())
    }

    companion object {
        /** `org.example.<name>`, with the name cut down to what an id element may hold. */
        fun defaultFlatpakId(name: String): String {
            val element = name.lowercase().filter { it.isLetterOrDigit() && it.code < 128 }
                .let { if (it.firstOrNull()?.isLetter() == true) it else "app$it" }
            return "org.example.$element"
        }
    }
}

/**
 * The JVM path: the payload is the app-image directory `createDistributable` made with jpackage,
 * so the bundle holds the application's runtime as well as its launcher.
 */
internal fun JvmApplicationContext.configureBundleFormat(
    format: TargetFormat,
    createDistributable: TaskProvider<AbstractCreateDistributableTask>,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>,
): TaskProvider<*> {
    val distributions = app.nativeDistributions
    val classifier = buildType.classifier.uppercaseFirstChar()
    val appDir = createDistributable.flatMap { it.destinationDir }
    val name = packageNameProvider
    val context = BundleContext(
        project = project,
        distributions = distributions,
        linux = distributions.linux,
        windows = distributions.windows,
        group = composeDesktopTaskGroup,
        taskName = { action, obj -> "$action$classifier$obj" },
        linuxPayload = appDir.zip(name) { dir, n -> dir.dir(n) },
        linuxExecutable = name.map { "bin/$it" },
        windowsPayload = appDir.zip(name) { dir, n -> dir.dir(n) },
        windowsExecutable = name.map { "$it.exe" },
        payloadTask = createDistributable,
        outputDir = { distributions.outputBaseDir.dir("$appDirName/${it.outputDirName}") },
        defaultPngIcon = unpackDefaultResources.flatMap { it.resources.linuxIcon },
        architecture = System.getProperty("os.arch"),
        unpackDefaultResources = unpackDefaultResources,
    )
    return context.register(format)
}

/**
 * The Kotlin/Native path: the payload is the directory the plugin laid the executable out in.
 * [linuxAppDir] is the `<name>.AppDir/usr` directory of the Linux layout and [windowsDir] the
 * `<name>` folder of the Windows layout.
 */
internal fun NativePackagingContext.configureBundleFormats(
    layoutTask: TaskProvider<*>,
    linuxUsr: Provider<Directory>?,
    linuxExecutable: Provider<String>?,
    windowsDir: Provider<Directory>?,
    windowsExecutable: Provider<String>?,
): List<TaskProvider<*>> {
    val formats = distributions.targetFormats.filter { it.isBundleFormat && it.isCompatibleWithCurrentOS }
    if (formats.isEmpty()) return emptyList()
    val nothing = project.objects.directoryProperty()
    val noExecutable = project.provider { "" }
    val context = BundleContext(
        project = project,
        distributions = distributions,
        linux = distributions.linux,
        windows = distributions.windows,
        group = "compose desktop (native)",
        taskName = { action, obj -> taskName("$action${obj}Native") },
        linuxPayload = linuxUsr ?: nothing,
        linuxExecutable = linuxExecutable ?: noExecutable,
        windowsPayload = windowsDir ?: nothing,
        windowsExecutable = windowsExecutable ?: noExecutable,
        payloadTask = layoutTask,
        outputDir = { outputDir(it.outputDirName) },
        defaultPngIcon = unpackDefaultResources.flatMap { it.resources.linuxIcon },
        architecture = binary.target.name,
        unpackDefaultResources = unpackDefaultResources,
    )
    return formats.map { context.register(it) }
}
