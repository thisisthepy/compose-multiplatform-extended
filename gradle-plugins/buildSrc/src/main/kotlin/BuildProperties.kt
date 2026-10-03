/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

import org.gradle.api.Project

// "Global" properties
object BuildProperties {
    const val name = "Compose Gradle Plugin (thisisthepy)"
    // The plugin and everything this build publishes. The plugin ID is the same name.
    const val group = "org.thisisthepy.compose"
    // Where the Compose libraries the plugin's aliases name are published: the groups of
    // thisisthepy/compose-multiplatform-core-extended, which are this prefix followed by the
    // JetBrains group's last part (org.thisisthepy.compose.ui and so on).
    const val librariesGroup = "org.thisisthepy.compose"
    const val website = "https://github.com/thisisthepy/compose-multiplatform-extended"
    const val vcs = "https://github.com/thisisthepy/compose-multiplatform-extended"
    fun composeVersion(project: Project): String =
        System.getenv("COMPOSE_GRADLE_PLUGIN_COMPOSE_VERSION")
            ?: project.findProperty("compose.version") as String

    /**
     * The version JetBrains published the same Compose as: [composeVersion] without the fork's
     * `-ext.<N>` suffix. The few libraries the fork does not publish (components, Compose HTML)
     * are still JetBrains' and are asked for at this version.
     */
    fun composeUpstreamVersion(project: Project): String =
        composeVersion(project).replace(Regex("-ext\\.\\d+(-dev)?$"), "")
    fun composeMaterial3Version(project: Project): String =
        project.findProperty("compose.material3.version") as String
    fun testsAndroidxCompilerVersion(project: Project): String =
        project.findProperty("compose.tests.androidx.compiler.version") as String
    fun testsAndroidxCompilerCompatibleVersion(project: Project): String =
        project.findProperty("compose.tests.androidx.compatible.kotlin.version") as String
    fun deployVersion(project: Project): String =
        System.getenv("COMPOSE_GRADLE_PLUGIN_VERSION")
            ?: project.findProperty("deploy.version") as String
}
