# LocalAdb

On-device Android ADB client using N2K02 theme and updater and the same verified-shell-UID-2000 ADB automation approach as VRec (without VRec's recording-specific daemon).

## Navigation

- Home: live ADB status, four compact status tiles, Start/Stop/Restart, automatic permissions summary and Apps card.
- Settings -> ADB connection: pairing, automatic code detection, watchdog, boot recovery and TCP/5555.
- Settings -> Apps: Shizuku clients and individually controlled Android development permissions.
- Settings -> Updates: shared n2k02updater.
- Quit: cancels discovery/recovery, closes transport and prevents automatic restart until explicitly started.

## First-time Android setup

1. Enable Developer options and Wireless debugging (Android 11+); allow nearby-network access if Android prompts.
2. Tap Find / Retry under ADB connection; open Android's Pair device with pairing code dialog.
3. Grant the optional LocalAdb Accessibility service if you want code auto-detection. It reads only Android Settings pairing dialogs. Otherwise enter the six-digit code manually; codes are not stored.
4. LocalAdb creates a persistent private ADB client identity and pairs to the on-device TLS listener.
5. Shell UID 2000 must be verified live. With TCP/5555 enabled, the verified shell switches adbd and LocalAdb reconnects to localhost port 5555 and verifies again.
6. Once connected, eligible LocalAdb permissions are configured automatically and confirmed by Android PackageManager. Restricted settings are granted only when requested by the corresponding option. Android may refuse individual grants and the UI reports that.

## Safety and recovery

- Connection watchdog, WorkManager backup and capped exponential recovery backoff.
- Start on boot is independent of the watchdog setting; recovery never silently restarts after explicit Stop or Quit.
- Wireless debugging restore after reboot is OFF by default, requires explicit opt-in, saved pairing and Android WRITE_SECURE_SETTINGS. Never override a manual off setting within the same boot or unknown boot count.
- The TCP/5555 daemon may be reachable by other devices on the network; LocalAdb itself connects only to 127.0.0.1. Use Disable TCP/5555 when not needed.

## Shizuku and app permissions

The Apps page draws on Shizuku-Next (https://github.com/rushiranpise/Shizuku-Next, Apache-2.0) to discover apps declaring Shizuku API support or Android developer permissions, filter and display them. Only individually chosen, declared Android development permissions can be changed using a live verified shell, with Android readback confirmation.

Shizuku API access is different from Android pm grant. Shizuku-Next checks the installed MANAGER UID for changes to its authorization flags. LocalAdb cannot directly grant Shizuku API access simply because it has ADB shell UID 2000. The UI opens the installed Shizuku manager instead and never claims that an unchecked Shizuku grant succeeded. LocalAdb does not impersonate or replace Shizuku.

The sideloaded app manager needs Android package visibility and declares QUERY_ALL_PACKAGES; this permission is restricted on Google Play.

## CI

One experimental PR at a time. Existing pipeline: unit tests, lint, signing, experimental prereleases, stable releases after main merge, Dependabot and updater-compatibility checks.

CI cannot confirm runtime pairing, Wi-Fi recovery or OEM-specific permission behavior. Test on an actual Android device before stable release.
