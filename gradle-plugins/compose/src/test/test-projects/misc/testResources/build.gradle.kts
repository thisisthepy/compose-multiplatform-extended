plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.thisisthepy.compose")
}

group = "app.group"

kotlin {
    jvm("desktop")

    iosArm64()
    iosSimulatorArm64()

    macosArm64()

    sourceSets {
        commonMain {
            dependencies {
                implementation("org.thisisthepy.compose.runtime:runtime:COMPOSE_VERSION_PLACEHOLDER")
                implementation("org.thisisthepy.compose.material:material:COMPOSE_VERSION_PLACEHOLDER")
                implementation("org.jetbrains.compose.components:components-resources:COMPOSE_UPSTREAM_VERSION_PLACEHOLDER")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.thisisthepy.compose.ui:ui-test:COMPOSE_VERSION_PLACEHOLDER")
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}
