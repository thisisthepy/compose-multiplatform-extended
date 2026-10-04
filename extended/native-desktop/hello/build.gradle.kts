// The smallest Kotlin/Native desktop application built with compose.nativeApplication. It
// proves the plugin configures and links an executable for the host platform. The Compose
// window content follows once the window modules are published.
plugins {
    kotlin("multiplatform") version "2.2.20"
    id("org.jetbrains.compose") version "1.11.1-extended-dev"
}

kotlin {
    val host = System.getProperty("os.name")
    val arch = System.getProperty("os.arch")
    val target = when {
        host.startsWith("Mac") && arch == "aarch64" -> macosArm64()
        host.startsWith("Mac") -> macosX64()
        host.startsWith("Linux") && arch == "aarch64" -> linuxArm64()
        host.startsWith("Linux") -> linuxX64()
        else -> error("no desktop Kotlin/Native target for $host")
    }
    target.binaries.executable { entryPoint = "hello.main" }
}

compose.nativeApplication {
    binarySettings {
        // The X11 and GL development libraries are not installed on a bare Linux runner.
        linkWindowSystem.set(providers.gradleProperty("hello.linkWindowSystem").map { it.toBoolean() }.orElse(false))
        providers.environmentVariable("SKIKO_NATIVE_DIR").orNull?.let { nativeSkikoDirectory.set(file(it)) }
    }
}
