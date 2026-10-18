#!/usr/bin/env bash
# Usage: check-one-executable.sh <packageNativeImage output directory> <scratch directory>
#
# Proves that what packageNativeImage made is one executable: the output directory holds one
# file and nothing else, that file names nothing but the system's own libraries, and copied
# alone into an empty directory on a machine with no ICU data file it lays Korean out
# correctly, draws it and opens its window.
#
#   <output directory>   build/compose/native-image/<name>, as packageNativeImage writes it
#   <scratch directory>  emptied first; the executable runs from a directory inside it
#
# The application has to be one that runs the self-check with HELLO_SELF_CHECK set, as
# extended/native-image/hello-awtfree does: "text self-check: ok", the inked pixel count, and the
# frames of Compose content it presented.
#
# Needs a display. On Linux run it under `xvfb-run -a`. On Windows run it from Git Bash with
# the MSVC tools (dumpbin) on PATH.
#
# What it prints is the evidence: every file in the output directory, every library the
# executable names, its size, and the application's own report.
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <packageNativeImage output directory> <scratch directory>" >&2
    exit 2
fi

fail() {
    echo "fail  $1" >&2
    shift
    for line in "$@"; do echo "      $line" >&2; done
    exit 1
}

case "$(uname -s)" in
    Darwin) os=macos ;;
    Linux) os=linux ;;
    MINGW*|MSYS*|CYGWIN*) os=windows ;;
    *) fail "this checks macOS, Linux and Windows executables; $(uname -s) is not one" ;;
esac
if [[ "$os" == linux && -z "${DISPLAY:-}" ]]; then
    fail "DISPLAY is not set" "Run it under xvfb-run -a."
fi

output="$(cd "$1" && pwd)"

# 1. One file. Anything beside the executable is a file the application would have to carry.
echo "-- ls -la $output"
ls -la "$output"
files=()
while IFS= read -r -d '' file; do files+=("$file"); done < <(find "$output" -mindepth 1 -print0)
[[ ${#files[@]} -eq 1 ]] || fail "$output holds ${#files[@]} entries, not one executable" "${files[@]}"
binary="${files[0]}"
[[ -f "$binary" ]] || fail "$binary is not a file"

# 2. Only the system's libraries.
failures=0
not_ours() {
    echo "      $1" >&2
    failures=$((failures + 1))
}

# Named for what travels beside a Compose desktop application that is not one executable:
# Skia, the Java runtime and its AWT, and an ICU library of its own.
renderer_library='skiko|skia|jvm|jawt|awt|java|jli|fontmanager|lcms|mlib_image|javajpeg|icu(uc|i18n|data)'

case "$os" in
    macos)
        echo "-- otool -L $(basename "$binary")"
        otool -L "$binary" | tail -n +2
        while read -r library; do
            case "$library" in
                /usr/lib/*|/System/Library/*) ;;
                *) not_ours "not a system library: $library" ;;
            esac
            ! grep -qiE "$renderer_library" <<< "$(basename "$library")" ||
                [[ "$library" == /usr/lib/libicucore* ]] ||
                not_ours "names a library of the application's own: $library"
        done < <(otool -L "$binary" | tail -n +2 | awk '{ print $1 }')
        if otool -l "$binary" | grep -q LC_RPATH; then
            not_ours "the executable carries an rpath, so it expects something beside it"
        fi
        ;;
    linux)
        echo "-- readelf -d $(basename "$binary") (NEEDED, RPATH, RUNPATH)"
        readelf -d "$binary" | grep -E '\((NEEDED|RPATH|RUNPATH)\)' || true
        if readelf -d "$binary" | grep -qE '\((RPATH|RUNPATH)\)'; then
            not_ours "the executable carries a search path, so it expects something beside it"
        fi
        echo "-- ldd $(basename "$binary")"
        ldd "$binary"
        while read -r library; do
            [[ "$library" != */* ]] || { not_ours "named by path: $library"; continue; }
            ! grep -qiE "$renderer_library" <<< "$library" || not_ours "names a library of the application's own: $library"
            # `name => path`, or for the dynamic loader itself, the path alone.
            resolved="$(ldd "$binary" | awk -v name="$library" '
                $1 == name { print $3; exit }
                $1 ~ ("/" name "$") { print $1; exit }')"
            case "$resolved" in
                /lib/*|/lib64/*|/usr/lib/*|/usr/lib64/*) ;;
                *) not_ours "$library resolves to '${resolved:-nothing}', which is not a system directory" ;;
            esac
        done < <(readelf -d "$binary" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
        ;;
    windows)
        command -v dumpbin >/dev/null || fail "dumpbin is not on PATH" "Run this from an MSVC developer environment."
        echo "-- dumpbin -dependents $(basename "$binary")"
        dumpbin -nologo -dependents "$(cygpath -w "$binary")" | tr -d '\r' | sed -n '/Image has the following dependencies/,/Summary/p'
        system="$(cygpath -u "${SystemRoot:-C:\\Windows}")/System32"
        while read -r dll; do
            lower="$(tr '[:upper:]' '[:lower:]' <<< "$dll")"
            case "$lower" in
                vcruntime*|msvcp*|concrt*) not_ours "$dll is the Visual C++ runtime, which a clean Windows does not have" ;;
                api-ms-win-*|ext-ms-win-*) ;;
                *)
                    ! grep -qiE "$renderer_library" <<< "$lower" || { not_ours "names a library of the application's own: $dll"; continue; }
                    [[ -f "$system/$dll" || -f "$system/$lower" ]] || not_ours "$dll is not part of Windows"
                    ;;
            esac
        done < <(dumpbin -nologo -dependents "$(cygpath -w "$binary")" | tr -d '\r' |
            sed -n 's/^ *\([A-Za-z0-9_.-]*\.[dD][lL][lL]\)$/\1/p' | sort -fu)
        ;;
esac
[[ "$failures" -eq 0 ]] || fail "$(basename "$binary") needs $failures thing(s) that are not the system's"

size="$(wc -c < "$binary" | tr -d ' ')"
echo "-- size: $(awk -v b="$size" 'BEGIN { printf "%.2f MB (%d bytes)", b / 1000000, b }')"

# 3. No ICU data file anywhere the application could be pointed at one, so the text check
# below proves the executable needs none. A runner that ships one (a browser carries
# icudtl.dat) has to have it removed before this runs.
case "$os" in
    macos) roots=(/usr /opt /Library /Applications /Users) ;;
    linux) roots=(/usr /opt /home /snap) ;;
    windows) roots=("/c/Program Files" "/c/Program Files (x86)" "/c/Users" "$(cygpath -u "${SystemRoot:-C:\\Windows}")") ;;
esac
icu_files="$({ find "${roots[@]}" -name 'icudtl.dat' 2>/dev/null || true; } | head -20)"
[[ -z "$icu_files" ]] || fail "an ICU data file is on this machine, so the text check would not prove anything" $icu_files

# 4. Alone in an empty directory: the text check, the drawing, the window.
mkdir -p "$2"
scratch="$(cd "$2" && pwd)"
rm -rf "$scratch"
mkdir -p "$scratch/run"
name="$(basename "$binary")"
cp "$binary" "$scratch/run/$name"
echo "-- ls -la $scratch/run"
ls -la "$scratch/run"
log="$scratch/run.log"
png="$scratch/self-check.png"
status=0
hang="$scratch/hang.txt"
# Started in the background and watched, so that a run which never ends is stopped with the
# stacks of every thread printed: the macOS window has hung intermittently on the hosted runner
# after the text check, and a hang that only says "timed out" cannot be traced.
( cd "$scratch/run" && HELLO_SELF_CHECK="$png" exec "./$name" ) > "$log" 2>&1 &
pid=$!
for _ in $(seq 120); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
if kill -0 "$pid" 2>/dev/null; then
    case "$os" in
        macos) sample "$pid" 3 -file "$hang" > /dev/null 2>&1 || true ;;
        linux) command -v gdb > /dev/null &&
            gdb -p "$pid" -batch -ex 'thread apply all bt' > "$hang" 2>&1 || true ;;
        windows) : ;;
    esac
    kill -9 "$pid" 2> /dev/null || true
    wait "$pid" 2> /dev/null || true
    echo "== the application's own report (it was still running after 120 s and was stopped)"
    cat "$log"
    if [[ -s "$hang" ]]; then echo "== its threads when it was stopped"; cat "$hang"; fi
    fail "the executable did not end on its own within 120 s" "Its report and its threads are above."
fi
wait "$pid" || status=$?
echo "== the application's own report"
cat "$log"
[[ "$(ls -A "$scratch/run")" == "$name" ]] || fail "the executable left files beside itself" "$(ls -A "$scratch/run")"
grep -q "text self-check: ok" "$log" ||
    fail "the executable did not lay Korean text out correctly with no ICU data file" "Its own report is above."
grep -q "inked [1-9][0-9]* pixels" "$log" || fail "the executable did not say it drew the sample text" "Its own report is above."
[[ -s "$png" ]] || fail "the executable drew no PNG at $png"
# The application hosts a Compose scene in its window and says how many frames it presented
# and how many were drawn at a size other than the window's: some, and none.
grep -qE '^scene: presented [1-9][0-9]* frames of Compose content, 0 at a size' "$log" ||
    fail "the Compose scene presented no frames, or some at the wrong size" "Its own report is above."
# A Java exception the application survived is still a lookup the image is missing (JNI or
# reflection metadata), and it would fail somewhere a user is watching.
if grep -E '^(java|kotlin|org)\.[A-Za-z0-9_.$]*(Exception|Error)\b' "$log"; then
    fail "the executable reported an exception, which the image's metadata has to cover" "Its own report is above."
fi
[[ "$status" -eq 0 ]] || fail "the executable did not run to the end on its own (exit $status)"

echo "ok    $name is one executable: system libraries only, Korean laid out with no ICU data file"
