/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.test.tests.integration

import org.jetbrains.compose.test.utils.GradlePluginTestBase
import org.jetbrains.compose.test.utils.checks
import org.junit.jupiter.api.Test

/**
 * The native image tasks can be stored in and reused from the configuration cache.
 *
 * A dry run stores the task graph like a real one, so nothing is compiled and no GraalVM is
 * needed. Storing failed with "Cannot run Project.afterEvaluate(Action) when the project is
 * already evaluated" while checkNativeImageNoAwt's onlyIf held the application's context, and
 * through it a KotlinJvmTarget (plugin 1.11.1-ext.3, found by Gemstone).
 */
class NativeImageConfigurationCacheTest : GradlePluginTestBase() {
    private fun project() = testProject(
        "application/nativeImageConfigurationCache",
        defaultTestEnvironment.copy(useGradleConfigurationCache = true)
    )

    @Test
    fun packageNativeImageIsStoredAndReused() = with(project()) {
        gradle("packageNativeImage", "--dry-run").checks {
            check.logContains("Configuration cache entry stored.")
        }
        gradle("packageNativeImage", "--dry-run").checks {
            check.logContains("Reusing configuration cache.")
        }
    }

    @Test
    fun runNativeImageAgentIsStoredAndReused() = with(project()) {
        gradle("runNativeImageAgent", "--dry-run").checks {
            check.logContains("Configuration cache entry stored.")
        }
        gradle("runNativeImageAgent", "--dry-run").checks {
            check.logContains("Reusing configuration cache.")
        }
    }
}
