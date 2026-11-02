# Required embedded ADB socket lifecycle pattern

Use this rule in **all current and future N2K02 Android apps** using
`com.github.MuntashirAkon:libadb-android` (verified upstream tag `3.1.1`).

## Why this is required
- Upstream `AbsAdbConnectionManager.close()` calls `PrivateKey.destroy()`
  and prints `DestroyFailedException` when that fails.
- Upstream `AdbConnection.close()`, even via `disconnect()`, destroys the
  connection's supplied key pair. Sharing a cached persistent key object could
  break subsequent reconnection when that provider supports destruction.
- Its read thread can print `SocketException: Socket closed` when intentional
  shutdown interrupts a blocking socket read; do not suppress all exceptions.

## Implementation checklist
1. Keep the saved authorized ADB RSA private key and certificate stable;
   do not delete/regenerate them during reconnect, Stop or Quit.
2. Each connection manager receives a separately PKCS#8-decoded key object
   derived from the saved identity, never the cached key object itself.
3. For socket teardown call `disconnect()`, not the manager's `close()`.
   Null out/retire the old manager reference and never reuse its session key.
4. On shell timeout, close the stream, disconnect the whole manager, mark
   disconnected, then require new `id -u == 2000` verification on reconnect.
5. Close the underlying ADB stream to wake readers, then close reader and
   output wrappers. Serialize shell commands through one mutex.
6. Reuse an existing healthy UID-2000 verified connection rather than
   reconnecting on every Activity start or discovery event.
7. Respect Quit, back off repeated automatic retries, cancel discovery and
   watchdogs first, and wait for teardown before destroying service ownership.
8. Avoid recording ADB secrets or pairing codes in logs. Separate expected
   shutdown exceptions from unexpected failures; do not hide all I/O warnings.
9. Add regression tests for key-copy isolation, manager retirement, timeouts,
   repeated reconnects, cancellation and shutdown.

Reference implementation: `N2K02/McpAdbAndroidLogger` PR #30,
`N2K02/VREC` PR #53, plus this repository's experimental PR.
