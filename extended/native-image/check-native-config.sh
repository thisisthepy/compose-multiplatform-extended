#!/usr/bin/env bash
# Checks that the window layer reaches native code only through the generated upcall table,
# and that the image's configuration has no runtime reflection for it.
#
# Usage: check-native-config.sh <core-extended checkout> [--strict <reachability-metadata.json>...]
#
# Source checks, always:
#   - no Class.forName, java.lang.reflect, kotlin.reflect or by-name method lookup in the
#     window modules (extended/window, graalvm modules and common);
#   - every file that declares a @CEntryPoint also builds a CEntryPointLiteral, so C reaches
#     Kotlin through a table the image builder resolves, never through a name lookup.
# Kotlin/Native window modules (extended/window/native): no kotlin.reflect.full, ServiceLoader
#   or by-name lookup of classes and selectors.
# Metadata checks, always, for each file given:
#   - no reflection or jni entry names a class of org.thisisthepy.compose.window.
# With --strict (the AWT-free image) the metadata must also have:
#   - no jni entry other than org.jetbrains.skiko classes (skiko's JNI is linked by stubs).
set -euo pipefail

core="${1:?usage: check-native-config.sh <core-extended checkout> [--strict] [metadata.json...]}"
shift
strict=0
if [[ "${1:-}" == "--strict" ]]; then strict=1; shift; fi

fail=0
note() { echo "FAIL: $*" >&2; fail=1; }

window="$core/extended/window"
[[ -d "$window" ]] || { echo "no $window" >&2; exit 2; }

mapfile -t sources < <(find "$window" -type f \( -name '*.kt' -o -name '*.java' \) \
    -not -path '*/test/*' -not -path '*/build/*' | sort)
echo "window sources checked: ${#sources[@]}"
[[ ${#sources[@]} -gt 0 ]] || { echo "no window sources found" >&2; exit 2; }

pattern='Class\.forName|java\.lang\.reflect|kotlin\.reflect|\.getDeclaredMethod\(|\.getMethod\(|\.getDeclaredField\(|MethodHandles\.lookup|ServiceLoader'
if hits=$(grep -nE "$pattern" "${sources[@]}" | grep -vE '^\S+:[0-9]+:\s*(//|\*|/\*)'); then
    note "runtime reflection in the window sources:"; echo "$hits" >&2
fi

entry_files=0
for f in "${sources[@]}"; do
    if grep -q '@CEntryPoint' "$f"; then
        entry_files=$((entry_files + 1))
        grep -q 'CEntryPointLiteral' "$f" || note "$f declares @CEntryPoint but builds no CEntryPointLiteral"
    fi
done
echo "files with upcall entry points: $entry_files"
[[ $entry_files -gt 0 ]] || note "no @CEntryPoint found in the window sources; the upcall table is missing"

# Kotlin/Native window modules: C and Objective-C reach Kotlin through staticCFunction or
# @CName entries, and nothing is looked up by name at run time. K/N has no JVM reflection, so
# what is left to forbid is kotlin.reflect.full, ServiceLoader and Objective-C class lookups by name. A selector for a target-action menu item is not one: it names a method of an object the code itself created.
mapfile -t kn_sources < <(find "$window/native" -type f -name '*.kt' \
    -not -path '*/test/*' -not -path '*/build/*' 2>/dev/null | sort)
echo "Kotlin/Native window sources checked: ${#kn_sources[@]}"
if [[ ${#kn_sources[@]} -gt 0 ]]; then
    kn_pattern='kotlin\.reflect\.full|ServiceLoader|Class\.forName|NSClassFromString|objc_getClass'
    if hits=$(grep -nE "$kn_pattern" "${kn_sources[@]}" | grep -vE '^\S+:[0-9]+:\s*(//|\*|/\*)'); then
        note "runtime lookup by name in the Kotlin/Native window sources:"; echo "$hits" >&2
    fi
fi

for m in "$@"; do
    [[ -f "$m" ]] || { note "no metadata file $m"; continue; }
    echo "metadata: $m"
    bad=$(jq -r '[(.reflection // [])[], (.jni // [])[]] | .[] | (.type // .name // "" | tostring)
        | select(test("org\\.thisisthepy\\.compose\\.window"))' "$m")
    [[ -z "$bad" ]] || note "reflection or jni entries for window classes in $m: $bad"
    if [[ $strict -eq 1 ]]; then
        other=$(jq -r '(.jni // [])[] | (.type // .name // "" | tostring)
            | select(startswith("org.jetbrains.skiko") | not)' "$m")
        [[ -z "$other" ]] || note "jni entries other than skiko's in $m: $other"
    fi
done

[[ $fail -eq 0 ]] && echo "native config check passed"
exit $fail
