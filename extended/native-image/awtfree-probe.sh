#!/usr/bin/env bash
# Runs native-image analysis on the probe's classpath with one AWT type forbidden at a time,
# so each failure prints its own reachability chain. Run from extended/native-image/awtfree-probe
# with GRAALVM_HOME set and ./gradlew writeRuntimeClasspath already run.
set -uo pipefail
out="$PWD/build/probe"
mkdir -p "$out"
cp="$(cat build/runtime-classpath.txt)"
# The plugin's own AWT-free substitutions, compiled the way packageNativeImage compiles them.
sub="$out/support"
mkdir -p "$sub/src/org/jetbrains/compose/nativeimage" "$sub/classes"
res=../../../gradle-plugins/compose/src/main/resources/org/jetbrains/compose/desktop/nativeimage
cp "$res/AwtFreeSubstitutions.java.txt" "$sub/src/org/jetbrains/compose/nativeimage/AwtFreeSubstitutions.java"
"$GRAALVM_HOME/bin/javac" -d "$sub/classes" --add-modules org.graalvm.nativeimage "$sub"/src/org/jetbrains/compose/nativeimage/*.java || exit 1
cp="$cp:$sub/classes"
for t in java.awt.Toolkit java.awt.Component java.awt.GraphicsEnvironment java.awt.Window java.awt.image.BufferedImage javax.swing.JComponent sun.awt.SunToolkit; do
    log="$out/forbidden-$t.log"
    "$GRAALVM_HOME/bin/native-image" -cp "$cp" probe.MainKt --no-fallback \
        -H:+UnlockExperimentalVMOptions "-H:ReportAnalysisForbiddenType=$t" \
        -Dcompose.nativeimage.awtFree=true -o "$out/probe-$t" > "$log" 2>&1
    echo "$t exit=$?" | tee -a "$out/summary.txt"
done
