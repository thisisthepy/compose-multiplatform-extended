# One executable from a Compose desktop application

What `compose.desktop` makes today is an application with a Java runtime packed beside it.
`packageNativeImage` makes a GraalVM native image instead: one file, no runtime, nothing
unpacked at start. macOS arm64 and Linux x64 so far.

```kotlin
compose.desktop.application {
    mainClass = "hello.MainKt"
    nativeImage {
        graalvmHome = "..."              // or GRAALVM_HOME; Liberica NIK Full
        skikoStaticDirectory = file("...") // build-skiko-static-jvm.sh output, see below
    }
}
```

```
./gradlew runNativeImageAgent   # the application on GraalVM's JVM, writing src/main/native-image
./gradlew packageNativeImage    # build/compose/native-image/main/<name>
```

The plugin is this fork's, `id("org.thisisthepy.compose")`, and its aliases
(`compose.desktop.currentOs` and the rest) resolve to compose-multiplatform-core-extended's
libraries, `org.thisisthepy.compose.*` at `<upstream version>-ext.<N>`; that repository's
`extended/COORDINATES.md` lists them. Nothing of either is published yet, so both go to the
local Maven repository first, as development builds, which is what the probe workflows do:

```
# in compose-multiplatform-core-extended (JDK 21 in ANDROIDX_JDK21)
./gradlew :mpp:publishComposeJbToMavenLocal -Pcompose.platforms=jvm \
    -Pjetbrains.publication.libraries=COMPOSE,COMPOSE_MATERIAL3 -Pextended.publication.dev=true
# in gradle-plugins
./gradlew :compose:publishToMavenLocal -Pdeploy.version=1.11.1-ext.1-dev \
    -Pcompose.version=1.11.1-ext.1-dev -Pcompose.material3.version=1.11.0-alpha07-ext.1-dev
```

The agent writes its metadata on a clean exit, so close the application rather than kill
it. `hello/` closes itself when `HELLO_SELF_CHECK` names a PNG to render into, which is how
the run needs nobody at the keyboard.

Skia comes from `extended/skiko/build-skiko-static-jvm.sh` in
compose-multiplatform-core-extended.

## What makes it one file

A Compose desktop application reaches native code through JNI libraries a JVM opens as
files: AWT's macOS toolkit, JAWT, and Skia. Each is linked in from a static archive and the
runtime is made to agree that it is there. The task does all of this; the code is
`AbstractNativeImageTask` and the support sources it compiles with the build's own GraalVM
(`gradle-plugins/compose/src/main/resources/org/jetbrains/compose/desktop/nativeimage/`).

1. **AWT's toolkit.** NIK links `libawt` statically but leaves `libawt_lwawt` to be opened as
   a file, by path from libawt's own directory, which a single executable does not have.
   The image links `libawt_lwawt.a` and `libosxui.a` forced, a feature registers both as
   built in, and `System.load` is substituted so a path naming a built-in library loads that
   library instead.
2. **The init functions are exported.** GraalVM resolves `JNI_OnLoad_<name>` at link time only
   for a fixed list of its own libraries and looks every other one up by name at run time.
3. **Skia.** skiko's bindings are linked forced (their JNI entry points are reached by name);
   Skia's archives are not (its module archives repeat its core objects). skiko's desktop
   loader is substituted with nothing, and `JNI_OnLoad_skiko`, which skiko does not define,
   comes from `static_onload.c`.
4. **JAWT.** skiko opens `<java.home>/lib/libjawt.dylib`, and a native image has no
   `java.home`. The static archive replaces that object with one calling the linked-in
   `JAWT_GetAWT`, and the image links `libjawt.a`.
5. **Other platforms' entry points.** skiko declares every platform's JNI methods and the image
   refers to all of them. The task reads skiko's classes for native methods, subtracts what
   the archive defines, and defines the rest as stops.

## On Linux x64

The same pieces, with what GraalVM does on Linux taken into account:

- GraalVM links AWT on Linux as shared libraries copied beside the image. The task registers
  `awt`, `awt_xawt`, `fontmanager`, `javajpeg`, `lcms` and `mlib_image` as built in, and
  GraalVM then links them itself from NIK's `lib/static/linux-amd64/glibc` as ordinary
  archives. Their `JNI_OnLoad_<name>` are on GraalVM's link time list, so nothing is
  exported. Forcing them in as whole archives as well defines everything twice.
- AWT loads `libawt_xawt.so` by path from libawt's directory (`System.load`), answered by the
  same substitution as on macOS.
- JAWT is not registered by anything; `libjawt.a` is linked in a group with Skia's archives and
  the AWT archives JAWT reaches into. NIK's `libfreetype.a` closes the group.
- skiko and libawt both define a global `jvm`. The static archive makes skiko's weak, so they
  are one variable, which AWT's initialisation sets.
- `sun.font` declares a Windows-only native the image then refers to; it is defined as a stop
  with skiko's foreign entry points.
- X11 AWT's toolkit thread finds `Thread.yield` through JNI, only when its poll has no timeout.
  A tracing agent run catches that only sometimes, so the feature registers it.
- GraalVM still writes `libjava.so`, `libjvm.so`, `libawt_headless.so` and `libfreetype.so`
  beside the executable, which needs none of them; the task removes them.

Linux cannot be built from another system. `linux-probe.sh` and the workflow
`native-image-linux-probe.yml` build and check it on a GitHub ubuntu runner, the GUI under
`xvfb-run`.

## Measured (2026-10-02, Mac mini M1, NIK 25.0.4.1)

`hello` built by `packageNativeImage` is one 82MB executable whose only dependencies are
system frameworks. Copied alone into an empty directory it opens its window, and its
self-check draws the same content through Skia offscreen into a PNG byte for byte the one the
JVM run draws.

## Measured on Linux x64 (2026-10-02, GitHub ubuntu-latest runner, NIK 25.0.4.1)

`hello` built by `packageNativeImage` is one 116066760 byte executable; the output directory
holds nothing else. Its `NEEDED` entries are `libz`, `libstdc++`, `libGL`, `libX11`, `libXext`,
`libXi`, `libXrender`, `libfontconfig`, `libm`, `libgcc_s`, `libc` and the loader: no
`libawt*`, no `libskiko`, no `libjvm`. Copied alone into an empty directory and run under
`xvfb-run`, its self-check PNG is byte for byte the one the JVM run draws (sha256
`426c589e...b0aa`), in each of three runs. Under Xvfb skiko finds no GL context and falls back
to its software renderer, in the JVM run and in the executable alike.

## Measured on Windows x64 (2026-10-03, GitHub windows-latest runner, NIK 25.0.4.1)

`hello` built by `packageNativeImage` is one 98820096 byte executable; the output directory
holds nothing else. It imports only Windows' own DLLs and the UCRT (`api-ms-win-crt-*`), which
is part of Windows 10 and later: no `VCRUNTIME140`, no `MSVCP140`, no `awt.dll`, no `jvm.dll`.
Run alone from an empty directory, its self-check PNG is byte for byte the one the JVM run
draws (sha256 `82a49a0b...8fb5`). Probe run 37096588029.

What it took, all in `windowsLink` and the static skiko archive:
- The JDK's static libraries are /MD and JetBrains' Skia /MT. Every C++ library links from a
  copy with the `RuntimeLibrary` guard blanked, including the C++ standard library, because
  GraalVM adds libraries of its own built for the DLL runtime. vcruntime and the C++ library
  are linked in; the UCRT stays the system DLL.
- No `opengl32.lib`: skiko defines the few OpenGL entry points it uses itself.
- Skia's ICU data is compiled into the skiko archive, since Skia otherwise looks for
  `icudtl.dat` beside the executable.

## Which GraalVM

An application with `windowing = Awt` needs Liberica NIK Full, the one distribution that ships the
JDK's static AWT libraries. An AWT-free application on macOS needs a GraalVM that runs `main` on
the process's first thread when told `-H:-RunMainInNewThread`: the AppKit window hands its calls
to that thread, which has to be serving them. Liberica NIK 25.0.4.1 runs `main` on a new thread
on macOS whatever the option says, and the first call that crosses waits forever; upstream
GraalVM honours it. `packageNativeImage` gives the option itself for an AWT-free macOS image, and
refuses to start with Liberica NIK there (it reads `IMPLEMENTOR` in the GraalVM's `release`
file), naming the GraalVM to use instead. The gate builds the AWT-free samples on macOS with upstream GraalVM 25.0.1,
and `hello-awtfree` exits with a message rather than hanging when `main` is on another thread.

## The one-executable gate

`check-one-executable.sh` is what the "One executable (GraalVM, <os>)" jobs in
`extended-native-image.yml` run on `hello-awtfree`. It fails unless the output directory holds
one file, that file names only the system's libraries (`otool -L`, `readelf -d` and `ldd`,
`dumpbin -dependents`), and, copied alone into an empty directory on a runner with every
`icudtl.dat` removed, it lays Korean out correctly (every glyph found, the word around a
syllable whole, lines broken between words), draws it into a PNG, and presents frames of
Compose content in its window, none at a size other than the window's. `hello-awtfree` is a
Compose application with no AWT: the core fork's scene module hosts its content in the GraalVM
window, through the window's own surface (`appKitWindowSurface`, `x11WindowSurface`). A run
that does not end within 120 s is stopped with every thread's stack printed. It then runs the
executable once more, watched (every file it touches on Linux, through `strace`; every library
it loads on macOS, through `DYLD_PRINT_LIBRARIES`), and fails if it looks for a Java runtime:
anything under the JDK or GraalVM on the runner, a module image, `jvm.cfg`, `libjvm` or
`libjava`. What the gate does not check is the build machine: building from source needs a JDK,
for Gradle, the Kotlin compiler and native-image, and the gate says so in a step of its own.
An executable that needs an ICU data file shows it there: a double click would select one
syllable, and lines would wrap inside words.

On macOS the gate also checks the window as a screen reader meets it, and runs the executable
twice, with `HELLO_TITLE_BAR` unset (the Normal title bar) and set to `Simple`. Each run walks
its own accessibility elements in process, through the objects AppKit's accessibility server
answers every client from, because a hosted runner has not trusted any process for
accessibility and an outside client is refused there. The run prints what the system's API
answered an outside client, with its error number (`kAXErrorAPIDisabled`, -25211, on a runner
that has not granted the trust). It fails unless the three system buttons are there, the content
runs under the title bar, no title text is drawn, the caption's height and button room match the
buttons' own frames, the corner radius is the core fork's measured table for that style and
release, and the field and the button are found by their roles and names. On macOS 26 the
Normal title bar's buttons must sit further in than the Simple one's.

The "One executable (Kotlin/Native, <os>)" jobs run the same gate on
`extended/native-desktop/hello-awtfree-kn`: the same content (`hello-awtfree/src/shared`) in the
core fork's Kotlin/Native window (`native-macos`), linked by Gradle with the native desktop
plugin against the core fork's `macosArm64` Compose, with no JVM. The macOS cell is there; the
Linux cells come with the Linux window, and Windows after it.

Measured by the gate, run 39028163166 (2026-10-17, NIK 25.0.4.1), before `hello-awtfree` hosted
a Compose scene: it is one
executable of 37.62 MB on macOS arm64, 46.27 MB on Linux x64 and 44.96 MB on Linux arm64, naming
only system libraries, and on runners with no `icudtl.dat` each prints
`text self-check: ok (no unresolved glyphs, word "반갑습니다", ...)` and inks the sample text.

## Running the Windows probe locally

`extended/native-image/windows-probe.sh` is what CI runs, after the two local publications
above, and it works from Git Bash
started inside an x64 Native Tools Command Prompt, with LLVM's `clang-cl` on PATH (skiko
compiles its Windows bindings with it; `winget install LLVM.LLVM`). Put Git's `/usr/bin`
ahead of `C:\Windows\System32` on PATH before running it: `vcvars64.bat` prepends System32,
where `bash` is WSL's launcher rather than an interpreter, and a nested script invoked as
`bash` would run under WSL and fail.

Visual Studio's own clang-cl works too, from the "C++ Clang Compiler for Windows" component,
under `VC\Tools\Llvm\x64\bin`; a C++ workload does not install it by default. Keep MSVC's
`cl`, `link`, `lib` and `dumpbin` ahead of clang-cl on PATH, the way CI pairs an MSVC
environment with a separate LLVM.

## Not yet

- Windows on a real display: the probe renders off screen, and opening a window by hand is
  still to be done.
- Linux on a real display with GL rather than Xvfb.
- The AWT-free gate on Windows x64, which needs the Win32 window layer in the core fork.
- skiko's static archive is built by a script beside the core fork rather than resolved from a
  repository.
