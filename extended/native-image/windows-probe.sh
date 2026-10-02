#!/usr/bin/env bash
# Stage A of the Windows probe: a stock native-image of hello, with nothing linked statically,
# to learn what a Windows build emits and whether it runs at all. Everything it finds goes to
# build/probe, which the workflow uploads.
set -uo pipefail
out=build/probe
mkdir -p "$out"
G="$(cygpath -u "$GRAALVM_HOME")"

echo "== JVM self-check and tracing agent" | tee -a "$out/summary.txt"
HELLO_SELF_CHECK="$(cygpath -w "$PWD/$out/jvm.png")" ./gradlew --no-daemon -q runNativeImageAgent writeRuntimeClasspath > "$out/agent.log" 2>&1
echo "agent exit=$?" | tee -a "$out/summary.txt"
ls -la src/main/native-image >> "$out/summary.txt" 2>&1

echo "== stock native-image" | tee -a "$out/summary.txt"
cp="$(cygpath -w "$PWD/build/classes/kotlin/main");$(cat build/runtime-classpath.txt)"
mkdir -p "$out/stock"
"$G/bin/native-image.cmd" -cp "$cp" --no-fallback \
    -H:ConfigurationFileDirectories="$(cygpath -w "$PWD/src/main/native-image")" \
    -o "$(cygpath -w "$PWD/$out/stock/hello")" hello.MainKt > "$out/native-image-stock.log" 2>&1
echo "native-image exit=$?" | tee -a "$out/summary.txt"
ls -la "$out/stock" >> "$out/summary.txt"

echo "== run stock" | tee -a "$out/summary.txt"
(cd "$out/stock" && HELLO_SELF_CHECK="$(cygpath -w "$PWD/native.png")" timeout 60 ./hello.exe > run.log 2>&1; echo "run exit=$?") | tee -a "$out/summary.txt"
cat "$out/stock/run.log" >> "$out/summary.txt" 2>/dev/null
cmp "$out/jvm.png" "$out/stock/native.png" && echo "stock render identical to JVM" | tee -a "$out/summary.txt"

echo "== static libraries GraalVM ships" | tee -a "$out/summary.txt"
ls "$G/lib/static/windows-amd64" >> "$out/summary.txt"
exit 0
