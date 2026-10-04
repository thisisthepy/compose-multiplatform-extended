#!/bin/bash
# Usage: probe.sh <dest-dir: /Applications or ~/Applications>
set +e
DEST="$1"; W="$PWD/work"; FEED="$W/feed"; export PROBE_LOG="$W/app.log"
H(){ echo; echo "=== $* ==="; }
mkapp(){ # version outdir
  local v=$1 out=$2
  PROBE_VERSION=$v cargo build --release --manifest-path probes/velopack-macos/Cargo.toml --target-dir "$W/target" 2>&1 | tail -3
  rm -rf "$out"; mkdir -p "$out/Probe.app/Contents/MacOS"
  cp "$W/target/release/probe" "$out/Probe.app/Contents/MacOS/probe"
  cat > "$out/Probe.app/Contents/Info.plist" <<P
<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>probe</string><key>CFBundleIdentifier</key><string>org.example.probe</string>
<key>CFBundleName</key><string>Probe</string><key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>$v</string><key>CFBundleVersion</key><string>$v</string>
<key>LSMinimumSystemVersion</key><string>12.0</string></dict></plist>
P
  codesign --force --deep -s - "$out/Probe.app"; codesign -dv "$out/Probe.app" 2>&1 | grep -E 'Signature|flags|Identifier'
}
mkdir -p "$W" ; rm -f "$PROBE_LOG"
if [ ! -d "$FEED" ]; then
  H "build + vpk pack"
  sw_vers; uname -m; dotnet --version
  dotnet tool install -g vpk 2>&1 | tail -2; export PATH="$PATH:$HOME/.dotnet/tools"; vpk --version
  for v in 1.0.0 1.0.1; do
    mkapp $v "$W/src-$v"
    vpk pack -u Probe -v $v -p "$W/src-$v/Probe.app" -e probe -o "$FEED" --noInst 2>&1 | tail -15
  done
  ls -la "$FEED"
fi
H "dmg from the portable zip's .app (1.0.0)"
Z=$(ls "$FEED"/*Portable*.zip "$FEED"/*.zip 2>/dev/null | grep -v -- '-1.0.1' | head -1); echo "zip: $Z"
rm -rf "$W/z"; mkdir -p "$W/z"; ditto -x -k "$Z" "$W/z"; ls -la "$W/z"
APP_SRC=$(find "$W/z" -maxdepth 2 -name '*.app' | head -1); echo "app: $APP_SRC"
codesign -dv "$APP_SRC" 2>&1 | grep -E 'Signature|flags'; codesign --verify --deep --strict "$APP_SRC"; echo "verify rc=$?"
rm -f "$W/Probe.dmg"; hdiutil create -volname Probe -srcfolder "$APP_SRC" -ov -format UDZO "$W/Probe.dmg" | tail -1
Q="0081;$(printf %x $(date +%s));Safari;"
xattr -w com.apple.quarantine "$Q" "$W/Probe.dmg"
H "mount dmg, copy to $DEST"
MP=$(hdiutil attach "$W/Probe.dmg" -nobrowse -readonly | tail -1 | awk -F'\t' '{print $NF}'); echo "mount: $MP"
mkdir -p "$DEST"; rm -rf "$DEST/Probe.app"; ditto "$MP/Probe.app" "$DEST/Probe.app"; hdiutil detach "$MP" -quiet
APP="$DEST/Probe.app"
xattr -w com.apple.quarantine "$Q" "$APP"; xattr -l "$APP"
ls -ld "$DEST" "$APP"; id
report(){ H "state: $1"; plutil -extract CFBundleShortVersionString raw "$APP/Contents/Info.plist"; codesign -dv "$APP" 2>&1 | grep -E 'Signature|flags|TeamIdentifier'; codesign --verify --deep --strict --verbose=2 "$APP" 2>&1; echo "verify rc=$?"; xattr -p com.apple.quarantine "$APP"; echo "quarantine rc=$?"; spctl --assess --type execute -vvv "$APP" 2>&1; echo "spctl rc=$?"; ls -la "$APP/Contents/MacOS"; }
report "before launch"
H "launch via open -W (plain, no feed), 60s timeout"
( open -n -W "$APP" ; echo "open rc=$?" ) & OP=$!
sleep 20; cat "$PROBE_LOG" 2>&1; kill $OP 2>/dev/null; pkill -x probe
H "launch with feed via open (env via launchctl), 120s"
launchctl setenv PROBE_FEED "$FEED"; launchctl setenv PROBE_LOG "$PROBE_LOG"
( open -n -W "$APP" ; echo "open rc=$?" ) & OP=$!
for i in $(seq 1 60); do grep -q 'started version=1.0.1' "$PROBE_LOG" 2>/dev/null && break; sleep 2; done
sleep 5; H "app log"; cat "$PROBE_LOG"; kill $OP 2>/dev/null
H "direct exec fallback if open did not update"
if ! grep -q 'started version=1.0.1' "$PROBE_LOG"; then
  PROBE_FEED="$FEED" timeout 120 "$APP/Contents/MacOS/probe"; echo "direct rc=$?"; sleep 10; cat "$PROBE_LOG"
fi
launchctl unsetenv PROBE_FEED; pkill -x probe
report "after update"
H "launch 1.0.1 plain"; rm -f "$PROBE_LOG"; launchctl unsetenv PROBE_FEED
( open -n -W "$APP"; echo "open rc=$?" ) & OP=$!; sleep 15; cat "$PROBE_LOG"; kill $OP 2>/dev/null; pkill -x probe
find "$APP" -maxdepth 3 | head -30
ls -la "$DEST" /Applications/ | grep -i -E 'probe|velo'
