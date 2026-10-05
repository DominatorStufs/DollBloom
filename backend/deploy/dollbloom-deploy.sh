#!/usr/bin/env bash
# Pulls the requested supported branch and rebuilds. The GitHub deploy key is
# pinned to this script (forced command), so a leaked key can only redeploy main/master.
set -euo pipefail

REPO="https://github.com/DominatorStufs/DollBloom.git"
SRC=/opt/dollbloom-src
BRANCH="${SSH_ORIGINAL_COMMAND:-master}"
case "$BRANCH" in
  main|master) ;;
  *) echo "refusing unsupported deploy branch: $BRANCH" >&2; exit 1 ;;
esac

if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch "$BRANCH" "$REPO" "$SRC"
fi
git -C "$SRC" fetch --depth 1 origin "$BRANCH"
git -C "$SRC" reset --hard FETCH_HEAD
echo "deploying $BRANCH: $(git -C "$SRC" log -1 --format='%h %s')"
DOMAIN="$(cat /etc/dollbloom-domain)" bash "$SRC/backend/deploy/setup.sh"
