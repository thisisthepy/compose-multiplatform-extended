# One executable from a Compose desktop application

What `compose.desktop` makes today is an application with a Java runtime packed beside it.
The goal here is a GraalVM native image instead: one file, no runtime, nothing unpacked at
start. This directory is where that is proved by hand before it becomes a plugin task.

| | |
|---|---|
| `support/` | A jar the image is built with: a feature that registers the desktop JNI libraries as linked in, and two substitutions for the places a Compose application opens a library by path. Plus the C the link needs (`native/`). |
| `hello/` | The smallest ordinary Compose desktop application, and `build-native.sh`, the hand-written build the plugin task replaces. |

## What makes it one file (macOS arm64, Liberica NIK 25 Full)

A Compose desktop application reaches native code through JNI libraries a JVM opens as
files: AWT's macOS toolkit, JAWT, and Skia. Each is linked in from a static archive and the
runtime is made to agree that it is there.

1. **AWT's toolkit.** NIK links `libawt` statically but leaves `libawt_lwawt` to be opened as
   a file, and libawt's own initialisation opens it by path from its own directory, which a
   single executable does not have. The image links `libawt_lwawt.a` and `libosxui.a`
   forced, the feature registers both as built in, and `System.load` is substituted so a path
   naming a built-in library loads that library instead of a file.
2. **The init functions have to be exported.** GraalVM resolves `JNI_OnLoad_<name>` at link
   time only for a fixed list of its own libraries; every other one is looked up by name at
   run time, in a binary whose symbols are otherwise not exported. The link exports
   `JNI_OnLoad_awt_lwawt`, `JNI_OnLoad_osxui` and `JNI_OnLoad_skiko` explicitly.
3. **Skia.** Built as a static archive by `build-skiko-static-jvm.sh` in the core fork
   (`extended/skiko/`). skiko's bindings are linked forced, Skia's own archives are not, and
   skiko's desktop loader, which unpacks and opens the shared library by path, is
   substituted with nothing. skiko defines no `JNI_OnLoad_skiko`; `native/static_onload.c`
   does.
4. **JAWT.** skiko opens `<java.home>/lib/libjawt.dylib` to find `JAWT_GetAWT`, and a native
   image has no `java.home`. The static archive replaces that object with one that calls
   the linked-in `JAWT_GetAWT`, and the image links `libjawt.a`.
5. **Other platforms' entry points.** skiko declares every platform's JNI methods, and the
   image refers to all of them. `native/generate-foreign-stubs.sh` defines the ones this
   platform does not implement as stops, from a first link made with lazy binding.

## Measured (2026-10-02, Mac mini M1, NIK 25.0.4.1)

- `hello` is one 82MB executable. Its only dependencies are system frameworks.
- Copied alone into an empty directory it opens its 800x600 window, and its self-check
  (`HELLO_SELF_CHECK=<png>`) draws the same content through Skia offscreen into a PNG that
  is byte for byte the one the JVM build draws.
- The tracing agent run is the same self-check on the JVM, so it needs nobody at the
  keyboard.

## Not yet

- Windows and Linux. On Windows the JDK's AWT is not shipped as static archives by the
  upstream GraalVM, and that is the open question for a single Windows executable.
- The plugin task. `hello/build-native.sh` is what it has to do.
