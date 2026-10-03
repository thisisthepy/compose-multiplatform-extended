plugins {
    id("org.thisisthepy.compose")
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        compileSdk {
            version = release(37) { minorApiLevel = 1 }
        }
        namespace = "me.sample.feature"
        minSdk = 23
        androidResources.enable = true
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    js { browser() }
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            implementation("org.thisisthepy.compose.runtime:runtime:COMPOSE_VERSION_PLACEHOLDER")
            implementation("org.thisisthepy.compose.material3:material3:COMPOSE_MATERIAL3_VERSION_PLACEHOLDER")
            implementation("org.jetbrains.compose.components:components-resources:COMPOSE_UPSTREAM_VERSION_PLACEHOLDER")
        }
    }
}
