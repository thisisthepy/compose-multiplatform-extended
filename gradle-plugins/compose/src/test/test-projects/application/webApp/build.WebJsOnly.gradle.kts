plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.thisisthepy.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    js {
        browser { }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.thisisthepy.compose.runtime:runtime:COMPOSE_VERSION_PLACEHOLDER")
        }

        val webMain by creating { dependsOn(commonMain.get()) }
        jsMain { dependsOn(webMain) }
    }
}
