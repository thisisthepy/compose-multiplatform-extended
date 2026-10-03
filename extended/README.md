# compose-multiplatform-extended

This is thisisthepy's fork of
[JetBrains/compose-multiplatform](https://github.com/JetBrains/compose-multiplatform), the
repository of the Compose Gradle plugin. Its work lives on the `extended` branch. The
repository root stays as upstream has it. What the fork adds is either in the plugin under
`gradle-plugins/` or under `extended/`.

Korean: [README_ko.md](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/README_ko.md)

## What it adds

| Addition | Status | Where |
|---|---|---|
| `packageNativeImage`: a Compose desktop application as one GraalVM native-image executable on macOS arm64, Linux x64 and Windows x64 | implemented | [`extended/native-image`](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/native-image/README.md) |
| `runNativeImageAgent`: the reachability metadata the image is built from | implemented | same |
| Windows: a DPI-aware executable, and a check for an old MSVC toolset before the link | planned | [#8](https://github.com/thisisthepy/compose-multiplatform-extended/pull/8) |
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
