// The smallest ordinary Compose desktop application: an AWT window, Material 3, a button
// and some text. It is what the native-image path is proved against before any plugin
// task is written, and what the plugin's tests build afterwards.
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.11.1"
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
}

compose.desktop.application {
    mainClass = "hello.MainKt"
}

// The classpath the application runs with, written out for the native-image proof script.
tasks.register("writeRuntimeClasspath") {
    val classpath = sourceSets.main.get().runtimeClasspath
    val out = layout.buildDirectory.file("runtime-classpath.txt")
    inputs.files(classpath)
    outputs.file(out)
    doLast { out.get().asFile.writeText(classpath.files.joinToString(File.pathSeparator)) }
}
