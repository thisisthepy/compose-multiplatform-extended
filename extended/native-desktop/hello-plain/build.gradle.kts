// A Kotlin/Native program with no Compose in it. The same source builds a native executable on
// macOS, Linux and Windows. On Windows the plugin relinks Kotlin/Native's mingwX64 output with
// MSVC, so the file you run is an MSVC-linked .exe, and the task names are Kotlin's own.
plugins {
    kotlin("multiplatform") version "2.2.20"
    id("org.thisisthepy.kotlin.native.desktop") version "0.1.0-extended-dev"
}

kotlin {
    val host = System.getProperty("os.name")
    val arch = System.getProperty("os.arch")
    val target = when {
        host.startsWith("Mac") && arch == "aarch64" -> macosArm64()
        host.startsWith("Mac") -> macosX64()
        host.startsWith("Linux") && arch == "aarch64" -> linuxArm64()
        host.startsWith("Linux") -> linuxX64()
        host.startsWith("Windows") -> mingwX64()
        else -> error("no desktop Kotlin/Native target for $host")
    }
    target.binaries {
        executable {
            baseName = "hello"
            entryPoint = "hello.main"
        }
    }

    msvc {
        // CI sets this to prove the rewrite of the MinGW objects is what makes the program work.
        rewriteMingwObjects = providers.gradleProperty("hello.unrewritten").map { !it.toBoolean() }.orElse(true)
    }
}
