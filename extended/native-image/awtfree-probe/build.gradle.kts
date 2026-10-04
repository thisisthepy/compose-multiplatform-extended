// Finds what still pulls AWT into a native image when no window is opened through AWT.
// The application draws Compose content offscreen; native-image is run on its classpath
// by ../awtfree-probe.sh, once per forbidden type.
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.11.1-extended-dev"
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
}

tasks.register("writeRuntimeClasspath") {
    val classpath = sourceSets.main.get().runtimeClasspath
    val out = layout.buildDirectory.file("runtime-classpath.txt")
    inputs.files(classpath)
    outputs.file(out)
    doLast { out.get().asFile.writeText(classpath.files.joinToString(File.pathSeparator)) }
}
