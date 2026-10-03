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

## Running the Windows probe locally

`extended/native-image/windows-probe.sh` is what CI runs, and it works from Git Bash
started inside an x64 Native Tools Command Prompt, with LLVM's `clang-cl` on PATH (skiko
compiles its Windows bindings with it; `winget install LLVM.LLVM`). Put Git's `/usr/bin`
ahead of `C:\Windows\System32` on PATH before running it: `vcvars64.bat` prepends System32,
where `bash` is WSL's launcher rather than an interpreter, and a nested script invoked as
`bash` would run under WSL and fail.

## Not yet

- Windows on a real display: the probe renders off screen, and opening a window by hand is
  still to be done.
- Linux arm64, and Linux on a real display with GL rather than Xvfb.
- skiko's static archive is built by a script beside the core fork rather than resolved from a
  repository.
