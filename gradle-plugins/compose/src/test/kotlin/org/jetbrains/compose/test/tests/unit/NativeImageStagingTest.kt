/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.test.tests.unit

import org.gradle.api.internal.TaskInternal
import org.gradle.api.tasks.Sync
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.compose.desktop.application.internal.JvmApplicationData
import org.jetbrains.compose.desktop.application.internal.configureNativeImageStaging
import org.jetbrains.compose.desktop.application.internal.nativeImageAgentCommandLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Files staged beside the native image executable, and the flags the tracing agent run shares
 * with the image.
 */
class NativeImageStagingTest {
    @TempDir
    lateinit var root: File

    private fun project() = ProjectBuilder.builder().withProjectDir(root.resolve("project")).build()

    @Test
    fun nothingIsStagedUnlessTheApplicationAsks() {
        val project = project()
        assertTrue(JvmApplicationData(project.objects, project.providers).nativeImage.besideExecutableSpecs.isEmpty())

        val image = root.resolve("image").apply { mkdirs(); resolve("app").writeText("executable") }
        val sync = project.tasks.register("stageNativeImage", Sync::class.java).get()
        configureNativeImageStaging(
            sync,
            project.layout.dir(project.provider { image }),
            emptyList(),
            project.layout.buildDirectory.dir("staged"),
        )
        assertFalse((sync as TaskInternal).onlyIf.isSatisfiedBy(sync))
    }

    @Test
    fun theExecutableAndThePrefixAreStagedTogether() {
        val project = project()
        val image = root.resolve("image").apply { mkdirs() }
        image.resolve("app").apply { writeText("executable"); setExecutable(true) }
        val prefix = project.projectDir.resolve("prefix/lib").apply { mkdirs() }
        prefix.resolve("marker.txt").writeText("staged")

        val data = JvmApplicationData(project.objects, project.providers)
        data.nativeImage.besideExecutable { spec -> spec.from("prefix") { it.into("python") } }
        assertEquals(1, data.nativeImage.besideExecutableSpecs.size)

        val sync = project.tasks.register("stageNativeImage", Sync::class.java).get()
        configureNativeImageStaging(
            sync,
            project.layout.dir(project.provider { image }),
            data.nativeImage.besideExecutableSpecs,
            project.layout.buildDirectory.dir("staged"),
        )
        assertTrue((sync as TaskInternal).onlyIf.isSatisfiedBy(sync))
        sync.actions.forEach { it.execute(sync) }

        val staged = project.layout.buildDirectory.dir("staged").get().asFile
        assertEquals("executable", staged.resolve("app").readText())
        assertTrue(staged.resolve("app").canExecute(), "the executable keeps its mode")
        assertEquals("staged", staged.resolve("python/lib/marker.txt").readText())
        assertEquals(setOf("app", "python"), staged.list()!!.toSet())
        // packageNativeImage's own output is untouched: still the one executable.
        assertEquals(listOf("app"), image.list()!!.toList())
    }

    @Test
    fun theAgentRunTakesTheApplicationsJvmArgs() {
        val java = root.resolve("graalvm/bin/java")
        val metadata = root.resolve("metadata")
        val jar = root.resolve("app.jar")
        assertEquals(
            listOf(
                java.absolutePath,
                "-agentlib:native-image-agent=config-merge-dir=${metadata.absolutePath}",
                "--enable-native-access=ALL-UNNAMED",
                "-cp", jar.absolutePath,
                "app.MainKt",
                "--flag",
            ),
            nativeImageAgentCommandLine(java, metadata, listOf(jar), "app.MainKt", listOf("--enable-native-access=ALL-UNNAMED"), listOf("--flag")),
        )
    }
}
