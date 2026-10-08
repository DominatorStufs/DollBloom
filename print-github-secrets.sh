#!/usr/bin/env bash
# Prints the ONE GitHub Secret DollBloom's workflows need today:
#
#   SIGNING_UNLOCK — the passphrase that unlocks the encrypted signing
#                    key committed in signing/*.enc at build time.
#
# Value source: signing/unlock.pass (gitignored local copy) if present,
# else the first argument. Paste the printed line into
#   repo -> Settings -> Secrets and variables -> Actions -> New repository secret
set -euo pipefail
cd "$(dirname "$0")"

if [ -f signing/unlock.pass ]; then
  PASS="$(cat signing/unlock.pass)"
else
  PASS="${1:-}"
fi
[ -n "$PASS" ] || {
  echo "usage: bash print-github-secrets.sh <passphrase>   (or keep signing/unlock.pass around)"
  exit 1
}
echo "SIGNING_UNLOCK=$PASS"
