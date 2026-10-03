plugins {
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.thisisthepy.compose")
}

kotlin {
    android {
        namespace = "org.company.app"
        compileSdk {
            version = release(37) { minorApiLevel = 1 }
        }
        minSdk = 23
        androidResources.enable = true
    }

    jvm()

    js { browser() }
    wasmJs { browser() }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api("org.thisisthepy.compose.runtime:runtime:COMPOSE_VERSION_PLACEHOLDER")
            api("org.thisisthepy.compose.ui:ui:COMPOSE_VERSION_PLACEHOLDER")
            api("org.thisisthepy.compose.foundation:foundation:COMPOSE_VERSION_PLACEHOLDER")
        }
    }
}
