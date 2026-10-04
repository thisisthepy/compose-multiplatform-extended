# Kotlin/Native desktop packaging

The Gradle plugin packages a Kotlin/Native desktop executable the way it packages a JVM
application: from the metadata in `nativeDistributions` (name, version, vendor, icons,
resources), with upstream's own `TargetFormat` names. What a window (Compose) app makes:

| OS | App | Package (`targetFormats`) | Store package |
| --- | --- | --- | --- |
| macOS | `.app` | `Dmg` | `Pkg` |
| Linux | `.AppImage` | `Deb`, `Rpm` | Flatpak (not yet, see below) |
| Windows | `.exe` | `Msi` (under decision), installer `.exe` (Velopack, not yet) | `.msix` (not yet) |

A command line program (`appKind = Cli`) is not packaged: it makes the bare executable, `.kexe`
on macOS and Linux and `.exe` on Windows, signed on macOS. The upstream JVM packaging is
untouched.

`.dmg` and the Linux and Windows installers install unsigned, and are the default way to
distribute. `.pkg` and `.msix` are store formats: they are documented for store submission
(re-signing by the store) or for when a certificate exists. A real `.AppImage` file beyond
what `packageAppImageNative` makes, Flatpak, MSIX and the Velopack installer are being built
separately. The `.msi` (WiX) is on hold while the owner decides.

The code lives here, `extended/gradle-plugin-kn/`, and is compiled into the plugin
(`gradle-plugins/compose/build.gradle.kts` adds this directory as a source root). The upstream
edits are small hooks: `configureNativeApplication`, the native distribution settings, and the
Info.plist generation of the app-dir task.

## Tasks

Names end in the build type and the target, for example `...ReleaseMacosArm64`.

| Task | OS | Result |
| --- | --- | --- |
| `createDistributableNative...` | window app | the runnable layout: `.app`, `.AppDir` or the exe folder |
| `signDistributableNative...` | macOS | signs the `.app` (Developer ID, or ad hoc without an identity, with Gatekeeper instructions beside it) |
| `packageDmgNative...` | macOS | `.dmg` (when `Dmg` is listed), with `checksums.sha256` |
| `packagePkgNative...` | macOS | `.pkg` through `productbuild` (when `Pkg` is listed), signed with the installer certificate of the same identity; `macOS { appStore = true }` for the Mac App Store |
| `notarizeDmgNative...` | macOS | notarizes and staples the dmg; skips with a message when credentials are absent |
| `lipoNative<Build>` | macOS | one universal executable, when `macOS { universalBinary = true }` and both targets exist |
| `packageAppImageNative...` | Linux | `.AppImage` through `appimagetool` |
| `packageDebNative...` | Linux | `.deb` through `dpkg-deb` (when `Deb` is listed) |
| `packageRpmNative...` | Linux | `.rpm` through `rpmbuild` (when `Rpm` is listed) |
| `packageExeNative...`, `packageMsiNative...` | Windows | the folder as a `.zip`, and an `.msi` through WiX 3 (as of the first version; both are being reworked) |
| `createExecutableNative...` | command line | `<name>.kexe`, or `<name>.exe` on Windows |
| `signExecutableNative...` | command line, macOS | keeps the linker's ad hoc signature while it verifies and signs ad hoc again when it does not; signs with the Developer ID identity when one is configured |
| `notarizeExecutableNative...` | command line, macOS | optional: submits a zip of the `.kexe` (a bare file cannot be stapled); skips without credentials |
| `runNative...` | host | runs the linked executable |
| `runDistributableNative...` | host | runs the executable inside the packaged layout, or the `.kexe` |
| `packageKotlinNative` | host | every output task of the host OS |

Targets of every desktop family are declared with `compose.nativeApplication.desktopTargets(...)`.
Only the binaries of the host OS get tasks, because the tools are the host's own.

## Prerequisites

### macOS

- Xcode command line tools (`codesign`, `lipo`, `hdiutil`, `productbuild`, `xcrun`).
- Signing: a Developer ID Application certificate in a keychain, named by
  `nativeDistributions.macOS.signing { identity = ... }`. Without an identity the app is
  signed ad hoc, which runs locally and on Apple Silicon but cannot be distributed.
- Notarization: `nativeDistributions.macOS.notarization { appleID, password, teamID }`
  (an app-specific password). Without them `notarizeDmgNative...` prints
  "Skipping notarization" and succeeds, so the same build runs on CI, which has no
  Apple credentials.

### Linux

- `appimagetool` on `PATH`, for the AppImage (needs FUSE, or runs with
  `--appimage-extract-and-run`, which the task passes).
- `dpkg-deb` (package `dpkg`), for the deb, and `rpmbuild` (package `rpm-build`), for the rpm.
- To link and run a window: the X11 development libraries (`libx11-dev libxext-dev libxi-dev
  libxrandr-dev libxcursor-dev libxcb1-dev`); the deb depends on their runtime packages.

### Windows

Every `mingwX64` target ships as a final MSVC executable. Kotlin/Native compiles MinGW
objects, which are rewritten so the MSVC linker accepts them, and the MSVC linker links them
with Skia/skiko and the window C layer, which are MSVC builds. There is no MinGW-only path.
The owner decided this on 2026-10-05:

> [user] "Msvc 있어야 하는게 뭐 어때서? 나는 물어보는거잖아. Mingw 우회 구현을 넣지 마. Extended는 Mingw 타겟도 전부 최종 msvc 앱으로 나가도록 하면 되는거지 그냥."

So the build needs:

- Visual Studio Build Tools with the MSVC toolset v14.51 or later, `clang-cl`, and the
  Windows SDK. The plugin finds them with `vswhere` at build start and stops with an
  install message when one is missing.
- WiX Toolset 3 for the msi: set `WIX_PATH` to its binaries directory.

Windows applications declare per-monitor DPI awareness. The manifest is the same text the
GraalVM native image uses (`WindowsAppManifest`), embedded at link time and also written
beside the exe as `<name>.exe.manifest`, which Windows ignores when one is embedded.

## What the packages contain

- macOS: `Contents/MacOS/<name>`, `Contents/Info.plist` (with `infoPlist { extraKeysRawXml }`
  and `fileAssociation`), the `.icns` icon, `Contents/Resources/compose-resources`.
- Linux: the executable and `compose-resources` side by side (`usr/bin` in the AppDir,
  `/opt/<name>` in the deb and rpm with a `/usr/bin` symlink), a `.desktop` entry and a PNG icon.
- Windows: `<name>.exe`, `compose-resources`, the `.ico` and the manifest.

Every package records its SHA-256 in `checksums.sha256` beside it. Command line programs make
the single executable.
