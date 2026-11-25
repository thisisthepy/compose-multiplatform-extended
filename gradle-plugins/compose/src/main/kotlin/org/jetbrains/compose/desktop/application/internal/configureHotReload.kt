package org.jetbrains.compose.desktop.application.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.jetbrains.compose.ComposeBuildConfig
import org.jetbrains.compose.internal.KOTLIN_JVM_PLUGIN_ID
import org.jetbrains.compose.internal.KOTLIN_MPP_PLUGIN_ID
import org.jetbrains.compose.internal.Version
import org.jetbrains.compose.internal.mppExt
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

private fun Project.applyHotReload(kgp: KotlinBasePlugin) {
    // We add an explicit runtime dependency to the Compose Hot Reload Gradle plugin of a
    // specific preferred version (https://docs.gradle.org/current/userguide/dependency_versions.html),
    // so we are able to apply the plugin by id here at least of that preferred version
    // (because the plugin is present on the class-path),
    // until a user explicitly specifies their own version.
    project.pluginManager.apply("org.jetbrains.compose.hot-reload")
    useJetBrainsComposeForHotReloadDevTools()
}

// The configuration the hot reload plugin resolves its dev tools from. They are a window of their
// own, run in their own process, and built against JetBrains' Compose at one version.
internal const val HOT_RELOAD_DEV_TOOLS_CONFIGURATION = "composeHotReloadDevTools"

/**
 * The hot reload dev tools ask for `compose.desktop.currentOs`, which this plugin answers with the
 * fork's coordinate, and then pin it to the Compose version they were built with
 * (`strictly("1.11.1")` in 1.3.0-alpha02). The fork publishes no such version, so the
 * configuration did not resolve and every hot reload task failed (#78). The dev tools get
 * JetBrains' own modules at the version they ask for; the application's classpath keeps the
 * fork's.
 */
private fun Project.useJetBrainsComposeForHotReloadDevTools() {
    // applyHotReload runs once per JVM target of a multiplatform project; one rule is enough.
    val marker = "org.thisisthepy.compose.hotReloadDevToolsSubstituted"
    if (extensions.extraProperties.has(marker)) return
    extensions.extraProperties.set(marker, true)
    val forkGroup = ComposeBuildConfig.composeLibrariesGroup
    configurations.matching { it.name == HOT_RELOAD_DEV_TOOLS_CONFIGURATION }.configureEach { configuration ->
        configuration.resolutionStrategy.dependencySubstitution.all { substitution ->
            val requested = substitution.requested
            if (requested is ModuleComponentSelector && requested.group.startsWith("$forkGroup.")) {
                val group = "org.jetbrains.compose." + requested.group.removePrefix("$forkGroup.")
                substitution.useTarget(
                    "$group:${requested.module}:${requested.version}",
                    "the hot reload dev tools run JetBrains' Compose, not the application's",
                )
            }
        }
    }
}

fun Project.configureHotReload() {
    if (!ComposeProperties.disableHotReload(providers).get()) {
        plugins.withId(KOTLIN_MPP_PLUGIN_ID) { plugin ->
            mppExt.targets.withType(KotlinJvmTarget::class.java) {
                applyHotReload(plugin as KotlinBasePlugin)
            }
        }
        plugins.withId(KOTLIN_JVM_PLUGIN_ID) { plugin ->
            applyHotReload(plugin as KotlinBasePlugin)
        }
    }
}