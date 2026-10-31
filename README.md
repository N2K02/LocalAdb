# LocalAdb

An Android Compose app with a built-in local ADB client, using the shared n2k02theme and n2k02updater libraries. Includes Home, Settings, Quit and Settings → ADB connection / Updates.

## First setup (Android 11+)

1. Turn on Developer options → Wireless debugging. Android must be on Wi-Fi to initiate wireless pairing.
2. In LocalAdb open Settings → ADB connection → **Find / Retry**.
3. Open Android's **Pair device with pairing code** dialog. Enter the six-digit code in LocalAdb, or explicitly enable the app's Accessibility service to read *only* Android Settings' pairing dialog. The pairing code is never persisted or logged.
4. ADB verifies the **shell UID 2000** before reporting Ready. When enabled, the verified shell switches adbd to localhost TCP/5555 and reconnects; otherwise it uses the local TLS listener.
5. The connection watchdog attempts authorized recovery; Start on boot schedules an optional WorkManager recovery. Reboot restore is *disabled by default* because it requires Android's WRITE_SECURE_SETTINGS development permission and explicit user consent. The app never overrides a manual Wireless debugging off action within the same boot.

**Security:** The app only connects to 127.0.0.1, and NSD-discovered services must belong to the current device. It does not expose a remote ADB server of its own. **TCP/5555 can be network-accessible on the device**; use Disable TCP/5555 when you don't need it.

**Limits:** Android's system setting, user authorization and Wi-Fi state cannot be bypassed. Background execution and reboot restore depend on Android's own restrictions and permissions. No VRec recording daemon or call-recording services are included.

## Build and releases

The experimental PR uses the shared Android build, lint/test, Dependabot and experimental prerelease workflow. Merge into main for the stable release. This app retains the existing signing secrets and distribution configuration.
