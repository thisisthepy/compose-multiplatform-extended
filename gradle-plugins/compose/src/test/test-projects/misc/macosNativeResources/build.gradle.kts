import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.thisisthepy.compose")
}

kotlin {

    listOf(
        macosArm64(),
    ).forEach {
        it.binaries {
            executable {
                entryPoint = "main"
                freeCompilerArgs += listOf(
                    "-linker-options",
                    "-framework",
                    "-linker-option",
                    "Metal",
                    "-Xdisable-phases=VerifyBitcode"
                )
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation("org.thisisthepy.compose.runtime:runtime:COMPOSE_VERSION_PLACEHOLDER")
                implementation("org.thisisthepy.compose.material:material:COMPOSE_VERSION_PLACEHOLDER")
                implementation("org.jetbrains.compose.components:components-resources:COMPOSE_UPSTREAM_VERSION_PLACEHOLDER")
            }
        }
    }
}

compose.desktop {
    nativeApplication {
        targets(
            targets = kotlin.targets.filter {
                it.platformType == KotlinPlatformType.native &&
                        it.name.contains("macos")
            }.toTypedArray()
        )
        distributions {
            macOS {
                targetFormats(TargetFormat.Dmg)
                packageName = "Test Resources"
                packageVersion = "1.0.0"
            }
        }
    }
}