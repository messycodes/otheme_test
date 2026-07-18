# OTheme

## Overview
OTheme (`com.chuishui.otheme`) is a native Android app (Kotlin, Jetpack Compose) for
managing OPPO/ColorOS `.theme` packages on rooted devices. It supports two privileged
execution modes for filesystem operations:

- **SU mode** (`SuFileOperations.kt`) — runs commands via `su -c`.
- **Shizuku mode** (`FileService.kt`, bound over `IFileService.aidl`) — runs as a
  Shizuku user service.

## Stack
- Kotlin + Jetpack Compose, single `app` Gradle module.
- Built with Gradle (`gradlew`) — this workspace does not have a JDK/Android SDK
  toolchain installed, so builds must be verified on a machine with those installed
  (e.g. Android Studio) rather than in this Replit shell.

## Theme installation (current design)
Installing a `.theme` file no longer unpacks/extracts it — the raw `.theme` file is
injected as-is into the system's built-in theme module directory
(`/system_ext/media/themeInner/<original-filename>.theme`). There is no install
confirmation dialog anymore; tapping "安装" starts installation immediately and
opens a live log dialog showing each step (`[+] ...`, `[OK] ...`, `[FAIL] ...`).

**SU mode** (`SuFileOperations.installThemeWithLog`) installs "safely" by preferring
a real root module over a manual remount:
1. `detectRootType()` probes for `ksud` / `magisk` / `apd` on the su PATH to identify
   Magisk / KernelSU / APatch.
2. If found, `installThemeAsModule` builds a minimal Magisk-format module zip
   (`module.prop` + `system_ext/media/themeInner/<file>.theme`) in `context.cacheDir`
   and installs it via the manager's own CLI (`magisk --install-module`,
   `ksud module install`, `apd module install`), streaming stdout/stderr line-by-line
   to the UI via `execSuCommandStreaming`. The manager's own overlay/mount handling
   is used instead of manually remounting `/system_ext`.
3. If no supported root manager is detected (or the module install fails), it falls
   back to the old direct-copy approach: remount `/system_ext` rw, `mkdir -p`, `cp`
   the file in unchanged, `chmod 644`/`chown root:root`, remount ro.

**Shizuku mode** (`FileService.installTheme`/`installThemeFromFd`) cannot reach
`/data/adb` root-manager tooling from the Shizuku shell, so it always uses the
direct-copy fallback described above; the UI still shows synthetic log lines around
the (non-streaming) AIDL call so the log dialog behaves consistently across modes.

Unrelated theme functions that still operate on the legacy `/data/theme` path
(active/applied theme state) were left untouched: `backupTheme`, `uninstallTheme`,
`getThemeInfo`, `getInstalledThemeInfo`, `extractWallpaper`, `restartProcesses`.

## User preferences
None recorded yet.
