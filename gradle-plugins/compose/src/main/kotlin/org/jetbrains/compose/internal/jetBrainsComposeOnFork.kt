/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.jetbrains.compose.ComposeBuildConfig
import org.jetbrains.compose.composeMaterial3Version
import org.jetbrains.compose.composeVersion
import org.jetbrains.compose.desktop.application.internal.HOT_RELOAD_DEV_TOOLS_CONFIGURATION

/** `false` keeps JetBrains' Compose modules as they are asked for (see [useForkForJetBrainsCompose]). */
internal const val SUBSTITUTE_JETBRAINS_COMPOSE = "org.thisisthepy.compose.substituteJetBrainsCompose"

private const val JETBRAINS_COMPOSE_GROUP = "org.jetbrains.compose"

// The groups this fork publishes under org.thisisthepy.compose: only these are replaced. The
// rest of org.jetbrains.compose (components, Compose HTML, hot reload and whatever JetBrains adds)
// is JetBrains' alone, and so are the Material icons, which stopped at 1.7.3.
private val forkPublishedSubgroups = setOf(
    "runtime", "ui", "foundation", "animation", "material", "material3", "desktop",
    "annotation-internal", "collection-internal",
)
private val jetBrainsOnlyModules = setOf(
    "$JETBRAINS_COMPOSE_GROUP.material:material-icons-core",
    "$JETBRAINS_COMPOSE_GROUP.material:material-icons-extended",
)

/**
 * Libraries built against JetBrains' Compose (JetBrains' lifecycle, navigation and most of the
 * ecosystem) name `org.jetbrains.compose.<x>:<y>`. This fork publishes the same modules as
 * `org.thisisthepy.compose.<x>:<y>`, which Gradle cannot match to JetBrains', so such a library
 * put a second Compose on the classpath next to the fork's, at an older version (#78).
 *
 * Every such request is resolved as the fork's module at the version this plugin's aliases use,
 * as `compose("org.jetbrains.compose.ui:ui")` already is. Only the groups the fork publishes are
 * replaced; modules only JetBrains publishes are left alone, and so are the hot reload dev
 * tools, which run JetBrains' own Compose.
 * `org.thisisthepy.compose.substituteJetBrainsCompose=false` turns this off.
 */
internal fun Project.useForkForJetBrainsCompose() {
    if (providers.gradleProperty(SUBSTITUTE_JETBRAINS_COMPOSE).orNull?.trim()?.equals("false", ignoreCase = true) == true) return
    val forkGroup = ComposeBuildConfig.composeLibrariesGroup
    val version = composeVersion
    val material3Version = composeMaterial3Version
    configurations.configureEach { configuration ->
        if (configuration.name == HOT_RELOAD_DEV_TOOLS_CONFIGURATION) return@configureEach
        configuration.resolutionStrategy.dependencySubstitution.all { substitution ->
            val requested = substitution.requested as? ModuleComponentSelector ?: return@all
            val group = requested.group
            if (!group.startsWith("$JETBRAINS_COMPOSE_GROUP.")) return@all
            val subgroup = group.removePrefix("$JETBRAINS_COMPOSE_GROUP.")
            if (subgroup !in forkPublishedSubgroups || "$group:${requested.module}" in jetBrainsOnlyModules) return@all
            val target = if (subgroup == "material3") material3Version else version
            substitution.useTarget(
                "$forkGroup.$subgroup:${requested.module}:$target",
                "this fork's Compose replaces JetBrains' ($SUBSTITUTE_JETBRAINS_COMPOSE=false keeps it)",
            )
        }
    }
}
