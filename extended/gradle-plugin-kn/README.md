# Kotlin/Native desktop packaging

What a Kotlin/Native desktop application produces depends on whether it has a window:

| App | App | Package |
| --- | --- | --- |
| macOS, with a window (Compose) | `.app` | `.dmg` |
| Linux, with a window (Compose) | `.AppImage` | Flatpak (`.flatpak`) |
| Windows, with a window (Compose) | `.exe` | `.msix` |
| Command line (`appKind = Cli`) | `.kexe` (macOS, Linux), `.exe` (Windows) | none |

A command line program is not packaged: the executable is the output. On macOS the `.kexe`
is signed. `.msi` and `.deb` are not made for Kotlin/Native applications. The upstream JVM
packaging (`nativeDistributions` with `Msi`, `Deb` and so on) is untouched.

The code lives here, `extended/gradle-plugin-kn/`, and is compiled into the plugin
(`gradle-plugins/compose/build.gradle.kts` adds this directory as a source root). The upstream
edits are small hooks: `configureNativeApplication`, the native distribution settings, and the
Info.plist generation of the app-dir task.

## Tasks

Names end in the build type and the target, for example `...ReleaseMacosArm64`.

| Task | OS | Result |
| --- | --- | --- |
| `createDistributableNative...` | window app | the runnable layout: `.app`, `.AppDir` or the exe folder (with a recorded checksum) |
| `signDistributableNative...` | macOS | signs the `.app` (Developer ID, or ad hoc without an identity, with Gatekeeper instructions beside it) |
| `packageDmgNative...` | macOS | `.dmg` (when `TargetFormat.Dmg` is listed), with `checksums.sha256` |
| `notarizeDmgNative...` | macOS | notarizes and staples the dmg; skips with a message when credentials are absent |
| `lipoNative<Build>` | macOS | one universal executable, when `macOS { universalBinary = true }` and both targets exist |
| `packageAppImageNative...` | Linux | `.AppImage` through `appimagetool`, with `.zsync` when update information is set |
| `packageFlatpakNative...` | Linux | `.flatpak` bundle, with the manifest and metainfo beside it |
| `packageMsixNative...` | Windows | `.msix` and `.msixbundle` through MakeAppx; unsigned with `INSTALL.txt`, or signed with a certificate |
| `wackMsixNative...` | Windows | runs the Windows App Certification Kit on the package |
| `createExecutableNative...` | command line | `<name>.kexe`, or `<name>.exe` on Windows |
| `signExecutableNative...` | command line, macOS | keeps the linker's ad hoc signature while it verifies and signs ad hoc again when it does not; signs with the Developer ID identity when one is configured |
| `notarizeExecutableNative...` | command line, macOS | optional: submits a zip of the `.kexe` (a bare file cannot be stapled); skips without credentials |
| `runNative...` | host | runs the linked executable |
| `runDistributableNative...` | host | runs the executable inside the packaged layout, or the `.kexe` |
| `packageKotlinNative` | host | every output task of the host OS |

Targets of every desktop family are declared with `compose.nativeApplication.desktopTargets(...)`.
Only the binaries of the host OS get tasks, because the tools are the host's own.

## Settings

```kotlin
nativeDistributions {
    packageName = "Ember"
    packageVersion = "1.2.3"          // the msix version is 1.2.3.0 (Store) or 1.2.3.<revision>
    macOS { bundleID = "org.example.ember"; signing { identity = "..." } }
    msix {
        identityName = "Example.Ember"; publisher = "CN=Example"   // the Store's values for a reserved name
        channel = MsixChannel.Sideload                              // or Store (revision stays 0)
        certificateFile = "cert.pfx"; certificatePassword = "..."   // omit to leave it unsigned
    }
    flatpak { runtimeVersion = "24.08"; wayland = false; finishArgs = listOf("--share=network") }
    appImage { updateInformation = "gh-releases-zsync|owner|repo|latest|Ember-*-x86_64.AppImage.zsync" }
}
```

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
- `flatpak` and `flatpak-builder`, with the `org.freedesktop.Platform` and `Sdk` runtime of the
  version in `flatpak { runtimeVersion }`, for the Flatpak.
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
- The Windows SDK's `makeappx.exe` and `signtool.exe` for the msix (set
  `COMPOSE_WINDOWS_SDK_BIN` to their directory if they are not in `Windows Kits`), and its
  App Certification Kit for `wackMsixNative...`.

Windows applications declare per-monitor DPI awareness. The manifest is the same text the
GraalVM native image uses (`WindowsAppManifest`), embedded at link time and also written
beside the exe as `<name>.exe.manifest`, which Windows ignores when one is embedded.

## What the outputs contain

- macOS `.app`: `Contents/MacOS/<name>`, `Contents/Info.plist` (with
  `infoPlist { extraKeysRawXml }` and `fileAssociation`), the `.icns` icon,
  `Contents/Resources/compose-resources`. The `.dmg` carries a note on opening an ad hoc
  signed app that Gatekeeper blocks.
- Linux `.AppImage`: the executable and `compose-resources` side by side in `usr/bin`, a
  `.desktop` entry, AppStream metainfo and a PNG icon.
- Linux Flatpak: the same payload under `/app/lib/<name>`, a launcher in `/app/bin`, an X11
  sandbox (shared IPC, GPU), and the manifest in the JSON form `flatpak-builder` reads.
- Windows `.exe` folder: `<name>.exe`, `compose-resources`, the `.ico` and the manifest.
- Windows `.msix`: the same folder plus `AppxManifest.xml` (a full trust desktop application)
  and the tile images drawn from one PNG icon.
- Every package records its SHA-256 in `checksums.sha256` beside it.
- Command line: the single executable.

The update channels (Sparkle appcast, zsync hosting and a Flatpak repository, the Microsoft
Store and an App Installer feed) are tracked in issues #34, #35 and #36.
