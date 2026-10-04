# Kotlin/Native desktop packaging

What a Kotlin/Native desktop application produces depends on whether it has a window:

| App | macOS | Linux | Windows |
| --- | --- | --- | --- |
| With a window (Compose), `appKind = Gui` | `.app` (and `.dmg`) | `.AppImage` | `.exe` in an application folder |
| Command line, `appKind = Cli` | `.kexe`, signed | `.kexe` | `.exe` |

A command line program is not packaged: the executable is the output. Installers (`.msi`,
`.deb` and the like) are not made for Kotlin/Native applications. The upstream JVM packaging
(`nativeDistributions` with `Msi`, `Deb` and so on) is untouched.

The code lives here, `extended/gradle-plugin-kn/`, and is compiled into the plugin
(`gradle-plugins/compose/build.gradle.kts` adds this directory as a source root). The upstream
edits are small hooks: `configureNativeApplication`, the native distribution settings, and the
Info.plist generation of the app-dir task.

## Tasks

Names end in the build type and the target, for example `...ReleaseMacosArm64`.

| Task | OS | Result |
| --- | --- | --- |
| `createDistributableNative...` | Gui | the runnable layout: `.app`, `.AppDir` or the exe folder |
| `signDistributableNative...` | Gui, macOS | signs the `.app` (Developer ID, or ad hoc without an identity) |
| `packageDmgNative...` | Gui, macOS | `.dmg` (when `TargetFormat.Dmg` is listed) |
| `notarizeDmgNative...` | Gui, macOS | notarizes and staples the dmg; skips with a message when credentials are absent |
| `lipoNative<Build>` | Gui, macOS | one universal executable, when `macOS { universalBinary = true }` and both targets exist |
| `packageAppImageNative...` | Gui, Linux | `.AppImage` through `appimagetool` |
| `createExecutableNative...` | Cli | `<name>.kexe`, or `<name>.exe` on Windows |
| `signExecutableNative...` | Cli, macOS | keeps the linker's ad hoc signature while it verifies and signs ad hoc again when it does not; signs with the Developer ID identity when one is configured |
| `notarizeExecutableNative...` | Cli, macOS | optional: submits a zip of the `.kexe` (a bare file cannot be stapled); skips without credentials |
| `runNative...` | host | runs the linked executable |
| `runDistributableNative...` | host | runs the executable inside the packaged layout, or the `.kexe` |
| `packageKotlinNative` | host | every output task of the host OS |

Targets of every desktop family are declared with `compose.nativeApplication.desktopTargets(...)`.
Only the binaries of the host OS get tasks, because the tools are the host's own.

## Prerequisites

### macOS

- Xcode command line tools (`codesign`, `lipo`, `hdiutil`, `xcrun`).
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
- To link and run a window: the X11 development libraries (`libx11-dev libxext-dev libxi-dev
  libxrandr-dev libxcursor-dev libxcb1-dev`).

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

Windows applications declare per-monitor DPI awareness. The manifest is the same text the
GraalVM native image uses (`WindowsAppManifest`), embedded at link time and also written
beside the exe as `<name>.exe.manifest`, which Windows ignores when one is embedded.

## What the outputs contain

- macOS `.app`: `Contents/MacOS/<name>`, `Contents/Info.plist` (with
  `infoPlist { extraKeysRawXml }` and `fileAssociation`), the `.icns` icon,
  `Contents/Resources/compose-resources`.
- Linux `.AppImage`: the executable and `compose-resources` side by side in `usr/bin`, a
  `.desktop` entry and a PNG icon.
- Windows: `<name>.exe`, `compose-resources`, the `.ico` and the manifest in one folder.
- Command line: the single executable.
