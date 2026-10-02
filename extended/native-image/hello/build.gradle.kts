// The smallest ordinary Compose desktop application: an AWT window, Material 3, a button
// and some text. It is what the native-image path is proved against before any plugin
// task is written, and what the plugin's tests build afterwards.
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
    nativeImage {
        // Liberica NIK Full and the static Skia archive; see ../README.md.
        providers.environmentVariable("SKIKO_STATIC").orNull?.let { skikoStaticDirectory.set(file(it)) }
    }
}

