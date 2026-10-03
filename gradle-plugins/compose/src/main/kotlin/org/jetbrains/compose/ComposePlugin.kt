/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

@file:Suppress("unused")

package org.jetbrains.compose

import groovy.lang.Closure
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.plugins.ExtensionAware
import org.jetbrains.compose.desktop.DesktopExtension
import org.jetbrains.compose.desktop.application.internal.configureDesktop
import org.jetbrains.compose.desktop.preview.internal.initializePreview
import org.jetbrains.compose.experimental.internal.configureExperimentalTargetsFlagsCheck
import org.jetbrains.compose.internal.KOTLIN_MPP_PLUGIN_ID
import org.jetbrains.compose.internal.mppExt
import org.jetbrains.compose.internal.utils.currentTarget
import org.jetbrains.compose.resources.ResourcesExtension
import org.jetbrains.compose.resources.configureComposeResources
import org.jetbrains.compose.web.WebExtension
import org.jetbrains.compose.web.internal.configureWeb
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler

internal val composeVersion get() = ComposeBuildConfig.composeVersion
internal val composeMaterial3Version get() = ComposeBuildConfig.composeMaterial3Version

abstract class ComposePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val composeExtension = project.extensions.create("compose", ComposeExtension::class.java, project)
        val desktopExtension = composeExtension.extensions.create("desktop", DesktopExtension::class.java)
        val resourcesExtension = composeExtension.extensions.create("resources", ResourcesExtension::class.java)
        val dependencyCompatibilityExtension = composeExtension.extensions.create(
            "dependencyCompatibility", DependencyCompatibilityExtension::class.java
        )

        project.dependencies.extensions.add("compose", Dependencies(project))

        if (!project.buildFile.endsWith(".gradle.kts")) {
            setUpGroovyDslExtensions(project)
        }

        project.initializePreview(desktopExtension)
        composeExtension.extensions.create("web", WebExtension::class.java)

        project.checkComposeCompilerPlugin()

        project.configureComposeResources(resourcesExtension)

        project.configureWeb()

        project.configureRuntimeLibrariesCompatibilityCheck(dependencyCompatibilityExtension)
        // TODO: https://youtrack.jetbrains.com/issue/CMP-10868
        // project.configureSwiftCompatibilityLinking()

        project.afterEvaluate {
            configureDesktop(project, desktopExtension)
            project.plugins.withId(KOTLIN_MPP_PLUGIN_ID) {
                val mppExt = project.mppExt
                project.configureExperimentalTargetsFlagsCheck(mppExt)
            }
        }
    }

    @Suppress("DEPRECATION")
    class Dependencies(project: Project) {
        val desktop = DesktopDependencies
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.animation:animation:${ComposeBuildConfig.composeVersion}\""))
        val animation get() = composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.animation:animation")
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.animation:animation-graphics:${ComposeBuildConfig.composeVersion}\""))
        val animationGraphics get() = composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.animation:animation-graphics")
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.foundation:foundation:${ComposeBuildConfig.composeVersion}\""))
        val foundation get() = composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.foundation:foundation")
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.material:material:${ComposeBuildConfig.composeVersion}\""))
        val material get() = composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.material:material")
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.material3:material3:${ComposeBuildConfig.composeMaterial3Version}\""))
        val material3 get() = composeMaterial3Dependency("${ComposeBuildConfig.composeLibrariesGroup}.material3:material3")
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.material3:material3-adaptive-navigation-suite:${ComposeBuildConfig.composeMaterial3Version}\""))
        val material3AdaptiveNavigationSuite get() = composeMaterial3Dependency("${ComposeBuildConfig.composeLibrariesGroup}.material3:material3-adaptive-navigation-suite")
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.runtime:runtime:${ComposeBuildConfig.composeVersion}\""))
        val runtime get() = composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.runtime:runtime")
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.runtime:runtime-saveable:${ComposeBuildConfig.composeVersion}\""))
        val runtimeSaveable get() = composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.runtime:runtime-saveable")
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.ui:ui:${ComposeBuildConfig.composeVersion}\""))
        val ui get() = composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.ui:ui")
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.ui:ui-test:${ComposeBuildConfig.composeVersion}\""))
        @ExperimentalComposeLibrary
        val uiTest get() = composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.ui:ui-test")
        @Deprecated("Use ${ComposeBuildConfig.composeLibrariesGroup}.ui:ui-tooling module instead", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.ui:ui-tooling:${ComposeBuildConfig.composeVersion}\""))
        val uiTooling get() = composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.ui:ui-tooling")
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.ui:ui-util:${ComposeBuildConfig.composeVersion}\""))
        val uiUtil get() = composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.ui:ui-util")
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.ui:ui-tooling-preview:${ComposeBuildConfig.composeVersion}\""))
        val preview get() = composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.ui:ui-tooling-preview")
        @Deprecated(
            "This artifact is pinned to version 1.7.3 and will not receive updates. " +
                "Either use this version explicitly or migrate to Material Symbols (vector resources). " +
                "See https://kotlinlang.org/docs/multiplatform/whats-new-compose-180.html",
            replaceWith = ReplaceWith("\"org.jetbrains.compose.material:material-icons-extended:1.7.3\"")
        )
        val materialIconsExtended get() = "org.jetbrains.compose.material:material-icons-extended:1.7.3"
        @Deprecated("Specify dependency directly")
        val components get() = CommonComponentsDependencies
        @Deprecated("Use compose.html", replaceWith = ReplaceWith("html"), level = DeprecationLevel.ERROR)
        val web: WebDependencies get() = WebDependencies
        @Deprecated("Specify dependency directly")
        val html: HtmlDependencies get() = HtmlDependencies
    }

    @Deprecated("Specify dependency directly")
    object DesktopDependencies {
        @Deprecated("Specify dependency directly")
        val components = DesktopComponentsDependencies

        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.desktop:desktop:${ComposeBuildConfig.composeVersion}\""))
        val common = composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.desktop:desktop")
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.desktop:desktop-jvm-linux-x64:${ComposeBuildConfig.composeVersion}\""))
        val linux_x64 = composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.desktop:desktop-jvm-linux-x64")
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.desktop:desktop-jvm-linux-arm64:${ComposeBuildConfig.composeVersion}\""))
        val linux_arm64 = composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.desktop:desktop-jvm-linux-arm64")
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.desktop:desktop-jvm-windows-x64:${ComposeBuildConfig.composeVersion}\""))
        val windows_x64 = composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.desktop:desktop-jvm-windows-x64")
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.desktop:desktop-jvm-windows-arm64:${ComposeBuildConfig.composeVersion}\""))
        val windows_arm64 = composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.desktop:desktop-jvm-windows-arm64")
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.desktop:desktop-jvm-macos-x64:${ComposeBuildConfig.composeVersion}\""))
        val macos_x64 = composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.desktop:desktop-jvm-macos-x64")
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.desktop:desktop-jvm-macos-arm64:${ComposeBuildConfig.composeVersion}\""))
        val macos_arm64 = composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.desktop:desktop-jvm-macos-arm64")

        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.ui:ui-test-junit4:${ComposeBuildConfig.composeVersion}\""))
        val uiTestJUnit4 get() = composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.ui:ui-test-junit4")

        @Deprecated(
            "currentOs is deprecated and will be removed in a future release. " +
                    "You might need to replace currentOs with these explicit dependencies:\n" +
                    "implementation(\"${ComposeBuildConfig.composeLibrariesGroup}.ui:ui:<composeVersion>\")\n" +
                    "implementation(\"${ComposeBuildConfig.composeLibrariesGroup}.foundation:foundation:<composeVersion>\")\n" +
                    "implementation(\"${ComposeBuildConfig.composeLibrariesGroup}.material:material:<composeVersion>\")\n" +
                    "Compose now publishes an artifact containing binaries for all supported desktop operating systems. " +
                    "During packaging, binaries for non-target operating systems are automatically removed."
        )
        val currentOs by lazy {
            composeDependency("${ComposeBuildConfig.composeLibrariesGroup}.desktop:desktop-jvm-${currentTarget.id}")
        }
    }

    @Deprecated("Specify dependency directly")
    object CommonComponentsDependencies {
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"org.jetbrains.compose.components:components-resources:${ComposeBuildConfig.composeUpstreamVersion}\""))
        val resources = composeDependency("org.jetbrains.compose.components:components-resources")
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"${ComposeBuildConfig.composeLibrariesGroup}.ui:ui-tooling-preview:${ComposeBuildConfig.composeVersion}\""))
        val uiToolingPreview = composeDependency("org.jetbrains.compose.components:components-ui-tooling-preview")
    }

    @Deprecated("Specify dependency directly")
    object DesktopComponentsDependencies {
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"org.jetbrains.compose.components:components-splitpane:${ComposeBuildConfig.composeUpstreamVersion}\""))
        @ExperimentalComposeLibrary
        val splitPane = composeDependency("org.jetbrains.compose.components:components-splitpane")

        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"org.jetbrains.compose.components:components-animatedimage:${ComposeBuildConfig.composeUpstreamVersion}\""))
        @ExperimentalComposeLibrary
        val animatedImage = composeDependency("org.jetbrains.compose.components:components-animatedimage")
    }

    @Deprecated("Use compose.html")
    object WebDependencies {
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"org.jetbrains.compose.html:html-core:${ComposeBuildConfig.composeUpstreamVersion}\""))
        val core by lazy {
            composeDependency("org.jetbrains.compose.html:html-core")
        }

        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"org.jetbrains.compose.html:html-svg:${ComposeBuildConfig.composeUpstreamVersion}\""))
        val svg by lazy {
            composeDependency("org.jetbrains.compose.html:html-svg")
        }

        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"org.jetbrains.compose.html:html-test-utils:${ComposeBuildConfig.composeUpstreamVersion}\""))
        val testUtils by lazy {
            composeDependency("org.jetbrains.compose.html:html-test-utils")
        }
    }

    @Deprecated("Specify dependency directly")
    object HtmlDependencies {
        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"org.jetbrains.compose.html:html-core:${ComposeBuildConfig.composeUpstreamVersion}\""))
        val core by lazy {
            composeDependency("org.jetbrains.compose.html:html-core")
        }

        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"org.jetbrains.compose.html:html-svg:${ComposeBuildConfig.composeUpstreamVersion}\""))
        val svg by lazy {
            composeDependency("org.jetbrains.compose.html:html-svg")
        }

        @Deprecated("Specify dependency directly", replaceWith = ReplaceWith("\"org.jetbrains.compose.html:html-test-utils:${ComposeBuildConfig.composeUpstreamVersion}\""))
        val testUtils by lazy {
            composeDependency("org.jetbrains.compose.html:html-test-utils")
        }
    }
}

fun RepositoryHandler.jetbrainsCompose(): MavenArtifactRepository =
    maven { repo -> repo.setUrl("https://packages.jetbrains.team/maven/p/cmp/dev") }

fun KotlinDependencyHandler.compose(groupWithArtifact: String) = composeDependency(groupWithArtifact)

fun DependencyHandler.compose(groupWithArtifact: String) = composeDependency(groupWithArtifact)

private const val JETBRAINS_COMPOSE_GROUP = "org.jetbrains.compose"

// Groups only JetBrains publishes: the fork builds Compose itself, not the components or
// Compose HTML, so these are still asked for from JetBrains, at the version JetBrains
// published the same Compose as.
private val upstreamOnlyGroups = setOf("$JETBRAINS_COMPOSE_GROUP.components", "$JETBRAINS_COMPOSE_GROUP.html")

/**
 * [groupWithArtifact] at the version this plugin was built for.
 *
 * The libraries come from thisisthepy/compose-multiplatform-core-extended, under
 * `org.thisisthepy.compose.*`. A JetBrains group passed in (`compose("org.jetbrains.compose.ui:ui")`)
 * is turned into the fork's, because the fork's version does not exist under JetBrains' group;
 * asking for it there would fail to resolve, and asking for JetBrains' own version would put a
 * second copy of the same classes on the classpath.
 */
private fun composeDependency(groupWithArtifact: String): String {
    val group = groupWithArtifact.substringBefore(":")
    val artifact = groupWithArtifact.substringAfter(":")
    return when {
        group in upstreamOnlyGroups -> "$groupWithArtifact:${ComposeBuildConfig.composeUpstreamVersion}"
        group.startsWith("$JETBRAINS_COMPOSE_GROUP.") ->
            "${ComposeBuildConfig.composeLibrariesGroup}.${group.removePrefix("$JETBRAINS_COMPOSE_GROUP.")}:$artifact:$composeVersion"
        else -> "$groupWithArtifact:$composeVersion"
    }
}

private fun composeMaterial3Dependency(groupWithArtifact: String) = "$groupWithArtifact:$composeMaterial3Version"

private fun setUpGroovyDslExtensions(project: Project) {
    project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
        (project.extensions.getByName("kotlin") as? ExtensionAware)?.apply {
            extensions.add("compose", ComposePlugin.Dependencies(project))
        }
    }
    (project.repositories as? ExtensionAware)?.extensions?.apply {
        add("jetbrainsCompose", object : Closure<MavenArtifactRepository>(project.repositories) {
            fun doCall(): MavenArtifactRepository =
                project.repositories.jetbrainsCompose()
        })
    }
}
