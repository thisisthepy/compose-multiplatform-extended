// The AWT-free sample as a Kotlin/Native macOS executable: the same Compose content as
// extended/native-image/hello-awtfree, in the core fork's Kotlin/Native AppKit and Metal window
// (org.thisisthepy.compose.window:native-macos), with no JVM at all. The one-executable gate
// checks it the way it checks the GraalVM images.
//
//   ./gradlew linkReleaseExecutableMacosArm64
//
// Kotlin has to be at least the version the window klibs were compiled with: a klib from a newer
// compiler does not link.
plugins {
    kotlin("multiplatform") version "2.4.10"
    kotlin("plugin.compose") version "2.4.10"
    id("org.thisisthepy.kotlin.native.desktop") version "0.1.0-extended-dev"
}

// The core fork's Compose and window modules, as compose-multiplatform-core-extended publishes
// them to the local Maven repository.
val compose = "1.11.1-ext.1"
val window = "1.11.1-ext.1"

kotlin {
    macosArm64 {
        binaries.executable {
            baseName = "hello-awtfree-kn"
            entryPoint = "awtfree.main"
        }
    }
    sourceSets.macosMain {
        // The content and the text check hello-awtfree draws and runs.
        kotlin.srcDir("../../native-image/hello-awtfree/src/shared/kotlin")
        dependencies {
            implementation("org.thisisthepy.compose.window:common:$window")
            implementation("org.thisisthepy.compose.window:native-macos:$window")
            implementation("org.thisisthepy.compose.runtime:runtime:$compose")
            implementation("org.thisisthepy.compose.foundation:foundation:$compose")
            implementation("org.thisisthepy.compose.ui:ui:$compose")
        }
    }
}
