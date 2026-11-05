# LocalAdb

LocalAdb is an on-device Android ADB client and native Shizuku-compatible
manager. It uses the N2K02 theme and updater, the canonical mRadio single
header and VRec-style Home status cards.

## Navigation

- **Home**: three compact, live-status cards for ADB, the embedded
  Shizuku manager, and LocalAdb's verified Android permissions. Only Home has
  the Shizuku **Stop / Restart** actions, automatic recovery details, and
  selectable startup diagnostics. The permissions card names the **actual
  Android read-back grants** (Nearby Wi-Fi, Local network, Secure settings
  when eligible); no placeholder/mock permissions are shown. Counts of
  Shizuku clients and eligible Android grants are populated from a live scan.
- **Home > Shizuku package installer**: choose one signed APK and confirm the system installer prompt. LocalAdb's embedded Shizuku UID 2000 LocalHelper launches the Android package installer with Google Play install-source attribution and checks the resulting PackageManager read-back. One alternate launch path is tried on OEMs that reject the first. This is **not** a silent install and does not magically make an incompatible APK work in Android Auto. Split APK / XAPK bundles are not supported.
- **Apps**: matching gradient-accented client cards, verified color-coded
  Shizuku authorization badges, compact app statistics, search and
  User/System/Disabled/Hidden and Android grant filters. Shizuku API access
  and Android development permissions retain separate confirmation and
  readback, with expandable per-app Android permission details. No redundant
  Shizuku server status/control card is rendered here.
- **Adb**: aShell You-inspired command console with a colored connection
  status card, a dedicated command-entry card, and green/red result cards
  showing actual exit codes; searchable bounded history, bookmarks,
  copy/paste and interrupt are preserved. **Inline, context-aware autocomplete**
  suggests shell commands, installed package names, and Android permission
  names as you type. Tap a suggestion or press Tab on a physical keyboard
  to insert it at the cursor without executing anything. Saved bookmarks and
  recent session commands are suggested before built-in command examples.
  Package names and declared permissions are loaded read-only from Android's
  PackageManager; no additional ADB shell commands or Shizuku calls occur
  while typing. Enter/Send still explicitly runs the chosen command.
  The familiar `adb shell <command>` prefix is accepted as a compatible
  alias and removed before execution inside LocalAdb's verified UID 2000
  shell. Host-only `adb devices/connect/pair/push/pull` actions are never
  presented as runnable commands. Typing `adb` suggests valid `adb shell`
  examples instead of suppressing autocomplete.
- **Commands submenu**: a searchable, categorized built-in command reference
  accessed through **Adb → Commands**, with descriptions and read-only,
  modifies-device or host-only badges. **Use in console** pre-fills the
  existing Adb command field and returns without executing. Host-only ADB
  commands are copyable reference examples but cannot be selected to run.
- **Command results**: newest output appears first; the latest result is
  expanded automatically and older results are collapsed. Tapping another
  result opens it while closing the previously expanded result, preserving
  copy actions, exit status, duration, errors and output search.
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
5. Selects the matching native ABI and extracts **librish.so** from the
   checksum-verified APK into `lib/<native ABI>/librish.so` beside `base.apk`.
   Shizuku-Next's Java server loads this JNI library during construction;
   without it, the native starter can return success but the server dies
   before the Binder handshake.
6. Waits for a **verified live Binder under UID 2000**; a successful shell
   command by itself never counts as Shizuku readiness.
7. Uses server permission flags for Shizuku grant/revoke and explicit approval
   requests from compatible apps. Never claims a grant without readback.

**Native dependency preflight:** the pinned Shizuku-Next APK must contain
both `libshizuku.so` and `librish.so` for every supported ABI it ships. CI
checks the JNI entries and ELF magic bytes before building. LocalAdb installs
the required arm64 library as
`/data/local/tmp/dev.n2k02.localadb-shizuku/lib/arm64/librish.so`
before launching the native starter, and reports an error immediately if
the copy fails or if the server process exits during starter execution.

In **Home → Shizuku manager**, **Stop** attempts to shut down only this
LocalAdb-owned server; it persists a manual-stop choice so the ADB watchdog
does not immediately restart it. **Restart** clears that choice and
performs a controlled stop/start. No external Shizuku manager is needed.
The verified ADB watchdog checks Shizuku health too, with a minimum 90-second
retry interval to avoid reconnect loops.

When server startup fails, Home exposes **Show server diagnostics**
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

## Android Auto package installer

The **Shizuku package installer** card on Home follows the approach used in
[UnknownAuto](https://github.com/GitMag/UnknownAuto), adapted to the **embedded**
Shizuku manager and LocalHelper service already provided by LocalAdb. It is an
installer tool, not an Android Auto modification or an APK patcher.

1. Start LocalAdb and wait for the embedded Shizuku Binder / UID 2000 to be ready.
2. Tap **Choose APK** and select an ordinary signed .apk (not XAPK / split APKs).
3. Tap **Install** and approve the Android **system** installer prompt. On older
   Android versions you may first be asked to allow LocalAdb to install unknown apps.
4. Read the attribution check. Successful read-back requires installing package
   **com.android.vending** and an acceptable system installer initiating package.
   Android Auto itself must still discover and support the app.

LocalAdb validates the installed version/signing key, stages only the selected
APK in private cache and grants read access to the system installer through a
single-path, read-only provider. The shell helper accepts only that provider URI
and an allowlisted system installer component. Android 14 and earlier use the
Shizuku service's result-target launch; newer Android uses a shell-launched
system installer. If an install succeeds without the expected mark, one alternate
launch path is tried and the working method remembered. Install failures do not
uninstall an existing app or delete its data. Quit clears the staged APK and
cancels LocalAdb's pending installer operations.

The metadata status is **not** a runtime Android Auto success guarantee. OEM
package installers differ, Play Protect may block an APK, and Android Auto
has other capability and compatibility checks. Validate on a real device and
car/head unit before relying on this for release distribution.

## ADB command privacy and safety

On Home, **Test LocalHelper** starts a dedicated Shizuku user service and checks
that it returns UID 2000 and echoes a fresh challenge over Binder IPC. A running
manager Binder alone cannot pass this test. The temporary service is removed
after each test, including timeout or cancellation. This checks LocalAdb's user
service path; each third-party client's own helper still needs its own check.

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
