# LocalAdb

Android starter built with Kotlin, Jetpack Compose, Material 3 Expressive, `n2k02theme` and `n2k02updater`.

## Current scope
- Home dashboard (placeholder: no ADB operations are enabled yet).
- Canonical single header and Home / Settings / Quit bottom navigation.
- Settings > Diagnostics, Logs, About & Updates.
- In-app opt-in, bounded, redacted log viewer; clipboard export.
- Shared stable / experimental updater.
- Unit tests, lint, CI builds, signed APK prereleases on experimental PRs, and stable releases on merge to `main`.

## Build
Use JDK 17 and Android SDK 37. Set `N2K02_PACKAGES_TOKEN` to a read-only GitHub Packages token with access to `n2k02theme` and `n2k02updater`.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The wrapper launcher downloads mRadio's **pinned** Gradle wrapper JAR on the first run and verifies its Git blob checksum. The JAR is intentionally not checked in.

## Release
Open or update the single experimental PR to `main`. A successful PR build produces a signed APK artifact and an experimental GitHub prerelease. Once reviewed, merge the PR to trigger the stable release on `main`. The workflow uses `SIGNING_PASSPHRASE` for the shared, encrypted signing material and optionally `ANDROID_DISTRIBUTION_TOKEN` to publish to the public `localadb/updates` channel. Plaintext signing keys never enter source control.

This is a **template only**; it does not claim ADB connectivity, pairing, command execution, or background tunnelling.
