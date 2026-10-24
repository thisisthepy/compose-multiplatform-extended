#!/usr/bin/env bash
# Usage: check-linux-chrome.sh <executable> <core-extended checkout> <scratch directory>
#
# Drives a running AWT-free application on Linux from outside, the way a person and a screen
# reader would, and requires what the window chrome and accessibility promise:
#
#   chrome   the window has no title bar of the window manager's; a drag of its caption moves
#            it by as far as the pointer went; a drag at each edge and corner changes its size
#            from that side only; a double click in the caption maximises and restores it; and
#            no frame was presented at a size the window had already left, and no size the
#            window took was left without a frame (the window's own resize count).
#   reader   walking the application's AT-SPI tree from the registry finds the text field, its
#            label and the button by role and name; setting the field's text and pressing the
#            button through AT-SPI reach the application; the caption's own Close button,
#            pressed the same way, closes the window.
#
# The window manager is openbox, which honours _NET_WM_MOVERESIZE and the sync counter, and
# the drags are the core fork's extended/window/scripts/wm-chrome.sh, the script its own window
# tests run. The reader is its extended/window/scripts/atspi-readback.c, built here against
# libatspi. Everything runs on a virtual display and a session bus of its own.
#
# The application has to be extended/native-image/hello-awtfree or its Kotlin/Native twin
# under extended/native-desktop/hello-awtfree: they stay up while HELLO_RUN_SECONDS lasts and
# then report their frames and the window's resize count.
#
# Needs xvfb-run, dbus-run-session, openbox, xdotool, xprop, at-spi2-core and libatspi's
# development files.
set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "usage: $0 <executable> <core-extended checkout> <scratch directory>" >&2
    exit 2
fi
executable="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
core="$(cd "$2" && pwd)"
mkdir -p "$3"
scratch="$(cd "$3" && pwd)"

scripts="$core/extended/window/scripts"
[[ -x "$scripts/wm-chrome.sh" ]] || chmod +x "$scripts/wm-chrome.sh"
reader="$scratch/atspi-readback"
cc -O1 -Wall -o "$reader" "$scripts/atspi-readback.c" $(pkg-config --cflags --libs atspi-2 gobject-2.0)

inner="$scratch/inner.sh"
cat > "$inner" <<'INNER'
#!/usr/bin/env bash
set -euo pipefail
executable="$1"; scripts="$2"; reader="$3"; scratch="$4"
log="$scratch/app.log"
title="안녕하세요, AwtFree"
# The window counts every size it takes and every frame it presents against the size it has
# at that moment, which DXC_REPORT_RESIZE asks of it.
sleep 1
( cd "$scratch" && DXC_REPORT_RESIZE=1 HELLO_RUN_SECONDS=240 exec "$executable" ) > "$log" 2>&1 &
app=$!
for _ in $(seq 1 50); do
    xdotool search --name "AwtFree" >/dev/null 2>&1 && break
    sleep 0.1
done
status=0
WM_DRAG_STEPS=40 "$scripts/wm-chrome.sh" "AwtFree" modern 46 || status=$?
echo "== wm-chrome.sh exit $status"
read_status=0
"$reader" "$title" --timeout 60 \
    --expect "entry:Name" --expect "label:Name" --expect "push button:click me" \
    --expect "push button:Minimize" --expect "push button:Maximize" --expect "push button:Close" \
    --set "Name=Ada" --press "click me" --press "Close" || read_status=$?
echo "== atspi-readback exit $read_status"
# Close, pressed through AT-SPI, ends the run; anything still up after that is stopped.
for _ in $(seq 30); do kill -0 "$app" 2>/dev/null || break; sleep 1; done
if kill -0 "$app" 2>/dev/null; then
    echo "== the application was still running after Close; stopping it"
    kill "$app" 2>/dev/null || true
    read_status=1
fi
app_status=0
wait "$app" || app_status=$?
echo "== the application's own report (exit $app_status)"
cat "$log"
failed=0
[[ "$status" -eq 0 ]] || { echo "fail  the window chrome is wrong; wm-chrome.sh says how above" >&2; failed=1; }
[[ "$read_status" -eq 0 ]] || { echo "fail  a reader did not find or could not use the controls; atspi-readback says how above" >&2; failed=1; }
grep -qE '^scene: presented [1-9][0-9]* frames of Compose content, [0-2] at a size' "$log" ||
    { echo "fail  a frame was presented at a size other than the window's" >&2; failed=1; }
resize="$(grep -E '^dxc resize: ' "$log" | tail -1 || true)"
[[ -n "$resize" ]] || { echo "fail  the application did not report the window's resize count" >&2; failed=1; }
if [[ -n "$resize" ]]; then
    steps="$(sed -n 's/.*steps=\([0-9]*\).*/\1/p' <<< "$resize")"
    stale="$(sed -n 's/.*stale=\([0-9]*\).*/\1/p' <<< "$resize")"
    stretched="$(sed -n 's/.*stretched=\([0-9]*\).*/\1/p' <<< "$resize")"
    [[ "${steps:-0}" -ge 8 ]] || { echo "fail  the edges took the window through ${steps:-0} sizes, fewer than eight" >&2; failed=1; }
    [[ "${stale:-0}" -le 2 ]] || { echo "fail  $stale frame(s) presented at a size the window had already left" >&2; failed=1; }
    [[ "$stretched" == 0 ]] || { echo "fail  $stretched size(s) the window took were left without a frame" >&2; failed=1; }
fi
[[ "$app_status" -eq 0 ]] || { echo "fail  the application ended with $app_status" >&2; failed=1; }
[[ "$failed" -eq 0 ]] && echo "ok    the chrome moves, resizes and maximises, and a reader finds and uses the controls"
exit "$failed"
INNER
chmod +x "$inner"
dbus-run-session -- xvfb-run -a -s '-screen 0 1600x1000x24' "$inner" "$executable" "$scripts" "$reader" "$scratch"
