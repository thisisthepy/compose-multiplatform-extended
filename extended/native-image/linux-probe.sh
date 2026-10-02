#!/usr/bin/env bash
# The Linux x64 probe, in stages, each recorded in build/probe whatever happens to the others:
#   0  what NIK's static JDK archives and Skia's archives define, for designing the link
#   1  the application on GraalVM's JVM: its self-check render and the tracing agent
#   B  skiko's JVM natives as a static archive, built on this runner
#   C  packageNativeImage, then the executable alone in an empty directory
# GUI runs go through xvfb-run. Run from extended/native-image/hello with GRAALVM_HOME set.
set -uo pipefail
out="$PWD/build/probe"
mkdir -p "$out"
G="$GRAALVM_HOME"
static="$G/lib/static/linux-amd64/glibc"
log() { echo "$*" | tee -a "$out/summary.txt"; }

defines() {  # <archive> <regex>: how many defined global symbols match
    nm -g --defined-only "$1" 2>/dev/null | awk 'NF == 3 {print $3}' | grep -cE "$2" || true
}

log "== 0: archives"
for a in libawt libawt_xawt libawt_headless libfontmanager libfreetype libjavajpeg liblcms libmlib_image libjawt; do
    f="$static/$a.a"
    [[ -f "$f" ]] || { log "$a.a: missing"; continue; }
    log "$a.a: $(ar t "$f" | wc -l) members, onload: $(nm -g --defined-only "$f" 2>/dev/null | awk 'NF==3 && $3 ~ /^JNI_OnLoad/ {printf "%s ", $3}')" \
        "FT_=$(defines "$f" '^FT_') hb_=$(defines "$f" '^hb_') jpeg_=$(defines "$f" '^jpeg_') png_=$(defines "$f" '^png_') cms=$(defines "$f" '^cms')"
done

log "== 1: JVM self-check and tracing agent"
HELLO_SELF_CHECK="$out/jvm.png" xvfb-run -a ./gradlew --no-daemon -q runNativeImageAgent > "$out/agent.log" 2>&1
log "agent exit=$?"
ls -la "$out/jvm.png" >> "$out/summary.txt" 2>&1
cp -r src/main/native-image "$out/metadata" 2>/dev/null

log "== B: static skiko"
bash ../../../core-extended/extended/skiko/build-skiko-static-jvm.sh "$RUNNER_TEMP/skiko-static" > "$out/skiko-static.log" 2>&1
log "skiko static exit=$?"
tail -3 "$out/skiko-static.log" >> "$out/summary.txt"
skiko_out="$RUNNER_TEMP/skiko-static/out/linux-x64"
ls -la "$skiko_out" "$skiko_out/skia" >> "$out/summary.txt" 2>&1
for f in "$skiko_out"/skia/*.a; do
    [[ -f "$f" ]] && log "$(basename "$f"): FT_=$(defines "$f" '^FT_') hb_=$(defines "$f" '^hb_') jpeg_=$(defines "$f" '^jpeg_') png_=$(defines "$f" '^png_')"
done

log "== C: packageNativeImage"
SKIKO_STATIC="$skiko_out" ./gradlew --no-daemon --info packageNativeImage > "$out/package.log" 2>&1
log "package exit=$?"
grep -E "error|Error|undefined reference|multiple definition|failed" "$out/package.log" | head -80 >> "$out/summary.txt"
cp -r build/tmp/packageNativeImage "$out/package-work" 2>/dev/null
rm -rf "$out/package-work/support" "$out/package-work/relinked"
dir="build/compose/native-image/main"
log "output directory:"; ls -la "$dir" >> "$out/summary.txt" 2>&1
exe="$dir/native-image-hello"
if [[ -f "$exe" ]]; then
    single="$RUNNER_TEMP/single"
    rm -rf "$single" && mkdir -p "$single" && cp "$exe" "$single/"
    log "size: $(stat -c %s "$single/native-image-hello") bytes"
    log "ldd:"; ldd "$single/native-image-hello" >> "$out/summary.txt" 2>&1
    log "NEEDED:"; readelf -d "$single/native-image-hello" | grep NEEDED >> "$out/summary.txt"
    log "exported JNI_OnLoad:"; nm -D --defined-only "$single/native-image-hello" | grep JNI_OnLoad >> "$out/summary.txt"
    log "files in the empty directory before the run:"; ls -la "$single" >> "$out/summary.txt"
    (cd "$single" && HELLO_SELF_CHECK="$single/native.png" xvfb-run -a timeout 120 ./native-image-hello > run.log 2>&1; echo "run exit=$?") | tee -a "$out/summary.txt"
    head -60 "$single/run.log" >> "$out/summary.txt" 2>/dev/null
    cp "$single/run.log" "$out/run.log" 2>/dev/null
    cp "$single/native.png" "$out/native.png" 2>/dev/null
    if cmp -s "$out/jvm.png" "$single/native.png"; then log "render identical to JVM"; else log "render differs or missing"; fi
    sha256sum "$out/jvm.png" "$single/native.png" >> "$out/summary.txt" 2>&1
    cp "$exe" "$out/" 2>/dev/null
fi
exit 0
