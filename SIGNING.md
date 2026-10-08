# Signing — the key lives in the repo's lock file; builds sign themselves

DollBloom ships its release key **inside the repository**, in one lock file:
`signing/release.lock`. It holds the keystore (base64) plus its passwords.
Every checkout — yours, a contributor's, GitHub Actions' — therefore signs
release builds automatically, with **zero secrets configured anywhere**:

- GitHub Actions: the workflow step `bash unlock-signing.sh` reads the lock
  file, drops `dollbloom-release.jks` + `keystore.properties` into the root,
  and Gradle signs. No repository secrets needed for signing at all.
- Locally: plain `./gradlew assembleProdRelease` reads `signing/release.lock`
  itself during configuration (see `app/build.gradle.kts`) — nothing to run.
  `bash unlock-signing.sh` does the same explicitly.
- Then CI re-checks the decrypted keystore's SHA-256 fingerprint against the
  pinned value below, so a tampered or swapped lock file fails the build
  instead of shipping a differently-signed APK. That pin is what keeps every
  future feature update on the same key as the first release — Android only
  installs an update over an install whose signing certificate matches.

## ⚠️ The trade-off, stated plainly

This repository is public, so **anyone can read the key** in
`signing/release.lock` and sign APKs that install as "DollBloom updates".
That is the accepted cost of the zero-secret open-source build pattern used
across your apps: convenience and reproducibility over key secrecy. The
real protections left are the pinned fingerprint in CI (catches accidental
swaps) and your GitHub account security (who can push a new lock file).

If you ever want the key out of public reach, the hardened path already
exists in this repo: delete `signing/release.lock`, keep the encrypted
`signing/*.enc` blobs, and set the single `SIGNING_UNLOCK` repository secret
(passphrase) — `unlock-signing.sh` and Gradle both fall back to it
automatically. Rotating that passphrase later is `bash seal-signing.sh
"new passphrase"` + commit; the key itself never changes.

**Never generate a second keystore.** A different key strands every
installed copy: people would have to uninstall (losing their library) to
move to a newer version.

## The key

| | |
|---|---|
| Lock file | `signing/release.lock` (committed; base64 keystore + passwords) |
| Encrypted backups | `signing/dollbloom-release.jks.enc`, `signing/keystore.properties.enc` (committed; AES-256-CBC/PBKDF2 600k; opened with the SIGNING_UNLOCK passphrase) |
| Alias | `dollbloom` |
| Algorithm | RSA 4096, self-signed, ~27 years validity |
| SHA-256 fingerprint | `B2:E4:39:33:74:68:B6:50:4D:36:36:C0:0E:08:C8:BB:ED:30:7B:A2:25:D3:A9:54:E4:1D:2B:74:4F:B7:37:3B` |
| Created | 2026-10-06 |

Verify any shipped APK: `keytool -printcert -jarfile app-release.apk` —
the SHA-256 must match the fingerprint above.

## Scripts

| Script | What it does |
|---|---|
| `unlock-signing.sh` | Lock file → plaintext key (default); or passphrase → decrypts `signing/*.enc`; or prompts |
| `seal-signing.sh` | Re-encrypts the plaintext key into `signing/*.enc` (passphrase rotation) |
| `print-github-secrets.sh` | Prints `SIGNING_UNLOCK=...` for the hardened flow, if you ever enable it |

## Releasing an update

1. Bump `version-code` (whole number, must rise) and pick a `version`
   (letters + digits fine, e.g. `2.1-beta1`).
2. Actions → **Release** → Run workflow → fill the two inputs.
3. The workflow unlocks the key from the lock file, signs, and if a release
   for that version already exists, edits it and replaces its APKs instead
   of duplicating.
