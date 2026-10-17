// What an application that calls native code through the Foreign Function and Memory API, and
// needs files beside its executable, asks of packageNativeImage:
//
// - ffm-lib, a dependency, carries the reachability metadata for its downcalls and its upcall
//   in its own jar (META-INF/native-image). Nothing for them is registered here, so a run that
//   works shows native-image read the library's metadata.
// - --enable-native-access=ALL-UNNAMED goes through nativeImage.buildArgs.
// - nativeImage.besideExecutable stages prefix/ beside the executable, where the application
//   finds it from its own path, the way an embedded Python finds PYTHONHOME.
//
// The application prints "ffm check ok" when all of it held.
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.11.1-extended-dev"
}

val macos = System.getProperty("os.name").startsWith("Mac")
val windowArtifact = if (macos) "graalvm-macos" else "graalvm-linux"

dependencies {
    implementation(project(":ffm-lib"))
    // An AwtFree image links the window layer and skiko whether or not the application opens a
    // window; this one opens none.
    implementation("org.thisisthepy.compose.window:common:0.1.0")
    implementation("org.thisisthepy.compose.window:$windowArtifact:0.1.0")
    implementation(compose.desktop.currentOs)
}

// ffm-lib is compiled for Java 22, the first release with the final FFM API; this module is
// compiled by the JDK Gradle runs on, and only calls ffm-lib's plain methods.
java.disableAutoTargetJvm()

compose.desktop.application {
    mainClass = "ffm.MainKt"
    windowing = org.jetbrains.compose.desktop.application.dsl.ApplicationWindowing.AwtFree
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    nativeImage {
        // CORE_EXTENDED: a checkout of compose-multiplatform-core-extended; SKIKO_STATIC: the
        // build-skiko-static-jvm.sh --no-jawt output.
        providers.environmentVariable("CORE_EXTENDED").orNull?.let { windowSourcesDirectory.set(file(it)) }
        providers.environmentVariable("SKIKO_STATIC").orNull?.let { skikoStaticDirectory.set(file(it)) }
        buildArgs.add("--enable-native-access=ALL-UNNAMED")
        besideExecutable {
            from("prefix") { into("prefix") }
        }
    }
}

// The window modules are built with a newer Kotlin than this sample; they use nothing the
// older metadata reader cannot follow, so the version check is skipped.
kotlin.compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
