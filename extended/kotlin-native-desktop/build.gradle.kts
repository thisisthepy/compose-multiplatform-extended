// org.thisisthepy.kotlin.native.desktop: Kotlin/Native desktop programs without Compose. The id
// follows the Kotlin Gradle plugin's own (org.jetbrains.kotlin.native.cocoapods) with the same
// substitution the fork applies to Compose: org.jetbrains becomes org.thisisthepy.
plugins {
    alias(libs.plugins.kotlin.jvm)
    id("java-gradle-plugin")
    id("maven-publish")
}

group = "org.thisisthepy.kotlin"

gradlePluginConfig {
    pluginId = "org.thisisthepy.kotlin.native.desktop"
    implementationClass = "org.thisisthepy.kotlin.gradle.nativedesktop.NativeDesktopPlugin"
    pluginPortalTags = listOf("kotlin", "kotlin-native")
}

mavenPublicationConfig {
    displayName = "Kotlin/Native desktop Gradle plugin"
    description = "Links Kotlin/Native desktop programs, and on Windows turns mingwX64 output into an MSVC executable"
    artifactId = "kotlin-native-desktop-gradle-plugin"
}

dependencies {
    compileOnly(gradleApi())
    compileOnly(kotlin("gradle-plugin"))
    compileOnly(kotlin("native-utils"))

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
