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
for t in java.awt.Toolkit java.awt.Component java.awt.GraphicsEnvironment java.awt.Window java.awt.image.BufferedImage javax.swing.JComponent sun.awt.SunToolkit java.awt.EventQueue sun.java2d.SunGraphics2D; do
    log="$out/forbidden-$t.log"
    "$GRAALVM_HOME/bin/native-image" -cp "$cp" probe.MainKt --no-fallback \
        -H:+UnlockExperimentalVMOptions "-H:ReportAnalysisForbiddenType=$t" \
        --add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.annotate=ALL-UNNAMED --add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.jdk=ALL-UNNAMED \
        -Dcompose.nativeimage.awtFree=true -o "$out/probe-$t" > "$log" 2>&1
    echo "$t exit=$?" | tee -a "$out/summary.txt"
done

# What the jars on the classpath register for native-image themselves.
meta="$out/jar-metadata"
mkdir -p "$meta"
IFS=: read -ra jars <<< "$(cat build/runtime-classpath.txt)"
for j in "${jars[@]}"; do
    unzip -l "$j" 2>/dev/null | awk '{print $4}' | grep "^META-INF/native-image/" > "$meta/list.tmp" || true
    if [ -s "$meta/list.tmp" ]; then
        echo "== $(basename "$j")" >> "$meta/summary.txt"; cat "$meta/list.tmp" >> "$meta/summary.txt"
        for f in $(cat "$meta/list.tmp"); do
            case "$f" in *.json) unzip -p "$j" "$f" | grep -c -E 'java\.awt|sun\.awt|javax\.swing' | sed "s|^|awt entries in $f: |" >> "$meta/summary.txt";; esac
        done
    fi
done
rm -f "$meta/list.tmp"

# One analysis with no forbidden type, to print the whole call tree: it shows every path
# from main (or a build-time root) into java.awt, which forbidding one type at a time does not.
"$GRAALVM_HOME/bin/native-image" -cp "$cp" probe.MainKt --no-fallback \
    --add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.annotate=ALL-UNNAMED --add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.jdk=ALL-UNNAMED \
    -Dcompose.nativeimage.awtFree=true -H:+PrintAnalysisCallTree -H:ReportsPath="$out/reports" \
    -o "$out/probe-calltree" > "$out/calltree.log" 2>&1
echo "calltree exit=$?" | tee -a "$out/summary.txt"
ls -la "$out/reports" >> "$out/summary.txt" 2>&1
gzip -f "$out"/reports/* 2>/dev/null || true

# Whether the module graph can be cut at java.desktop.
"$GRAALVM_HOME/bin/native-image" -cp "$cp" probe.MainKt --no-fallback \
    --add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.annotate=ALL-UNNAMED --add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.jdk=ALL-UNNAMED \
    -Dcompose.nativeimage.awtFree=true --limit-modules=java.base,java.logging,java.management,java.naming,java.xml,java.sql,jdk.unsupported,jdk.management \
    -o "$out/probe-limit" > "$out/limit-modules.log" 2>&1
echo "limit-modules exit=$?" | tee -a "$out/summary.txt"
