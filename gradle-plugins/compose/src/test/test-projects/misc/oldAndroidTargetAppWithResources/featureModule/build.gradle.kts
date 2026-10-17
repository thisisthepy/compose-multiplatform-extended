plugins {
    id("org.thisisthepy.compose")
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("com.android.library")
}

kotlin {
    jvm()

    androidTarget()

    sourceSets {
        commonMain.dependencies {
            api("org.thisisthepy.compose.runtime:runtime:COMPOSE_VERSION_PLACEHOLDER")
            api("org.thisisthepy.compose.material:material:COMPOSE_VERSION_PLACEHOLDER")
            api("org.jetbrains.compose.components:components-resources:COMPOSE_UPSTREAM_VERSION_PLACEHOLDER")
        }
    }
}
android {
    namespace = "me.sample.feature"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
}

compose.resources {
    publicResClass = true
}
