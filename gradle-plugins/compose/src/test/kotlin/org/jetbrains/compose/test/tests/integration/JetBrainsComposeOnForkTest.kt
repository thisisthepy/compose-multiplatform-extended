/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.test.tests.integration

import org.jetbrains.compose.test.utils.GradlePluginTestBase
import org.jetbrains.compose.test.utils.TestProject
import org.jetbrains.compose.test.utils.checks
import org.jetbrains.compose.test.utils.modify
import org.junit.jupiter.api.Test

/**
 * A library built against JetBrains' Compose runs on this fork's: its org.jetbrains.compose
 * modules are resolved as the fork's at the plugin's version. Without that, JetBrains' lifecycle
 * (built against org.jetbrains.compose.ui 1.6.11) put a second Compose UI on the classpath next
 * to the fork's (compose-multiplatform-extended#78).
 */
class JetBrainsComposeOnForkTest : GradlePluginTestBase() {
    // JetBrains' lifecycle-viewmodel-compose 2.8.3 names org.jetbrains.compose.ui:ui 1.6.11, and
    // material-icons-extended is a module JetBrains publishes and the fork does not.
    private fun TestProject.addJetBrainsComposeLibraries() {
        file("build.gradle").modify {
            it.replace(
                "implementation compose.desktop.currentOs",
                "implementation compose.desktop.currentOs\n" +
                    "    implementation \"org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3\"\n" +
                    "    implementation \"org.jetbrains.compose.material:material-icons-extended:1.7.3\"",
            )
        }
    }

    private val forkVersion get() = defaultTestEnvironment.composeVersion

    @Test
    fun jetBrainsComposeModulesResolveToTheForks() = with(testProject("application/jvm")) {
        addJetBrainsComposeLibraries()
        gradle("dependencies", "--configuration", "runtimeClasspath").checks {
            check.logContains("org.jetbrains.compose.ui:ui:1.6.11 -> org.thisisthepy.compose.ui:ui:$forkVersion")
            check.logContains("org.jetbrains.compose.runtime:runtime:1.6.11 -> org.thisisthepy.compose.runtime:runtime:$forkVersion")
            // What only JetBrains publishes stays JetBrains'.
            check.logContains("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            check.logDoesntContain("material-icons-extended:1.7.3 -> org.thisisthepy")
        }
    }

    @Test
    fun theSubstitutionCanBeTurnedOff() = with(testProject("application/jvm")) {
        addJetBrainsComposeLibraries()
        file("gradle.properties").appendText("\norg.thisisthepy.compose.substituteJetBrainsCompose=false\n")
        gradle("dependencies", "--configuration", "runtimeClasspath").checks {
            check.logContains("org.jetbrains.compose.ui:ui:1.6.11")
            check.logDoesntContain("org.jetbrains.compose.ui:ui:1.6.11 -> org.thisisthepy.compose.ui:ui")
        }
    }
}
