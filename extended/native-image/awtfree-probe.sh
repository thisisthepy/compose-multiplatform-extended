#!/usr/bin/env bash
# Runs native-image analysis on the probe's classpath with one AWT type forbidden at a time,
# so each failure prints its own reachability chain. Run from extended/native-image/awtfree-probe
# with GRAALVM_HOME set and ./gradlew writeRuntimeClasspath already run.
set -uo pipefail
out="$PWD/build/probe"
mkdir -p "$out"
cp="$(cat build/runtime-classpath.txt)"
for t in java.awt.Toolkit java.awt.Component java.awt.GraphicsEnvironment java.awt.Window java.awt.image.BufferedImage javax.swing.JComponent sun.awt.SunToolkit; do
    log="$out/forbidden-$t.log"
    "$GRAALVM_HOME/bin/native-image" -cp "$cp" probe.MainKt --no-fallback \
        -H:+UnlockExperimentalVMOptions "-H:ReportAnalysisForbiddenType=$t" \
        -o "$out/probe-$t" > "$log" 2>&1
    echo "$t exit=$?" | tee -a "$out/summary.txt"
done
