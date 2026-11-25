/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.compose.ComposeBuildConfig

private const val JETBRAINS_SKIKO_GROUP = "org.jetbrains.skiko"
private const val SKIKO_AWT = "skiko-awt"

/** The fork's skiko-awt coordinate: `org.thisisthepy.compose.skiko:skiko-awt`. */
internal val forkSkikoAwtGroup get() = "${ComposeBuildConfig.composeLibrariesGroup}.skiko"

/**
 * One skiko-awt on a classpath: this fork's.
 *
 * The fork republishes skiko's skiko-awt, with its AWT switch, as
 * `org.thisisthepy.compose.skiko:skiko-awt:<fork version>`, and its desktop modules name that one.
 * The natives stay JetBrains' (`org.jetbrains.skiko:skiko-awt-runtime-<target>`), and each of them
 * depends on `org.jetbrains.skiko:skiko-awt` again. Gradle saw two modules, kept both, and every
 * desktop application carried the same classes twice: ProGuard and the uber jar stopped on the
 * duplicate entries, and which copy a JVM loaded came down to classpath order (#78).
 *
 * The fork's module is given JetBrains' capability too, so the two conflict, and the conflict is
 * resolved to the fork's, whatever version either side names.
 */
internal fun Project.keepOneSkikoAwt() {
    val forkGroup = forkSkikoAwtGroup
    dependencies.components.withModule("$forkGroup:$SKIKO_AWT") { details ->
        val version = details.id.version
        details.allVariants { variant ->
            variant.withCapabilities { capabilities ->
                capabilities.addCapability(forkGroup, SKIKO_AWT, version)
                capabilities.addCapability(JETBRAINS_SKIKO_GROUP, SKIKO_AWT, version)
            }
        }
    }
    configurations.configureEach { configuration ->
        configuration.resolutionStrategy.capabilitiesResolution.withCapability(JETBRAINS_SKIKO_GROUP, SKIKO_AWT) { resolution ->
            val fork = resolution.candidates.firstOrNull { candidate ->
                (candidate.id as? ModuleComponentIdentifier)?.group == forkGroup
            }
            if (fork != null) {
                resolution.select(fork)
                resolution.because("this fork's skiko-awt replaces skiko's; one copy on a classpath")
            }
        }
    }
}
