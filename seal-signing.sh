#!/usr/bin/env bash
# (Re-)seal the local signing secrets into the encrypted copies that are
# safe to commit: signing/dollbloom-release.jks.enc and
# signing/keystore.properties.enc (AES-256-CBC, PBKDF2, 600k iterations).
#
# Usage:
#   bash seal-signing.sh                # prompts for a new passphrase
#   bash seal-signing.sh "passphrase"   # non-interactive (rotation in scripts)
#
# The passphrase is also written to signing/unlock.pass (gitignored) so
# local builds and unlock-signing.sh keep working without re-typing.
# After sealing, COMMIT the two .enc files — they are the repository's
# copy of the key; the passphrase is the only secret left to guard.
set -euo pipefail
cd "$(dirname "$0")"

PASS="${1:-}"
if [ -z "$PASS" ]; then
  read -r -s -p "New unlock passphrase: " PASS; echo
  read -r -s -p "Repeat: " PASS2; echo
  [ "$PASS" = "$PASS2" ] || { echo "error: passphrases differ"; exit 1; }
fi
[ -n "$PASS" ] || { echo "error: empty passphrase"; exit 1; }

[ -f dollbloom-release.jks ] && [ -f keystore.properties ] || {
  echo "error: plaintext dollbloom-release.jks / keystore.properties not found here"
  exit 1
}

mkdir -p signing
export UNLOCK_PASS="$PASS"
openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt \
  -in dollbloom-release.jks      -out signing/dollbloom-release.jks.enc -pass env:UNLOCK_PASS
openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt \
  -in keystore.properties        -out signing/keystore.properties.enc   -pass env:UNLOCK_PASS

printf '%s' "$PASS" > signing/unlock.pass
chmod 600 signing/unlock.pass
echo "Sealed into signing/*.enc — commit those two files."
echo "Guard the passphrase: it is now the only secret (GitHub secret SIGNING_UNLOCK)."
