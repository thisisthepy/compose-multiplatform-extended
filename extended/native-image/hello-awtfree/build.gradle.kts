// The AWT-free Compose application the one-executable gate checks: Compose content hosted by
// the core fork's scene module in its GraalVM window, drawn by Skia into the window's own
// surface (Metal on macOS, GL on Linux), and no AWT anywhere in the image. With HELLO_SELF_CHECK
// naming a PNG it also checks that Korean is shaped, broken into words and wrapped correctly by
// what is linked into the executable, draws that text into the PNG, presents frames of the
// Compose content and exits, so a run nobody watches proves all of it.
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.compose") version "2.2.20"
    id("org.thisisthepy.compose") version "1.11.1-ext.1-dev"
}

val macos = System.getProperty("os.name").startsWith("Mac")
val windowArtifact = if (macos) "graalvm-macos" else "graalvm-linux"

sourceSets.main {
    kotlin.srcDir(if (macos) "src/macos/kotlin" else "src/linux/kotlin")
    // The content and the text check, shared with the Kotlin/Native sample
    // (extended/native-desktop/hello-awtfree-kn).
    kotlin.srcDir("src/shared/kotlin")
}

dependencies {
    implementation("org.thisisthepy.compose.window:common:0.1.0")
    implementation("org.thisisthepy.compose.window:$windowArtifact:0.1.0")
    // The scene host, and the core fork's foundation the content is written with.
    implementation("org.thisisthepy.compose.window:scene:0.1.0")
    implementation(compose.foundation)
    // @CFunction, to ask the system which thread main runs on.
    compileOnly("org.graalvm.sdk:nativeimage:25.0.1")
    // skiko for this system, and Compose UI for the desktop.
    implementation(compose.desktop.currentOs)
    // skiko-awt with the AWT switch, from compose-multiplatform-core-extended's
    // extended/skiko/build-skiko-awt.sh. Its version sorts above the 0.144.6 Compose asks for, so
    // it is the one the image takes.
    implementation("org.jetbrains.skiko:skiko-awt:0.144.6.1")
}

compose.desktop.application {
    mainClass = "awtfree.MainKt"
    windowing = org.jetbrains.compose.desktop.application.dsl.ApplicationWindowing.AwtFree
    nativeImage {
        // CORE_EXTENDED: a checkout of compose-multiplatform-core-extended; SKIKO_STATIC: the
        // build-skiko-static-jvm.sh --no-jawt output.
        providers.environmentVariable("CORE_EXTENDED").orNull?.let { windowSourcesDirectory.set(file(it)) }
        // The sample's text is Korean; the image keeps the locale data it needs.
        buildArgs.add("-H:IncludeLocales=en,ko")
        // NATIVE_IMAGE_CALL_TREE set: the analysis call tree is written to the reports folder, to
        // find what makes something reachable.
        if (providers.environmentVariable("NATIVE_IMAGE_CALL_TREE").isPresent) {
            buildArgs.addAll("-H:+PrintAnalysisCallTree", "-H:PrintAnalysisCallTreeType=TXT")
        }
        // NATIVE_IMAGE_SYMBOLS set: the executable keeps its local symbols, so a stack sampled
        // from it (the gate samples one that hangs) names the methods it is in.
        if (providers.environmentVariable("NATIVE_IMAGE_SYMBOLS").isPresent) buildArgs.add("-H:-DeleteLocalSymbols")
        providers.environmentVariable("SKIKO_STATIC").orNull?.let { skikoStaticDirectory.set(file(it)) }
    }
}

// The window modules are built with a newer Kotlin than this sample; they use nothing the
// older metadata reader cannot follow, so the version check is skipped.
kotlin.compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
