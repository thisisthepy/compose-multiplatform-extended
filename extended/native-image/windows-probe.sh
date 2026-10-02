#!/usr/bin/env bash
# The Windows probe, in stages, each recorded in build/probe whatever happens to the others:
#   A  a stock native image, nothing linked statically, to learn the baseline
#   B  skiko's JVM natives as a static archive, built on this runner
#   C  packageNativeImage, the plugin's single-executable path
set -uo pipefail
out="$PWD/build/probe"
mkdir -p "$out"
G="$(cygpath -u "$GRAALVM_HOME")"
log() { echo "$*" | tee -a "$out/summary.txt"; }

log "== JVM self-check and tracing agent"
HELLO_SELF_CHECK="$(cygpath -w "$out/jvm.png")" ./gradlew --no-daemon -q runNativeImageAgent writeRuntimeClasspath > "$out/agent.log" 2>&1
log "agent exit=$?"

run_check() {  # <dir> <exe>
    (cd "$1" && HELLO_SELF_CHECK="$(cygpath -w "$PWD/native.png")" timeout 90 "./$2" > run.log 2>&1; echo "run exit=$?") | tee -a "$out/summary.txt"
    head -40 "$1/run.log" >> "$out/summary.txt" 2>/dev/null
    if cmp -s "$out/jvm.png" "$1/native.png"; then log "render identical to JVM"; else log "render differs or missing"; fi
    log "files beside the executable:"; ls -la "$1" >> "$out/summary.txt"
    log "imports:"; dumpbin.exe //nologo //dependents "$(cygpath -w "$1/$2")" | grep -i "\.dll" >> "$out/summary.txt"
}

log "== A: stock native-image"
mkdir -p "$out/stock"
cp="$(cygpath -w "$PWD/build/classes/kotlin/main");$(cat build/runtime-classpath.txt)"
printf '%s\n' "-cp" "\"${cp//\\/\\\\}\"" "--no-fallback" \
    "-H:ConfigurationFileDirectories=\"$(cygpath -w "$PWD/src/main/native-image" | sed 's/\\/\\\\/g')\"" \
    "-o" "\"$(cygpath -w "$out/stock/hello" | sed 's/\\/\\\\/g')\"" "hello.MainKt" > "$out/stock.args"
"$G/bin/native-image.cmd" "@$(cygpath -w "$out/stock.args")" > "$out/native-image-stock.log" 2>&1
log "native-image exit=$?"
[[ -f "$out/stock/hello.exe" ]] && run_check "$out/stock" hello.exe

log "== B: static skiko"
JAVA_HOME="$GRAALVM_HOME" bash ../../../core-extended/extended/skiko/build-skiko-static-jvm.sh "$(cygpath -u "$RUNNER_TEMP")/skiko-static" > "$out/skiko-static.log" 2>&1
log "skiko static exit=$?"
tail -3 "$out/skiko-static.log" >> "$out/summary.txt"
skiko_out="$(cygpath -u "$RUNNER_TEMP")/skiko-static/out/windows-x64"
ls -la "$skiko_out" >> "$out/summary.txt" 2>&1

log "== C: packageNativeImage"
SKIKO_STATIC="$(cygpath -w "$skiko_out")" ./gradlew --no-daemon --info packageNativeImage > "$out/package.log" 2>&1
log "package exit=$?"
grep -E "error|Error|LNK|unresolved|failed" "$out/package.log" | head -60 >> "$out/summary.txt"
cp -r build/tmp/packageNativeImage "$out/package-work" 2>/dev/null
exe="build/compose/native-image/main/native-image-hello.exe"
if [[ -f "$exe" ]]; then
    mkdir -p "$out/single" && cp "$exe" "$out/single/"
    run_check "$out/single" native-image-hello.exe
fi
exit 0
