# Bundled Shizuku server: third-party notices

LocalAdb includes the **Shizuku-Next** Android server payload as a bundled
runtime component. It is **not installed as a separate Android application**.

- Source: https://github.com/rushiranpise/Shizuku-Next
- Version: v14.0.31-next (published 2026-10-28)
- Release asset: shizuku-v14.0.31-next.apk
- SHA-256: f75b8b74727d281f3d7fd242298f676bfb4ee81d2feb5298aa719b05284fa149
- Upstream license: Apache License, Version 2.0
- License text: https://www.apache.org/licenses/LICENSE-2.0
- Source and upstream attribution: retained in the linked upstream repository.
- Modifications to upstream executable payload: none (checksum verified).
- LocalAdb adds its own APK manager, provider, and shell-based startup.
- LocalAdb's `moe.shizuku.starter.ServiceStarter` entry point delegates to the
  checksum-verified upstream starter in an isolated classloader. The manager
  provider implements the upstream `sendUserService` / `attachUserService`
  handshake. The executable upstream payload remains unmodified.

The upstream APK is used as a classpath payload for a local shell process;
it is not installed as an app. If it cannot start or deliver a verified Binder,
LocalAdb never reports that external Shizuku authorization succeeded.

Shizuku and Shizuku-API also use Apache-2.0 and MIT:
https://github.com/RikkaApps/Shizuku
https://github.com/RikkaApps/Shizuku-API
