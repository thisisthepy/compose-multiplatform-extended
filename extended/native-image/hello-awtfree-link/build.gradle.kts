// The smallest AwtFree image that has to link the window layer: a plain main, no Compose
// scene, that opens one window through the extended window module, presents a frame and
// exits. If the Objective-C or C window source, or the upcall table, does not compile and
// link into the image, packageNativeImage here is where it shows.
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.compose") version "2.2.20"
    id("org.thisisthepy.compose") version "1.11.1-ext.1-dev"
}

val macos = System.getProperty("os.name").startsWith("Mac")
val windowArtifact = if (macos) "graalvm-macos" else "graalvm-linux"

sourceSets.main {
    kotlin.srcDir(if (macos) "src/macos/kotlin" else "src/linux/kotlin")
}

dependencies {
    implementation("org.thisisthepy.compose.window:common:1.11.1-ext.1")
    implementation("org.thisisthepy.compose.window:$windowArtifact:1.11.1-ext.1")
    // skiko is linked into every image this plugin makes; the sample draws nothing with it.
    implementation(compose.desktop.currentOs)
    // skiko-awt with the AWT switch, from compose-multiplatform-core-extended's
    // extended/skiko/build-skiko-awt.sh. Its version sorts above the 0.144.6 Compose asks for, so
    // it is the one the image takes.
    implementation("org.thisisthepy.compose.skiko:skiko-awt:1.11.1-ext.1")
}

configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("org.jetbrains.skiko:skiko-awt"))
            .using(module("org.thisisthepy.compose.skiko:skiko-awt:1.11.1-ext.1"))
            .because("AWT-free skiko-awt with AwtSwitch")
    }
}

compose.desktop.application {
    mainClass = "link.MainKt"
    windowing = org.jetbrains.compose.desktop.application.dsl.ApplicationWindowing.AwtFree
    nativeImage {
        // CORE_EXTENDED: a checkout of compose-multiplatform-core-extended; SKIKO_STATIC: the
        // build-skiko-static-jvm.sh --no-jawt output.
        providers.environmentVariable("CORE_EXTENDED").orNull?.let { windowSourcesDirectory.set(file(it)) }
        providers.environmentVariable("SKIKO_STATIC").orNull?.let { skikoStaticDirectory.set(file(it)) }
    }
}

// The window modules are built with a newer Kotlin than this sample; they use nothing the
// older metadata reader cannot follow, so the version check is skipped.
kotlin.compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
