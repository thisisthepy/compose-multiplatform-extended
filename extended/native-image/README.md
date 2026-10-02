# One executable from a Compose desktop application

What `compose.desktop` makes today is an application with a Java runtime packed beside it.
`packageNativeImage` makes a GraalVM native image instead: one file, no runtime, nothing
unpacked at start. macOS arm64 so far.

```kotlin
compose.desktop.application {
    mainClass = "hello.MainKt"
    nativeImage {
        graalvmHome = "..."              // or GRAALVM_HOME; Liberica NIK Full on macOS
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

## Measured (2026-10-02, Mac mini M1, NIK 25.0.4.1)

`hello` built by `packageNativeImage` is one 82MB executable whose only dependencies are
system frameworks. Copied alone into an empty directory it opens its window, and its
self-check draws the same content through Skia offscreen into a PNG byte for byte the one the
JVM run draws.

## Not yet

- Windows and Linux. On Windows the JDK's AWT is not shipped as static archives by GraalVM,
  which is the open question for a single Windows executable.
- skiko's static archive is built by a script beside the core fork rather than resolved from a
  repository.
