#!/usr/bin/env bash
# Tests central_bundle.py on a small Maven repository written here: what a complete bundle
# looks like passes, and each gap Central refuses (or a consumer would trip on) fails with
# its own message.
#
# Usage: extended/publish/tests/central-bundle.test.sh   (needs python3 and gpg)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tool="$here/../central_bundle.py"
work="$(mktemp -d "${RUNNER_TEMP:-$here}/cbt.XXXXXX")"
# gpg-agent's socket lives in GNUPGHOME, and an absolute path deep in a checkout can be
# longer than a socket name may be; a relative one from inside the work directory is not.
cd "$work"
export GNUPGHOME=gnupg
trap 'gpgconf --kill gpg-agent 2>/dev/null || true; rm -rf "$work"' EXIT
"$here/../throwaway-key.sh" "$GNUPGHOME" > /dev/null

failures=0
pass() { echo "ok    $1"; }
fail() { echo "FAIL  $1" >&2; failures=$((failures + 1)); }

V=1.11.1-ext.1
pom() { # <file> <group> <artifact> <version> [packaging] [extra xml]
    cat > "$1" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <modelVersion>4.0.0</modelVersion>
  <groupId>$2</groupId>
  <artifactId>$3</artifactId>
  <version>$4</version>
  <packaging>${5:-jar}</packaging>
  ${6:-}
</project>
XML
}
jar() { # <file> [entry]
    python3 -I - "$1" "${2:-}" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as z:
    z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n")
    if sys.argv[2]:
        z.writestr(sys.argv[2], "implementation-class=Example\n")
PY
}

# A repository like the ones the builds leave: a KMP root whose module points at a target,
# the target with a klib, a Gradle plugin with its marker, and something outside the namespace
# that must not be staged.
make_repo() {
    local repo="$1" d
    d="$repo/org/thisisthepy/compose/ui/ui/$V"; mkdir -p "$d"
    pom "$d/ui-$V.pom" org.thisisthepy.compose.ui ui "$V"
    jar "$d/ui-$V.jar"
    cat > "$d/ui-$V.module" <<JSON
{"formatVersion":"1.1","component":{"group":"org.thisisthepy.compose.ui","module":"ui","version":"$V"},
 "variants":[{"name":"linuxX64ApiElements-published","available-at":{"url":"../../ui-linuxx64/$V/ui-linuxx64-$V.module","group":"org.thisisthepy.compose.ui","module":"ui-linuxx64","version":"$V"}},
             {"name":"metadataApiElements","files":[{"name":"ui-$V.jar","url":"ui-$V.jar"}]}]}
JSON
    d="$repo/org/thisisthepy/compose/ui/ui-linuxx64/$V"; mkdir -p "$d"
    pom "$d/ui-linuxx64-$V.pom" org.thisisthepy.compose.ui ui-linuxx64 "$V" klib \
        "<dependencies><dependency><groupId>org.thisisthepy.compose.ui</groupId><artifactId>ui</artifactId><version>$V</version></dependency></dependencies>"
    echo klib > "$d/ui-linuxx64-$V.klib"
    jar "$d/ui-linuxx64-$V-sources.jar"
    d="$repo/org/thisisthepy/compose/compose-gradle-plugin/$V"; mkdir -p "$d"
    pom "$d/compose-gradle-plugin-$V.pom" org.thisisthepy.compose compose-gradle-plugin "$V"
    jar "$d/compose-gradle-plugin-$V.jar" META-INF/gradle-plugins/org.thisisthepy.compose.properties
    d="$repo/org/thisisthepy/compose/org.thisisthepy.compose.gradle.plugin/$V"; mkdir -p "$d"
    pom "$d/org.thisisthepy.compose.gradle.plugin-$V.pom" org.thisisthepy.compose org.thisisthepy.compose.gradle.plugin "$V" pom
    d="$repo/org/jetbrains/skiko/skiko-awt/0.144.6.1"; mkdir -p "$d"
    pom "$d/skiko-awt-0.144.6.1.pom" org.jetbrains.skiko skiko-awt 0.144.6.1
    echo junk > "$repo/org/thisisthepy/compose/ui/ui/maven-metadata-local.xml"
}

build() { # <name> -> stages, signs; prints the staging dir
    local repo="$work/$1-repo" out="$work/$1-out"
    rm -rf "$repo" "$out"
    make_repo "$repo"
    python3 -I "$tool" stage --from "$repo" --out "$out" --project thisisthepy/example > "$work/$1-stage.log"
    python3 -I "$tool" sign "$out" > /dev/null
    echo "$out"
}

expect_fail() { # <description> <pattern> <dir> [validate args]
    local description="$1" pattern="$2" out="$3"; shift 3
    if python3 -I "$tool" validate "$out" "$@" > "$work/log" 2>&1; then
        fail "$description: validate passed"; cat "$work/log" >&2
    elif grep -q -- "$pattern" "$work/log"; then
        pass "$description"
    else
        fail "$description: no '$pattern' in"; cat "$work/log" >&2
    fi
}

out="$(build good)"
if python3 -I "$tool" validate "$out" --version "$V" --require org.thisisthepy.compose.ui:ui --verify-signatures > "$work/log"; then
    pass "a complete bundle validates, signatures included"
else
    fail "a complete bundle does not validate"; cat "$work/log" >&2
fi
[[ ! -e "$out/org/jetbrains" ]] && pass "nothing outside org.thisisthepy is staged" || fail "org.jetbrains was staged"
[[ ! -e "$out/org/thisisthepy/compose/ui/ui/maven-metadata-local.xml" ]] && pass "local repository metadata is left out" || fail "maven-metadata-local.xml was staged"
grep -q "<scm>" "$out/org/thisisthepy/compose/ui/ui/$V/ui-$V.pom" && pass "the POM gets scm" || fail "no scm in the POM"
grep -q "empty org/thisisthepy/compose/ui/ui-linuxx64/$V/ui-linuxx64-$V-javadoc.jar" "$work/good-stage.log" &&
    pass "an empty javadoc jar is added and reported" || fail "the empty javadoc jar is not reported"
grep -q "ui-linuxx64-$V-sources.jar" "$work/good-stage.log" && fail "an existing sources jar was replaced" || pass "an existing sources jar is kept"
python3 -I "$tool" zip "$out" "$work/bundle.zip" > /dev/null
python3 -I -c 'import sys, zipfile; n = zipfile.ZipFile(sys.argv[1]).namelist(); sys.exit(0 if any(x.startswith("org/thisisthepy/compose/ui/ui/") and x.endswith(".pom.asc") for x in n) else 1)' "$work/bundle.zip" &&
    pass "the bundle zip is in Maven layout" || fail "the bundle zip layout"

expect_fail "the release version is enforced" "not 1.11.1-ext.2" "$out" --version 1.11.1-ext.2
expect_fail "a required coordinate is enforced" "required, and not in the bundle" "$out" --require org.thisisthepy.compose.skiko:skiko-awt

out="$(build noasc)"; rm "$out/org/thisisthepy/compose/ui/ui/$V/ui-$V.jar.asc"
expect_fail "a missing signature fails" "has no .asc" "$out"
out="$(build nosha512)"; rm "$out/org/thisisthepy/compose/ui/ui/$V/ui-$V.pom.sha512"
expect_fail "a missing sha512 fails" "has no .sha512" "$out"
out="$(build badmd5)"; echo 0 > "$out/org/thisisthepy/compose/ui/ui/$V/ui-$V.jar.md5"
expect_fail "a wrong checksum fails" "md5 does not match" "$out"
out="$(build tampered)"; echo x >> "$out/org/thisisthepy/compose/ui/ui-linuxx64/$V/ui-linuxx64-$V.klib"
expect_fail "a signature that does not verify fails" "does not verify" "$out" --verify-signatures
out="$(build nojavadoc)"; rm "$out/org/thisisthepy/compose/ui/ui/$V/ui-$V-javadoc.jar"
expect_fail "a missing javadoc jar fails" "no javadoc jar" "$out"
out="$(build dangling)"; rm -r "$out/org/thisisthepy/compose/ui/ui-linuxx64"
expect_fail "a variant pointing at an unpublished target fails" "linuxX64ApiElements-published points at" "$out"
out="$(build nomarker)"; rm -r "$out/org/thisisthepy/compose/org.thisisthepy.compose.gradle.plugin"
expect_fail "a plugin without its marker fails" "marker" "$out"
out="$(build nodesc)"
p="$out/org/thisisthepy/compose/ui/ui/$V/ui-$V.pom"
python3 -I - "$p" <<'PY'
import re, sys
text = open(sys.argv[1]).read()
open(sys.argv[1], "w").write(re.sub(r"<description>.*?</description>", "", text, flags=re.S))
PY
expect_fail "a POM without a description fails" "no description" "$out"

for bad in 1.11.1-SNAPSHOT 1.11.1-ext.1-dev 0.1.0; do
    repo="$work/v-repo"; out="$work/v-out"; rm -rf "$repo" "$out"
    d="$repo/org/thisisthepy/compose/window/common/$bad"; mkdir -p "$d"
    pom "$d/common-$bad.pom" org.thisisthepy.compose.window common "$bad" pom
    python3 -I "$tool" stage --from "$repo" --out "$out" > /dev/null
    python3 -I "$tool" sign "$out" > /dev/null
    expect_fail "version $bad is refused" "common:$bad" "$out"
done

repo="$work/g-repo"; out="$work/g-out"; rm -rf "$repo" "$out"
d="$repo/org/thisisthepy/compose/window/common/$V"; mkdir -p "$d"
pom "$d/common-$V.pom" org.jetbrains.compose common "$V" pom
python3 -I "$tool" stage --from "$repo" --out "$out" > /dev/null
python3 -I "$tool" sign "$out" > /dev/null
expect_fail "a POM whose group is not its directory's fails" "groupId is 'org.jetbrains.compose'" "$out"

# Two repositories with the same file: the first one named wins.
a="$work/a-repo"; b="$work/b-repo"; out="$work/ab-out"; rm -rf "$a" "$b" "$out"
make_repo "$a"; make_repo "$b"
echo other > "$b/org/thisisthepy/compose/ui/ui-linuxx64/$V/ui-linuxx64-$V.klib"
python3 -I "$tool" stage --from "$a" --from "$b" --out "$out" > "$work/log"
[[ "$(cat "$out/org/thisisthepy/compose/ui/ui-linuxx64/$V/ui-linuxx64-$V.klib")" == klib ]] && grep -q "kept the copy" "$work/log" &&
    pass "the first repository wins a conflict, and it is reported" || fail "conflict handling"

if [[ $failures -gt 0 ]]; then
    echo "$failures failure(s)" >&2
    exit 1
fi
echo "all passed"
