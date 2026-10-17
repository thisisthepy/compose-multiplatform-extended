// The AWT-free application the one-executable gate checks: a window from the extended window
// module, text laid out and drawn by Skia with the paragraph engine Compose's text uses, and no
// AWT anywhere in the image. With HELLO_SELF_CHECK naming a PNG it also checks that Korean is
// shaped, broken into words and wrapped correctly by what is linked into the executable, draws
// that text into the PNG and exits, so a run nobody watches proves both.
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.11.1-extended-dev"
}

val macos = System.getProperty("os.name").startsWith("Mac")
val windowArtifact = if (macos) "graalvm-macos" else "graalvm-linux"

sourceSets.main {
    kotlin.srcDir(if (macos) "src/macos/kotlin" else "src/linux/kotlin")
}

dependencies {
    implementation("org.thisisthepy.compose.window:common:0.1.0")
    implementation("org.thisisthepy.compose.window:$windowArtifact:0.1.0")
    // skiko for this system: the paragraph engine and the raster surface the sample draws with.
    implementation(compose.desktop.currentOs)
}

compose.desktop.application {
    mainClass = "awtfree.MainKt"
    windowing = org.jetbrains.compose.desktop.application.dsl.ApplicationWindowing.AwtFree
    nativeImage {
        // CORE_EXTENDED: a checkout of compose-multiplatform-core-extended; SKIKO_STATIC: the
        // build-skiko-static-jvm.sh --no-jawt output.
        providers.environmentVariable("CORE_EXTENDED").orNull?.let { windowSourcesDirectory.set(file(it)) }
        // AppKit calls have to come from the process's first thread; GraalVM's launcher runs
        // main on a new one on macOS unless told not to.
        if (macos) buildArgs.add("-H:-RunMainInNewThread")
        // The sample's text is Korean; the image keeps the locale data it needs.
        buildArgs.add("-H:IncludeLocales=en,ko")
        providers.environmentVariable("SKIKO_STATIC").orNull?.let { skikoStaticDirectory.set(file(it)) }
    }
}

// The window modules are built with a newer Kotlin than this sample; they use nothing the
// older metadata reader cannot follow, so the version check is skipped.
kotlin.compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
