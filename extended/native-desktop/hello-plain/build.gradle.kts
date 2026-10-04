// A Kotlin/Native program with no Compose in it, built with the extended desktop plugin. The
// same source builds a native executable on macOS and Linux, and on Windows an executable
// linked with MSVC, from Kotlin/Native's mingwX64 output.
plugins {
    kotlin("multiplatform") version "2.2.20"
    id("org.jetbrains.compose") version "1.11.1-extended-dev"
}

kotlin {
    val host = System.getProperty("os.name")
    val arch = System.getProperty("os.arch")
    when {
        host.startsWith("Mac") && arch == "aarch64" -> macosArm64()
        host.startsWith("Mac") -> macosX64()
        host.startsWith("Linux") && arch == "aarch64" -> linuxArm64()
        host.startsWith("Linux") -> linuxX64()
        host.startsWith("Windows") -> mingwX64()
        else -> error("no desktop Kotlin/Native target for $host")
    }.let { target ->
        // Windows gets its executable from the kotlinMsvc block below.
        if (!host.startsWith("Windows")) target.binaries.executable { entryPoint = "hello.main" }
    }
}

if (System.getProperty("os.name").startsWith("Windows")) {
    kotlinMsvc {
        executable("hello") {
            entryPoint = "hello.main"
            passArguments = true
            // CI sets this to prove the rewrite is what makes the program work.
            rewriteMingwObjects = providers.gradleProperty("hello.unrewritten").map { !it.toBoolean() }.orElse(true)
        }
    }
}
