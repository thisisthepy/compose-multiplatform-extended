plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.thisisthepy.compose")
}

group = "app.group"

kotlin {
    jvm("desktop")

    sourceSets {
        commonMain {
            dependencies {
                implementation("org.thisisthepy.compose.runtime:runtime:COMPOSE_VERSION_PLACEHOLDER")
                implementation("org.thisisthepy.compose.material:material:COMPOSE_VERSION_PLACEHOLDER")
                implementation("org.jetbrains.compose.components:components-resources:COMPOSE_UPSTREAM_VERSION_PLACEHOLDER")
            }
        }
    }
}
