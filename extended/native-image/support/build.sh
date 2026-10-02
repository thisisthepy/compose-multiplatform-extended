#!/usr/bin/env bash
# Compiles the support jar against the GraalVM it will be used with. GRAALVM_HOME names it.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${GRAALVM_HOME:?GRAALVM_HOME has to name a GraalVM}"
rm -rf "$here/build" && mkdir -p "$here/build/classes"
"$GRAALVM_HOME/bin/javac" -d "$here/build/classes" --add-modules org.graalvm.nativeimage \
    $(find "$here/src" -name '*.java')
cp -r "$here/src/META-INF" "$here/build/classes/"
(cd "$here/build/classes" && "$GRAALVM_HOME/bin/jar" cf ../native-image-support.jar .)
echo "$here/build/native-image-support.jar"
