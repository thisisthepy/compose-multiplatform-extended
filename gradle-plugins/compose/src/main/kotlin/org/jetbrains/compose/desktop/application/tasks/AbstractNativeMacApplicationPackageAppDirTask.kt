/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.tasks

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import org.gradle.api.tasks.Optional
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.compose.desktop.application.dsl.FileAssociation
import org.jetbrains.compose.desktop.application.extended.NativeInfoPlist
import org.jetbrains.compose.desktop.application.extended.NativeInfoPlistInput
import org.jetbrains.compose.internal.utils.ioFile
import org.jetbrains.compose.internal.utils.property
import java.io.File

private const val KOTLIN_NATIVE_MIN_SUPPORTED_MAC_OS = "10.13"

@DisableCachingByDefault(because = "Uses platform-specific native tools whose output depends on local system")
abstract class AbstractNativeMacApplicationPackageAppDirTask : AbstractNativeMacApplicationPackageTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val executable: RegularFileProperty = objects.fileProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val iconFile: RegularFileProperty = objects.fileProperty()

    @get:Input
    val bundleID: Property<String> = objects.property<String>().value(packageName)

    @get:Input
    @get:Optional
    val appCategory: Property<String> = objects.property()

    @get:Input
    @get:Optional
    val copyright: Property<String> = objects.property()

    @get:Input
    @get:Optional
    val minimumSystemVersion: Property<String> = objects.property()

    /** Extra top-level keys for Info.plist, as raw XML. */
    @get:Input
    @get:Optional
    val extraInfoPlistKeysRawXml: Property<String> = objects.property()

    @get:Input
    @get:Optional
    internal val fileAssociations: SetProperty<FileAssociation> = objects.setProperty(FileAssociation::class.java)

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val composeResourcesDirs: ConfigurableFileCollection = objects.fileCollection()

    override fun createPackage(destinationDir: File, workingDir: File) {
        val packageName = packageName.get()
        val appDir = destinationDir.resolve("$packageName.app").apply { mkdirs() }
        val contentsDir = appDir.resolve("Contents").apply { mkdirs() }
        val macOSDir = contentsDir.resolve("MacOS").apply { mkdirs() }
        val appResourcesDir = contentsDir.resolve("Resources").apply { mkdirs() }

        val appExecutableFile = macOSDir.resolve(packageName)
        executable.ioFile.copyTo(appExecutableFile)
        appExecutableFile.setExecutable(true)

        val appIconFile = appResourcesDir.resolve("$packageName.icns")
        iconFile.ioFile.copyTo(appIconFile)

        NativeInfoPlist.write(infoPlistInput(appExecutableFile.name), contentsDir.resolve("Info.plist"))

        if (!composeResourcesDirs.isEmpty) {
            fileOperations.copy { copySpec ->
                copySpec.from(composeResourcesDirs)
                copySpec.into(appResourcesDir.resolve("compose-resources").apply { mkdirs() })
            }
        }
    }

    private fun infoPlistInput(executableName: String): NativeInfoPlistInput {
        val packageVersion = packageVersion.get()
        return NativeInfoPlistInput(
            executableName = executableName,
            iconFileName = iconFile.ioFile.name,
            bundleID = bundleID.get(),
            version = packageVersion,
            minimumSystemVersion = minimumSystemVersion.getOrElse(KOTLIN_NATIVE_MIN_SUPPORTED_MAC_OS),
            appCategory = appCategory.orNull,
            copyright = copyright.orNull,
            extraKeysRawXml = extraInfoPlistKeysRawXml.orNull,
            fileAssociations = fileAssociations.getOrElse(emptySet()).toList(),
        )
    }
}
