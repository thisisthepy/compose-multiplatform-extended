// The smallest Compose desktop application, packaged as the three bundle formats of the
// extended plugin: a Flatpak on Linux, an unsigned MSIX on Windows.
// The extended-bundles workflow builds it. Run the task of your OS:
//   ./gradlew packageFlatpak   (Linux)
//   ./gradlew packageMsix                          (Windows)
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.11.1-extended-dev"
}

version = "1.0.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
}

compose.desktop.application {
    mainClass = "hello.MainKt"
    nativeDistributions {
        packageName = "hello-bundles"
        packageVersion = "1.0.0"
        description = "A window with a button."
        vendor = "Example"
        targetFormats(TargetFormat.Flatpak, TargetFormat.Msix)
        linux {
            appCategory = "Utility"
            flatpak {
                appId = "io.github.thisisthepy.HelloBundles"
                summary = "A window with a button"
                developerName = "Example"
            }
        }
        windows {
            msix {
                publisher = "CN=Example"
            }
        }
    }
}
