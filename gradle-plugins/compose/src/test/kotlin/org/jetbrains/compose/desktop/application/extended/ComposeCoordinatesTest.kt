/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.extended

import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.compose.ComposeBuildConfig
import org.jetbrains.compose.ComposePlugin
import org.jetbrains.compose.compose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The aliases name compose-multiplatform-core-extended's libraries, except the components and
 * Compose HTML, which that fork does not build and which stay JetBrains'.
 */
@Suppress("DEPRECATION")
class ComposeCoordinatesTest {
    private val project = ProjectBuilder.builder().build()
    private val fork = ComposeBuildConfig.composeLibrariesGroup
    private val version = ComposeBuildConfig.composeVersion
    private val upstream = ComposeBuildConfig.composeUpstreamVersion

    @Test
    fun theLibrariesAreTheForks() {
        val dependencies = ComposePlugin.Dependencies(project)
        assertEquals("org.thisisthepy.compose", fork)
        assertEquals("$fork.ui:ui:$version", dependencies.ui)
        assertEquals("$fork.foundation:foundation:$version", dependencies.foundation)
        assertEquals("$fork.runtime:runtime:$version", dependencies.runtime)
        assertEquals(
            "$fork.material3:material3:${ComposeBuildConfig.composeMaterial3Version}",
            dependencies.material3,
        )
    }

    @Test
    fun aJetBrainsGroupPassedInBecomesTheForks() {
        assertEquals("$fork.ui:ui:$version", project.dependencies.compose("org.jetbrains.compose.ui:ui"))
    }

    @Test
    fun componentsStayJetBrainsAtTheUpstreamVersion() {
        assertEquals(
            "org.jetbrains.compose.components:components-resources:$upstream",
            project.dependencies.compose("org.jetbrains.compose.components:components-resources"),
        )
        assertEquals(version.replace(Regex("-ext\\.\\d+(-dev)?$"), ""), upstream)
    }
}
