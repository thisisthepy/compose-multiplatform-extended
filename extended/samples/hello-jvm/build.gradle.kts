// The plain JVM way to ship: an ordinary Compose desktop application, packaged with jpackage
// into a self-contained app image. It is the baseline the native-image and Kotlin/Native
// samples are compared against.
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.11.1-extended-dev"
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
}

compose.desktop.application {
    mainClass = "hello.MainKt"
    nativeDistributions {
        packageName = "hello-jvm"
        packageVersion = "1.0.0"
    }
}
