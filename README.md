# LocalAdb

LocalAdb is an on-device Android ADB client and native Shizuku-compatible
manager. It uses the N2K02 theme and updater, the canonical mRadio single
header and VRec-style Home status cards.

## Navigation

- Home: verified UID 2000, pairing/TCP state, native Shizuku server health,
  eligible Android permission grants and shortcuts.
- Adb: aShell You-inspired internal command console with output/exit codes,
  searchable bounded history, bookmarks, copy/paste and interrupt.
- Settings > ADB connection: pairing, watchdog, boot/reboot restore, TCP/5555.
- Settings > Apps: native Shizuku access controls separate from the verified
  Android developer-permission grant/revoke controls.
- Settings > Updates: shared updater. Logs and Diagnostics removed.
- Quit: interrupts commands, stops native Shizuku, cancels recovery and closes ADB.

## Native Shizuku (no extra manager app installed)

After LocalAdb verifies a live shell UID 2000 it:

1. Attempts its own eligible Android permissions and confirms readback.
2. Verifies LocalAdb owns the globally named Shizuku manager permission.
3. Streams the bundled Apache-2.0 Shizuku-Next payload to the verified shell
   using a UID-2000-restricted ContentProvider.
4. Checks the exact SHA-256 on the device and launches it using app_process.
5. Receives the server Binder through the official Shizuku provider.
6. Uses server permission flags for Shizuku grant/revoke and explicit approval
   requests from compatible apps. Never claims a grant without readback.

The payload is bundled as an APK asset, NOT installed as a separate Android app.
See THIRD_PARTY_NOTICES.md for the source, license and SHA-256.

Important compatibility limits: globally named MANAGER/API_V23 permissions can
be owned by only one installed Shizuku manager. If another manager owns these,
LocalAdb fails closed and reports the conflict. Resolve it manually; LocalAdb
will not uninstall or impersonate another manager. Older Shizuku clients that
hard-code the official manager package moe.shizuku.privileged.api may not find
the LocalAdb manager (dev.n2k02.localadb). Real-device testing is necessary
before calling it fully drop-in compatible.

## ADB command privacy and safety

The console executes user-entered commands only after re-verifying a live shell
UID 2000 under the transport mutex. These commands may change device state.
Console history and captured output remain in memory; only explicit bookmarks
persist in private storage. Quit stops command execution and clears history.
ADB TLS pairing identity is persisted privately. TCP/5555 may be reachable on
the network even though LocalAdb connects only to localhost.

## CI

Keep changes in experimental PR #1 until reviewed. Existing workflows provide
test/lint, signed experimental and stable APKs, updater version guard and
Dependabot. CI cannot verify OEM-specific behavior or third-party Shizuku
client compatibility on physical devices.
