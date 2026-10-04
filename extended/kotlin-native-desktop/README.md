# Kotlin/Native desktop programs, with or without Compose

`org.thisisthepy.kotlin.native.desktop` is a Gradle plugin for Kotlin/Native desktop programs.
It needs no Compose. Its id follows the Kotlin Gradle plugin's (`org.jetbrains.kotlin.native.cocoapods`),
with `org.jetbrains` replaced by `org.thisisthepy` the way the fork does for Compose.

## What it does on Windows

Kotlin/Native compiles for Windows with MinGW. With this plugin every `mingwX64` executable ends
as a program linked by Microsoft's linker: the file `linkReleaseExecutableMingwX64` writes is an
MSVC-linked `.exe`. It needs no MinGW DLL and no Visual C++ runtime DLL beside it. The tasks keep
Kotlin's names, so `runReleaseExecutableMingwX64` runs it too. On macOS and Linux the plugin
changes nothing and the program builds as Kotlin/Native always does.

```kotlin
plugins {
    kotlin("multiplatform") version "2.2.20"
    id("org.thisisthepy.kotlin.native.desktop") version "0.1.0-extended-dev"
}

kotlin {
    mingwX64 {
        binaries { executable { entryPoint = "hello.main" } }
    }
    msvc {
        // subsystem = WindowsSubsystem.Windows       // no console window
        // manifest.set(file("app.manifest")); icon.set(file("app.ico"))
    }
}
```

## What you need on Windows

Visual Studio or the Build Tools for Visual Studio with the workload "Desktop development with
C++" (the MSVC x64 build tools and a Windows SDK). The build checks for them before it compiles
anything and stops with install instructions if they are missing. "C++ Clang tools for Windows"
is needed only when `requireClangCl` is set. `minimumToolset` sets the oldest MSVC accepted, and
the error lists the versions this has been verified with. The MSVC link runs on Windows only.

## How it works

`MsvcLinkPipeline` (package `org.thisisthepy.kotlin.gradle.nativedesktop.msvc`) does the link,
and other plugin code can call it directly:

1. The program is also built as a Kotlin/Native static library, with a generated function that
   calls your `entryPoint`.
2. Copies of that library and of MinGW's GCC runtime are rewritten so MSVC's linker keeps their
   meaning: static constructors move to `.CRT$XCU`, and unwind data becomes associative to its code.
3. A generated C `main` (or `WinMain` for the Windows subsystem) calls the Kotlin entry point.
4. `link.exe` links it all against the UCRT from Windows, with vcruntime linked in.
5. `linkCppStandardLibrary` (off by default) also links MSVC's `libcpmt.lib` with its runtime
   guard blanked, for programs that link C++ built with MSVC. Prebuilt MSVC libraries in
   `libraries` get the same blanking.

## Try it

`../native-desktop/hello-plain` is a program with no Compose. The `extended-kotlin-msvc`
workflow builds and runs it on Linux, macOS and Windows, checks which DLLs the Windows
executable needs, and links it once without the MinGW rewrite to prove the rewrite is what makes
it work.
