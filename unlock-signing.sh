#!/usr/bin/env bash
# Unlock DollBloom's signing secrets so Gradle can sign release builds.
#
# Sources, first one found wins:
#
#   1. signing/release.lock — the committed lock file holding the key
#      itself. This is the pattern this repo uses: CI configures ZERO
#      secrets, and any checkout (yours, a contributor's, Actions') gets a
#      signed build from `bash unlock-signing.sh` — or from plain Gradle,
#      which reads the same file (see app/build.gradle.kts).
#
#   2. a passphrase — argument, $SIGNING_UNLOCK, or signing/unlock.pass —
#      which decrypts the hardened copies signing/*.enc instead.
#
#   3. an interactive prompt for that passphrase.
#
# Gradle needs no call to this script at all once unlocked; the script
# exists for CI and for unlocking outside Gradle.
set -euo pipefail
cd "$(dirname "$0")"

lock_value() { grep "^$1=" signing/release.lock | head -1 | cut -d= -f2-; }

if [ -f signing/release.lock ]; then
  grep '^keyBase64=' signing/release.lock | head -1 | cut -d= -f2- | base64 -d > dollbloom-release.jks
  {
    echo "storeFile=dollbloom-release.jks"
    echo "storePassword=$(lock_value storePassword)"
    echo "keyAlias=$(lock_value keyAlias)"
    echo "keyPassword=$(lock_value keyPassword)"
  } > keystore.properties
  chmod 600 dollbloom-release.jks keystore.properties
  echo "Signing unlocked from signing/release.lock (no secret needed)."
else
  PASS="${1:-${SIGNING_UNLOCK:-}}"
  if [ -z "$PASS" ] && [ -f signing/unlock.pass ]; then
    PASS="$(cat signing/unlock.pass)"
  fi
  if [ -z "$PASS" ]; then
    read -r -s -p "Unlock passphrase for DollBloom signing: " PASS
    echo
  fi
  [ -n "$PASS" ] || { echo "error: no unlock passphrase given"; exit 1; }

  for pair in "signing/dollbloom-release.jks.enc:dollbloom-release.jks" \
              "signing/keystore.properties.enc:keystore.properties"; do
    enc="${pair%%:*}"; out="${pair##*:}"
    [ -f "$enc" ] || { echo "error: $enc not found in the repository"; exit 1; }
    UNLOCK_PASS="$PASS" openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 \
      -in "$enc" -out "$out" -pass env:UNLOCK_PASS \
      || { echo "error: wrong passphrase (or corrupt $enc)"; rm -f "$out"; exit 1; }
    chmod 600 "$out"
  done
  echo "Signing unlocked from signing/*.enc (passphrase)."
fi

if command -v keytool > /dev/null 2>&1; then
  FP=$(keytool -list -keystore dollbloom-release.jks \
    -storepass "$(grep '^storePassword=' keystore.properties | cut -d= -f2-)" \
    | grep "SHA-256" | sed 's/.*SHA-256): //')
  echo "Keystore SHA-256: $FP"
else
  echo "(keytool not on PATH — fingerprint print skipped; CI verifies it separately)"
fi
