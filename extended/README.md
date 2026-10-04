# compose-multiplatform-extended

The Compose Gradle plugin, extended to ship a desktop application as a JVM app, one GraalVM native-image executable, or one Kotlin/Native executable, all from the same `compose.desktop` settings.

Korean: [README_ko.md](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/README_ko.md)

This is thisisthepy's fork of
[JetBrains/compose-multiplatform](https://github.com/JetBrains/compose-multiplatform), the
repository of the Compose Gradle plugin. Its work lives on the `extended` branch. The
repository root stays as upstream has it. What the fork adds is either in the plugin under
`gradle-plugins/` or under `extended/`. It pairs with
[compose-multiplatform-core-extended](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/README.md),
the fork of the Compose libraries.

## Features

- One application, three outputs: `output = ApplicationOutput.Jvm`, `NativeImage` or
  `KotlinNative`. Name, version, vendor, icons and resources are written once in
  `nativeDistributions`.
- `packageNativeImage`: the application as one GraalVM native-image executable, with no Java
  runtime beside it (macOS arm64, Linux x64, Windows x64).
- `runNativeImageAgent`: records the reachability metadata the image is built from.
- AWT-free windows: with `windowing = ApplicationWindowing.AwtFree` the window comes from the
  extended window modules, not from the JDK's AWT.
- Kotlin/Native desktop packaging for macOS, Linux and Windows: `.app` with Info.plist, icon,
  signing, notarization and a universal binary; AppImage and deb; exe folder, zip and msi.
- Run tasks for every output, and a Windows application that is DPI aware on a scaled display.
- Upstream's `compose` DSL and public API are unchanged, so an existing Compose project keeps
  working. New settings are additive.

## Why use it

Upstream's `compose.desktop` ships an application with a Java runtime packed beside it. That is
a larger download and a runtime to keep up to date. With this fork the same
project can also produce a single native executable, and it does so without AWT, so there is
nothing in the executable that the application does not use.

## Install

The fork is not on a public repository yet. Publish the plugin to your local Maven repository
and put `mavenLocal()` ahead of the Gradle Plugin Portal:

```sh
cd gradle-plugins
./gradlew --no-daemon :compose:publishToMavenLocal \
    -Pdeploy.version=1.11.1-extended-dev -Pcompose.version=1.11.1
```

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories { mavenLocal(); gradlePluginPortal(); mavenCentral(); google() }
}
dependencyResolutionManagement {
    repositories { mavenLocal(); mavenCentral(); google() }
}
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.11.1-extended-dev"
}
```

The plugin keeps JetBrains' ID for now. A rename to `org.thisisthepy.compose` is planned, see
[The Gradle plugin](#the-gradle-plugin).

## The three ways to ship

| Output | What you get | Task | Details |
|---|---|---|---|
| JVM | an application with a Java runtime beside it (dmg, deb, msi, ...) | `packageDistributionForCurrentOS` | upstream's, unchanged |
| GraalVM native image | one executable, no runtime | `packageNativeImage` | [One executable](#one-executable-packagenativeimage) below |
| Kotlin/Native | one executable per target, packaged for the OS | `packageKotlinNative` | [`gradle-plugin-kn`](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/gradle-plugin-kn/README.md) |

`packageApplication` runs the one the `output` setting names:

```kotlin
compose.desktop.application {
    mainClass = "hello.MainKt"
    output = ApplicationOutput.NativeImage      // Jvm (default), NativeImage or KotlinNative
    windowing = ApplicationWindowing.AwtFree    // Awt (default for Jvm) or AwtFree
    nativeDistributions {
        packageName = "Hello"
        packageVersion = "1.0.0"
    }
}
```

### Windows needs the MSVC Build Tools

Every `mingwX64` target ships as a final MSVC executable. Kotlin/Native compiles MinGW objects,
which are rewritten so the MSVC linker accepts them, and the MSVC linker links them with Skia
and the window C layer, which are MSVC builds. There is no MinGW-only path. The owner decided
this on 2026-10-05:

> [user] "Msvc 있어야 하는게 뭐 어때서? 나는 물어보는거잖아. Mingw 우회 구현을 넣지 마. Extended는 Mingw 타겟도 전부 최종 msvc 앱으로 나가도록 하면 되는거지 그냥."

Install Visual Studio Build Tools with the MSVC toolset v14.51 or later, `clang-cl` and the
Windows SDK. The build looks for them at its start and stops with an install message when one
is missing. The GraalVM native image on Windows has the same toolset requirement (below).

### macOS signing and notarization

The Kotlin/Native `.app` is signed by `signDistributableNative...`: with a Developer ID
identity when `nativeDistributions.macOS.signing { identity = "..." }` is set, ad hoc
otherwise (enough to run on the machine that built it). `notarizeDmgNative...` submits the
dmg with `macOS.notarization { appleID, password, teamID }` and staples the ticket. Without
credentials it prints "Skipping notarization" and succeeds, so CI without Apple credentials
still passes. See
[`gradle-plugin-kn`](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/gradle-plugin-kn/README.md)
for every task and the Linux and Windows prerequisites.

## What it adds, in detail

| Addition | Status | Where |
|---|---|---|
| `packageNativeImage`: a Compose desktop application as one GraalVM native-image executable on macOS arm64, Linux x64 and Windows x64 | implemented | [`extended/native-image`](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/native-image/README.md) |
| `runNativeImageAgent`: the reachability metadata the image is built from | implemented | same |
| Kotlin/Native packaging and run tasks (macOS, Linux, Windows) | implemented | [`extended/gradle-plugin-kn`](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/gradle-plugin-kn/README.md) |
| Windows: a DPI-aware executable, and a check for an old MSVC toolset before the link | implemented | [#8](https://github.com/thisisthepy/compose-multiplatform-extended/pull/8) |
| Windows on a real display (the probe renders off screen) | planned | [`extended/native-image`](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/native-image/README.md#not-yet) |
| Plugin ID `org.thisisthepy.compose` | planned | branch [`chore/thisisthepy-coordinates`](https://github.com/thisisthepy/compose-multiplatform-extended/tree/chore/thisisthepy-coordinates) |

The Compose libraries this pairs with, and the static skiko archive the image links, come from
[thisisthepy/compose-multiplatform-core-extended](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/README.md).

## One executable: packageNativeImage

What `compose.desktop` makes today is an application with a Java runtime packed beside it.
`packageNativeImage` makes a GraalVM native image instead: one file, no runtime, nothing
unpacked at start. AWT, JAWT and Skia are linked in from static archives, because a single
executable cannot open them as separate files. The per-directory README explains each piece,
per platform, with what was measured:
[extended/native-image/README.md](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/native-image/README.md).

### Requirements

- **GraalVM: Liberica NIK 25 Full.** The image links the JDK's AWT statically, and only a
  distribution that ships the JDK's static archives can do that. The task stops with that
  reason when they are missing. Measured with NIK 25.0.4.1.
- **The static skiko archive**, from `extended/skiko/build-skiko-static-jvm.sh` in
  compose-multiplatform-core-extended, built on the same system. A single executable cannot
  load Skia from a file.
- **The host is the target.** native-image builds for the system it runs on, so Linux is
  built on Linux and Windows on Windows.
- **Linux x64:** the X11, GL, fontconfig, freetype and dbus development packages that the
  [Linux probe workflow](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/.github/workflows/native-image-linux-probe.yml)
  installs.
- **Windows x64:**
  - Run from an x64 Native Tools Command Prompt (or after `vcvars64.bat`), because the task
    finds the MSVC libraries on `LIB` and calls `cl` and `dumpbin`.
  - MSVC toolset 14.51 or later. Skia's archives call runtime helpers that 14.43 lacks
    (`__std_find_first_of_trivial_pos_1`), so an older toolset fails at the link. Today that
    failure names only the missing symbol; #8 makes the task say so before the link.
  - `clang-cl` on PATH for the skiko archive, because skiko compiles its Windows bindings with
    it: `winget install LLVM.LLVM`, or Visual Studio's "C++ Clang Compiler for Windows".

### Configure

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.11.1-extended-dev"
}

dependencies {
    implementation(compose.desktop.currentOs)
}

compose.desktop.application {
    mainClass = "hello.MainKt"
    nativeImage {
        graalvmHome = "/path/to/liberica-nik-25-full"      // or GRAALVM_HOME
        skikoStaticDirectory = file("/path/to/work/out/macos-arm64")
    }
}
```

The `nativeImage { }` block takes:

| Property | Meaning | Default |
|---|---|---|
| `graalvmHome` | The GraalVM that builds the image | `GRAALVM_HOME` |
| `skikoStaticDirectory` | `build-skiko-static-jvm.sh` output: `libskiko-static.a` (`skiko-static.lib` on Windows) and `skia/` | required |
| `imageName` | The executable's name | the application's package name |
| `metadataDirectory` | Where `runNativeImageAgent` writes and `packageNativeImage` reads the metadata | `src/main/native-image` |
| `buildArgs` | Extra arguments for native-image, after the plugin's own | none |

[`extended/native-image/hello`](https://github.com/thisisthepy/compose-multiplatform-extended/tree/extended/extended/native-image/hello)
is a complete project set up this way.

### Run

```sh
./gradlew runNativeImageAgent    # the application on GraalVM's JVM, writing src/main/native-image
./gradlew packageNativeImage     # build/compose/native-image/main/<name>
```

`runNativeImageAgent` runs the application on GraalVM's own `java` with the tracing agent,
merging what it sees into `metadataDirectory`. The agent writes on a clean exit, so close the
application rather than kill it. Run it again after a change that reaches new classes or
resources. Keep the directory with the sources, because `packageNativeImage` builds from it.

`packageNativeImage` writes one executable and nothing beside it. It is registered once per
application rather than once per build type: GraalVM optimises the image whatever the build
type, and ProGuard's output is not what an image should be analysed from.

## The Gradle plugin

### Today: org.jetbrains.compose

On `extended` the plugin keeps JetBrains' ID and coordinate, so it must come from the local
Maven repository ahead of the Gradle Plugin Portal (the `settings.gradle.kts` above):

- plugin ID: `org.jetbrains.compose` (`gradle-plugins/compose/build.gradle.kts`)
- artifact: `org.jetbrains.compose:compose-gradle-plugin`
- version: whatever `-Pdeploy.version` says; `gradle-plugins/gradle.properties` defaults to
  `9999.0.0-SNAPSHOT`. The probes and the `hello` sample use `1.11.1-extended-dev`.

Publish it to the local repository the way the probe workflows do:

```sh
cd gradle-plugins
./gradlew --no-daemon :compose:publishToMavenLocal \
    -Pdeploy.version=1.11.1-extended-dev -Pcompose.version=1.11.1
```

`-Pcompose.version` is the Compose version the plugin's aliases (`compose.desktop.currentOs`
and the rest) resolve to. Nothing is on a public repository yet.

### Planned: org.thisisthepy.compose

**Status: planned.** The branch
[`chore/thisisthepy-coordinates`](https://github.com/thisisthepy/compose-multiplatform-extended/tree/chore/thisisthepy-coordinates)
publishes the plugin under this fork's own name, so that it can sit beside JetBrains'
`org.jetbrains.compose` without either replacing the other. The implementation class and the
`compose { }` DSL stay JetBrains'.

- plugin ID: `org.thisisthepy.compose` (marker
  `org.thisisthepy.compose:org.thisisthepy.compose.gradle.plugin`)
- artifact: `org.thisisthepy.compose:compose-gradle-plugin`
- version: `<upstream version>-ext.<N>`, for example `1.11.1-ext.1`; `-ext.<N>-dev` for a local
  build
- the aliases resolve to compose-multiplatform-core-extended's `org.thisisthepy.compose.*`
  libraries

With that change the `plugins { }` line becomes:

```kotlin
plugins {
    id("org.thisisthepy.compose") version "1.11.1-ext.1-dev"
}
```
