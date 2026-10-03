// The smallest ordinary Compose desktop application: an AWT window, Material 3, a button
// and some text. It is what the native-image path is proved against before any plugin
// task is written, and what the plugin's tests build afterwards.
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.compose") version "2.2.20"
    id("org.thisisthepy.compose") version "1.11.1-ext.1-dev"
}

// The libraries are compose-multiplatform-core-extended's, built locally as a development build
// (-Pextended.publication.dev=true), which is what the plugin above is built to ask for.
dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.thisisthepy.compose.material3:material3:1.11.0-alpha07-ext.1-dev")
}

compose.desktop.application {
    mainClass = "hello.MainKt"
    nativeImage {
        // Liberica NIK Full and the static Skia archive; see ../README.md.
        providers.environmentVariable("SKIKO_STATIC").orNull?.let { skikoStaticDirectory.set(file(it)) }
    }
}


// The classpath the application runs with, for probes that call native-image directly.
tasks.register("writeRuntimeClasspath") {
    val classpath = sourceSets.main.get().runtimeClasspath
    val out = layout.buildDirectory.file("runtime-classpath.txt")
    inputs.files(classpath)
    outputs.file(out)
    doLast { out.get().asFile.writeText(classpath.files.joinToString(File.pathSeparator)) }
}
