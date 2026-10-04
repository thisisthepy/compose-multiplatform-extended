pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

include(":compose")
include(":preview-rpc")
include(":jdk-version-probe")

// Fork-only: the Compose-free Kotlin/Native desktop plugin.
include(":kotlin-native-desktop")
project(":kotlin-native-desktop").projectDir = file("../extended/kotlin-native-desktop")
