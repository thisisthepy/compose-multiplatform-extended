#!/usr/bin/env bash
# The hand-written proof that the plugin task replaces: this sample as one macOS executable.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
N="${GRAALVM_HOME:?GRAALVM_HOME has to name Liberica NIK Full}"
S="$N/lib/static/darwin-aarch64"
SK="${SKIKO_STATIC:?SKIKO_STATIC has to name libskiko-static.a}"
mkdir -p "$here/build/static"
cc -c -O2 -arch arm64 -I"$N/include" -I"$N/include/darwin" "$here/../support/native/static_onload.c" -o "$here/build/static/static_onload.o"
CP="$here/build/classes/kotlin/main:$(cat "$here/build/runtime-classpath.txt"):$here/../support/build/native-image-support.jar"
rm -rf "$here/build/native" && mkdir -p "$here/build/native/bin"
extra=("$@")
# Skia as ordinary archives, libskia first so the core objects come from it.
skia_dir="$(dirname "$SK")/skia"
skia=("-H:NativeLinkerOption=$skia_dir/libskia.a")
for library in "$skia_dir"/*.a; do
    [[ "$(basename "$library")" == "libskia.a" ]] || skia+=("-H:NativeLinkerOption=$library")
done
"$N/bin/native-image" -cp "$CP" -H:ConfigurationFileDirectories="$here/build/agent" --no-fallback \
  "-Dcompose.nativeimage.staticLibraries=awt_lwawt:sun_lwawt|sun_java2d_metal|sun_java2d_opengl|sun_font|sun_awt,osxui:com_apple_laf,skiko:org_jetbrains_skia|org_jetbrains_skiko" \
  "-H:NativeLinkerOption=-Wl,-force_load,$S/libawt_lwawt.a" "-H:NativeLinkerOption=-Wl,-force_load,$S/libosxui.a" \
  "-H:NativeLinkerOption=-Wl,-force_load,$S/libjawt.a" "-H:NativeLinkerOption=-Wl,-force_load,$SK" \
  "-H:NativeLinkerOption=$here/build/static/static_onload.o" "${skia[@]}" \
  "-H:NativeLinkerOption=-framework" "-H:NativeLinkerOption=Metal" "-H:NativeLinkerOption=-framework" "-H:NativeLinkerOption=MetalKit" "-H:NativeLinkerOption=-framework" "-H:NativeLinkerOption=IOKit" \
  "-H:NativeLinkerOption=-Wl,-exported_symbol,_JNI_OnLoad_awt_lwawt" "-H:NativeLinkerOption=-Wl,-exported_symbol,_JNI_OnLoad_osxui" \
  "-H:NativeLinkerOption=-Wl,-exported_symbol,_JNI_OnLoad_skiko" \
  ${extra[@]+"${extra[@]}"} \
  -o "$here/build/native/bin/hello" hello.MainKt
