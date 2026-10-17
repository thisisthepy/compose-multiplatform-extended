plugins {
    id("org.thisisthepy.compose")
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvm()

    android {
        namespace = "me.sample.feature"
        compileSdk {
            version = release(37) { minorApiLevel = 1 }
        }
        minSdk = 24
        androidResources.enable = true
    }

    sourceSets {
        commonMain.dependencies {
            api("org.thisisthepy.compose.runtime:runtime:COMPOSE_VERSION_PLACEHOLDER")
            api("org.thisisthepy.compose.material:material:COMPOSE_VERSION_PLACEHOLDER")
            api("org.jetbrains.compose.components:components-resources:COMPOSE_UPSTREAM_VERSION_PLACEHOLDER")
        }
    }
}

//https://youtrack.jetbrains.com/issue/CMP-8325
compose.desktop {
    application { }
}

compose.resources {
    publicResClass = true
}
