# LocalAdb

LocalAdb is an on-device Android ADB client and native Shizuku-compatible
manager. It uses the N2K02 theme and updater, the canonical mRadio single
header and VRec-style Home status cards.

## Navigation

- **Home**: three compact read-only cards for ADB/pairing/transport/UID 2000,
  native Shizuku health, and automated Android permission verification.
  Actions and navigation shortcuts are deliberately not duplicated here.
- **Apps**: Shizuku clients shown first, with searchable User/System/Disabled/
  Hidden and Android grant filters, verified local Shizuku permissions,
  individually confirmed Android developer permissions, and expandable
  per-app Android permission details.
- **Adb**: aShell You-inspired internal command console with output/exit codes,
  searchable bounded history, bookmarks, copy/paste and interrupt.
- **Settings > ADB connection**: pairing, watchdog, boot/reboot restore,
  TCP/5555. This remains a submenu; the console is the main Adb tab.
- **Settings > Updates**: shared updater. No duplicate Apps or Adb commands
  entry in Settings; Logs and Diagnostics remain hidden.
- **Quit**: interrupts commands, stops native Shizuku, cancels recovery
  and closes ADB.

Main navigation order: **Home · Apps · Adb · Settings · Quit**.
The page header has one aligned icon/title and a compact, constrained
LocalAdb logo/name; it never renders the 108dp launcher icon full-size.

## Native Shizuku (no extra manager app installed)

After LocalAdb verifies a live shell UID 2000 it:

1. Starts the native Shizuku manager **automatically and independently of
   Android permission bootstrap**, even if an optional grant is unavailable.
2. Verifies LocalAdb owns the globally named Shizuku manager permission.
3. Exposes the checksum-verified bundled Shizuku-Next APK through a
   **read-only seekable file descriptor** from a UID-2000-restricted
   ContentProvider, avoiding a long-lived streaming pipe that could stall
   shell extraction. Only the same verified shell may read the payload.
4. Checks the exact SHA-256 on the device, extracts Shizuku-Next's own
   **native starter** from that verified APK for the device ABI, then runs
   the starter **as a foreground ADB shell command**. As in Shizuku-Next,
   the native starter forks and detaches the Java server itself. LocalAdb
   confirms the starter's exit status instead of adding another background
   shell job and swallowing startup errors.
5. Waits for a **verified live Binder under UID 2000**; a successful shell
   command by itself never counts as Shizuku readiness.
6. Uses server permission flags for Shizuku grant/revoke and explicit approval
   requests from compatible apps. Never claims a grant without readback.

In **Apps → Shizuku manager**, **Stop server** attempts to shut down only this
LocalAdb-owned server; it persists a manual-stop choice so the ADB watchdog
does not immediately restart it. **Restart server** clears that choice and
performs a controlled stop/start. No external Shizuku manager is needed.
The verified ADB watchdog checks Shizuku health too, with a minimum 90-second
retry interval to avoid reconnect loops.

When server startup fails, the Apps page exposes **Show server diagnostics**
with bounded excerpts from the native starter output, staged APK metadata and
Shizuku server phases. During the Binder wait, progress is updated every
five seconds and the operation ends with a visible failure after 60 seconds.
The same failure is
also reported under the Android logcat tag `LocalAdbShizuku`, which can be
inspected with the connected Android logger. A missing Binder can still reflect
Android/OEM restrictions or incompatibility; these changes improve startup
and diagnostics, but require on-device verification.

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
