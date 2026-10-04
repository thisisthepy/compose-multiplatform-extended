/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.extended

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import java.io.File
import org.jetbrains.compose.desktop.application.dsl.NativeApplication
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractNativeMacApplicationPackageAppDirTask
import org.jetbrains.compose.desktop.application.tasks.AbstractNativeMacApplicationPackageDmgTask
import org.jetbrains.compose.desktop.tasks.AbstractUnpackDefaultComposeApplicationResourcesTask
import org.jetbrains.compose.internal.utils.OS
import org.jetbrains.compose.internal.utils.currentOS
import org.jetbrains.compose.internal.utils.joinLowerCamelCase
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBinary
import org.jetbrains.kotlin.konan.target.Family
import java.util.Calendar

/**
 * Declares Kotlin/Native desktop targets of any family: macOS, Linux and Windows (mingw).
 * `targets()` of the upstream class takes macOS only, and stays as it is.
 */
fun NativeApplication.desktopTargets(vararg targets: KotlinTarget) {
    val unsupported = targets.filter {
        it !is KotlinNativeTarget || it.konanTarget.family !in setOf(Family.OSX, Family.LINUX, Family.MINGW)
    }
    check(unsupported.isEmpty()) {
        "compose.nativeApplication.desktopTargets supports Kotlin/Native macOS, Linux and mingw targets; " +
            "not supported: ${unsupported.joinToString { it.name }}"
    }
    targets.forEach { if (it !in _targets) _targets.add(it as KotlinNativeTarget) }
}

/** Everything a per-OS packager needs about one executable binary. */
internal class NativePackagingContext(
    val project: Project,
    val app: NativeApplication,
    val binary: NativeBinary,
    val unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>,
) {
    val distributions get() = app.distributions

    fun taskName(action: String): String =
        joinLowerCamelCase(action, binary.buildType.name.lowercase(), binary.target.name)

    fun outputDir(format: String): Provider<Directory> = distributions.outputBaseDir.dir(
        "${app.name}/native-${binary.target.name}-${binary.buildType.name.lowercase()}-$format"
    )

    fun packageName(platformName: String?): Provider<String> =
        project.provider { platformName ?: distributions.packageName ?: project.name }

    fun packageVersion(platformVersion: String?): Provider<String> = project.provider {
        platformVersion
            ?: distributions.packageVersion
            ?: project.version.toString().takeIf { it != "unspecified" }
            ?: "1.0.0"
    }

    val copyright: Provider<String> = project.provider {
        distributions.copyright ?: "Copyright (C) ${Calendar.getInstance().get(Calendar.YEAR)}"
    }

    val executable = project.layout.file(binary.linkTaskProvider.map { it.binary.outputFile })

    val composeResources = (binary.compilation.associatedCompilations + binary.compilation).flatMap { compilation ->
        compilation.allKotlinSourceSets.map { it.resources }
    }

    inline fun <reified T : Task> register(action: String, noinline configure: T.() -> Unit): TaskProvider<T> =
        project.tasks.register(taskName(action), T::class.java).also {
            it.configure { task ->
                task.group = "compose desktop (native)"
                task.configure()
            }
        }
}

/** Packaging for one operating system family. */
internal interface NativeOsPackager {
    val os: OS

    /** Adds packaging and run tasks for one binary. [createDistributable] makes the runnable layout. */
    fun configure(ctx: NativePackagingContext)

    companion object {
        fun forFamily(family: Family): NativeOsPackager? = when (family) {
            Family.OSX -> MacNativePackager
            Family.LINUX -> LinuxNativePackager
            Family.MINGW -> WindowsNativePackager
            else -> null
        }
    }
}

/** Run tasks (`runDistributableNative...`, `runNative...`) and the `packageKotlinNative` aggregate. */
internal fun registerNativeRunTasks(
    ctx: NativePackagingContext,
    runnableInDistribution: Provider<java.io.File>,
) {
    ctx.register<AbstractNativeRunTask>("runNative") {
        dependsOn(ctx.binary.linkTaskProvider)
        executable.set(ctx.executable)
    }
    ctx.register<AbstractNativeRunTask>("runDistributableNative") {
        dependsOn(ctx.taskName("createDistributableNative"))
        executable.set(ctx.project.layout.file(runnableInDistribution))
    }
}

internal fun registerPackageKotlinNative(project: Project, packageTasks: List<TaskProvider<*>>) {
    if (packageTasks.isEmpty()) return
    val aggregate = project.tasks.names.contains("packageKotlinNative")
    if (aggregate) {
        project.tasks.named("packageKotlinNative").configure { it.dependsOn(packageTasks) }
    } else {
        project.tasks.register("packageKotlinNative") {
            it.group = "compose desktop (native)"
            it.description = "Packages the Kotlin/Native executables of this OS."
            it.dependsOn(packageTasks)
        }
    }
}

// region macOS

internal object MacNativePackager : NativeOsPackager {
    override val os = OS.MacOS

    /** Called after upstream created `createDistributableNative` and `packageDmgNative`. */
    override fun configure(ctx: NativePackagingContext) {
        val settings = ctx.distributions.macOS
        val createName = ctx.taskName("createDistributableNative")
        val create = ctx.project.tasks.named(createName, AbstractNativeMacApplicationPackageAppDirTask::class.java)
        create.configure { task ->
            task.bundleID.set(ctx.project.provider { settings.bundleID ?: "org.example.${task.packageName.get()}" })
            task.minimumSystemVersion.set(ctx.project.provider { settings.minimumSystemVersion })
            task.extraInfoPlistKeysRawXml.set(ctx.project.provider { settings.infoPlistSettings.extraKeysRawXml })
            task.fileAssociations.set(ctx.project.provider { settings.fileAssociations })
        }

        val sign = ctx.register<AbstractNativeMacSignTask>("signDistributableNative") {
            dependsOn(create)
            appDir.set(create.flatMap { it.destinationDir })
            packageName.set(create.flatMap { it.packageName })
            bundleID.set(create.flatMap { it.bundleID })
            signingSettings = settings.signing
            entitlements.set(ctx.project.provider { settings.entitlementsFile.orNull?.asFile?.absolutePath })
        }

        val packages = mutableListOf<TaskProvider<*>>()
        val dmgName = ctx.taskName("packageDmgNative")
        if (ctx.project.tasks.names.contains(dmgName)) {
            val dmg = ctx.project.tasks.named(dmgName, AbstractNativeMacApplicationPackageDmgTask::class.java)
            dmg.configure { task ->
                task.dependsOn(sign)
                task.doLast {
                    val dir = task.destinationDir.get().asFile
                    NativeChecksums.write(dir, dir.listFiles().orEmpty().filter { it.name.endsWith(".dmg") })
                }
            }
            val notarize = ctx.register<AbstractNativeMacNotarizeTask>("notarizeDmgNative") {
                dependsOn(dmg)
                packageFiles.from(dmg.flatMap { it.destinationDir }.map { dir ->
                    dir.asFileTree.matching { it.include("*.dmg") }
                })
                notarizationSettings = settings.notarization
            }
            packages += notarize
        } else {
            packages += sign
        }

        registerNativeRunTasks(ctx, create.flatMap { it.destinationDir }.zip(create.flatMap { it.packageName }) { dir, name ->
            dir.asFile.resolve("$name.app/Contents/MacOS/$name")
        })
        if (ctx.binary.target.konanTarget.family == Family.OSX) registerUniversal(ctx, create)
        registerPackageKotlinNative(ctx.project, packages)
    }

    /** One `lipoNative<Build>` per build type, once both macOS architectures are declared. */
    private fun registerUniversal(ctx: NativePackagingContext, create: TaskProvider<*>) {
        if (!ctx.distributions.macOS.universalBinary) return
        val arm = ctx.app._targets.firstOrNull { it.konanTarget.name == "macos_arm64" } ?: return
        val x64 = ctx.app._targets.firstOrNull { it.konanTarget.name == "macos_x64" } ?: return
        // Registered once, by the arm64 binary.
        if (ctx.binary.target != arm) return
        val buildType = ctx.binary.buildType
        val binaries = listOf(arm, x64).map { target ->
            target.binaries.first { it.buildType == buildType && it.outputKind == ctx.binary.outputKind }
        }
        val name = joinLowerCamelCase("lipoNative", buildType.name.lowercase())
        if (ctx.project.tasks.names.contains(name)) return
        ctx.project.tasks.register(name, AbstractNativeMacLipoTask::class.java) { task ->
            task.group = "compose desktop (native)"
            task.dependsOn(binaries.map { it.linkTaskProvider })
            task.executables.from(binaries.map { b -> b.linkTaskProvider.map { it.binary.outputFile } })
            task.universalExecutable.set(
                ctx.distributions.outputBaseDir.file("${ctx.app.name}/native-universal-${buildType.name.lowercase()}/${ctx.project.name}")
            )
        }
    }
}

// endregion

// region Linux

internal object LinuxNativePackager : NativeOsPackager {
    override val os = OS.Linux

    override fun configure(ctx: NativePackagingContext) {
        val settings = ctx.distributions.linux
        fun AbstractNativeLinuxPackageTask.common() {
            packageName.set(ctx.packageName(settings.packageName))
            packageVersion.set(ctx.packageVersion(settings.debPackageVersion ?: settings.packageVersion))
            executable.set(ctx.executable)
            val default = ctx.unpackDefaultResources.flatMap { it.resources.linuxIcon }
            iconFile.set(settings.iconFile.orElse(default))
            dependsOn(ctx.unpackDefaultResources)
            dependsOn(ctx.binary.linkTaskProvider)
            appDescription.set(ctx.project.provider { ctx.distributions.description })
            appCategory.set(ctx.project.provider { settings.appCategory ?: settings.menuGroup })
            vendor.set(ctx.project.provider { ctx.distributions.vendor })
            maintainer.set(ctx.project.provider { settings.debMaintainer })
            architecture.set(DebControl.architecture(ctx.binary.target.name))
            fileAssociationMimeTypes.set(ctx.project.provider { settings.fileAssociations.map { it.mimeType } })
            composeResourcesDirs.setFrom(ctx.composeResources)
        }

        val appDir = ctx.register<AbstractNativeLinuxAppDirTask>("createDistributableNative") {
            common()
            destinationDir.set(ctx.outputDir("app-image"))
        }
        val appImage = ctx.register<AbstractNativeLinuxAppImageTask>("packageAppImageNative") {
            common()
            dependsOn(appDir)
            this.appDir.set(appDir.flatMap { it.destinationDir })
            destinationDir.set(ctx.outputDir("appimage"))
        }
        val packages = mutableListOf<TaskProvider<*>>(appImage)
        if (TargetFormat.Deb in ctx.distributions.targetFormats) {
            packages += ctx.register<AbstractNativeLinuxDebTask>("packageDebNative") {
                common()
                destinationDir.set(ctx.outputDir("deb"))
            }
        }
        registerNativeRunTasks(ctx, appDir.flatMap { it.destinationDir }.zip(appDir.flatMap { it.packageName }) { dir, name ->
            dir.asFile.resolve("$name.AppDir/usr/bin/$name")
        })
        registerPackageKotlinNative(ctx.project, packages)
    }
}

// endregion

// region Windows

internal object WindowsNativePackager : NativeOsPackager {
    override val os = OS.Windows

    override fun configure(ctx: NativePackagingContext) {
        val settings = ctx.distributions.windows
        val appDir = ctx.register<AbstractNativeWindowsAppDirTask>("createDistributableNative") {
            packageName.set(ctx.packageName(null))
            packageVersion.set(ctx.packageVersion(settings.exePackageVersion ?: settings.packageVersion))
            executable.set(ctx.executable)
            iconFile.set(settings.iconFile.orElse(ctx.unpackDefaultResources.flatMap { it.resources.windowsIcon }))
            dependsOn(ctx.unpackDefaultResources)
            dependsOn(ctx.binary.linkTaskProvider)
            composeResourcesDirs.setFrom(ctx.composeResources)
            destinationDir.set(ctx.outputDir("app-image"))
        }
        val packages = mutableListOf<TaskProvider<*>>()
        if (TargetFormat.Exe in ctx.distributions.targetFormats) {
            packages += ctx.register<AbstractNativeWindowsZipTask>("packageExeNative") {
                dependsOn(appDir)
                packageName.set(appDir.flatMap { it.packageName })
                packageVersion.set(ctx.packageVersion(settings.exePackageVersion ?: settings.packageVersion))
                this.appDir.set(appDir.flatMap { it.destinationDir })
                destinationDir.set(ctx.outputDir("exe"))
            }
        }
        if (TargetFormat.Msi in ctx.distributions.targetFormats) {
            packages += ctx.register<AbstractNativeWindowsMsiTask>("packageMsiNative") {
                dependsOn(appDir)
                packageName.set(appDir.flatMap { it.packageName })
                packageVersion.set(ctx.packageVersion(settings.msiPackageVersion ?: settings.packageVersion))
                this.appDir.set(appDir.flatMap { it.destinationDir })
                vendor.set(ctx.project.provider { ctx.distributions.vendor })
                upgradeUuid.set(ctx.project.provider { settings.upgradeUuid })
                perUserInstall.set(settings.perUserInstall)
                shortcut.set(settings.shortcut)
                destinationDir.set(ctx.outputDir("msi"))
            }
        }
        if (packages.isEmpty()) packages += appDir
        registerNativeRunTasks(ctx, appDir.flatMap { it.destinationDir }.zip(appDir.flatMap { it.packageName }) { dir, name ->
            dir.asFile.resolve("$name/$name.exe")
        })
        registerPackageKotlinNative(ctx.project, packages)
    }
}

// endregion

internal val nativeHostOS: OS get() = currentOS
