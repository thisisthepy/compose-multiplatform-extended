// A Compose desktop application with no AWT in it: no ComposeWindow, no java.awt. The window
// is core-extended's extended/window WindowPlatform (graalvm-macos or graalvm-linux, chosen by
// the operating system this is built on), the scene is hosted by the window scene module,
// and Skia draws into the window's surface, Metal on macOS and GL on Linux.
//
// The window modules come from the local Maven repository: core-extended publishes them with
// `./kotlin publish mavenLocal` in extended/window.
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.11.1-extended-dev"
}

val windowing = "org.thisisthepy.compose.window"
val windowVersion = "0.1.0"
val mac = org.gradle.internal.os.OperatingSystem.current().isMacOsX

sourceSets.main {
    // The one file that names the platform layer and its graphics API.
    kotlin.srcDir(if (mac) "src/mac/kotlin" else "src/linux/kotlin")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation("$windowing:common:$windowVersion")
    implementation("$windowing:scene:$windowVersion")
    implementation("$windowing:${if (mac) "graalvm-macos" else "graalvm-linux"}:$windowVersion")
}

compose.desktop.application {
    mainClass = "hello.MainKt"
    nativeImage {
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
