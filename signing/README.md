# CI signing

The Android apps share an encrypted signing bundle (`signing/keys.tar.enc`) currently used by
MRadio and AAYouTube. CI retrieves that same public encrypted bundle from MRadio at a pinned
Git object hash, verifies the bytes, then decrypts with this repository's
`SIGNING_PASSPHRASE` Actions secret. The decrypted keystore is removed after signing.
No plaintext keys are committed or uploaded as artifacts.

The shared signing key intentionally matches the existing Android app signing workflow.
Do not change the signing bundle without a migration plan.
