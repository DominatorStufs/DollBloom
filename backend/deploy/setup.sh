#!/usr/bin/env bash
# One-shot installer for the Listen Together server on an Oracle Cloud
# (or any Ubuntu 22.04/24.04) VM. Safe to re-run: it rebuilds and restarts.
#
#   sudo DOMAIN=jam.example.com bash deploy/setup.sh
#
# Run it from the backend/ directory of a checkout on the VM. The DNS A record
# for $DOMAIN must already point at this VM, or Caddy cannot get a certificate.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

DOMAIN="${DOMAIN:?set DOMAIN, e.g. DOMAIN=jam.example.com}"
GO_VERSION="${GO_VERSION:-1.27.0}"
BACKEND_DIR="$(cd "$(dirname "$0")/.." && pwd)"

case "$(uname -m)" in
  aarch64) GOARCH=arm64 ;;   # Ampere A1 shape
  x86_64)  GOARCH=amd64 ;;   # E2.1.Micro shape
  *) echo "unsupported arch $(uname -m)"; exit 1 ;;
esac

echo "== packages"
if ! dpkg -s curl git gnupg iptables-persistent >/dev/null 2>&1; then
  apt-get update -y
  apt-get install -y curl git debian-keyring debian-archive-keyring apt-transport-https gnupg iptables-persistent
fi

echo "== Go $GO_VERSION"
if ! /usr/local/go/bin/go version 2>/dev/null | grep -q "go$GO_VERSION"; then
  curl -fsSL "https://go.dev/dl/go$GO_VERSION.linux-$GOARCH.tar.gz" -o /tmp/go.tgz
  rm -rf /usr/local/go && tar -C /usr/local -xzf /tmp/go.tgz && rm /tmp/go.tgz
fi

echo "== build"
id dollbloom >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin dollbloom
install -d -o dollbloom -g dollbloom /opt/dollbloom-jam
(cd "$BACKEND_DIR" && /usr/local/go/bin/go build -o /opt/dollbloom-jam/server .)
chown dollbloom:dollbloom /opt/dollbloom-jam/server

echo "== service"
install -m 644 "$BACKEND_DIR/deploy/dollbloom-jam.service" /etc/systemd/system/dollbloom-jam.service
systemctl daemon-reload
systemctl enable dollbloom-jam
systemctl restart dollbloom-jam
install -m 644 "$BACKEND_DIR/deploy/dollbloom-keepalive.service" /etc/systemd/system/dollbloom-keepalive.service
systemctl daemon-reload
systemctl enable --now dollbloom-keepalive

echo "== caddy (HTTPS + WebSocket proxy)"
if ! command -v caddy >/dev/null; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -y && apt-get install -y caddy
fi
sed "s/{\$DOMAIN}/$DOMAIN/" "$BACKEND_DIR/deploy/Caddyfile" > /tmp/Caddyfile
if ! cmp -s /tmp/Caddyfile /etc/caddy/Caddyfile; then
  mv /tmp/Caddyfile /etc/caddy/Caddyfile
  systemctl reload caddy || systemctl restart caddy
fi

echo "== deploy hook"
echo "$DOMAIN" > /etc/dollbloom-domain
install -m 755 "$BACKEND_DIR/deploy/dollbloom-deploy.sh" /usr/local/sbin/dollbloom-deploy

echo "== firewall"
# Oracle's Ubuntu images ship an iptables REJECT rule that blocks everything
# but SSH, independent of the VCN security list. Open 80/443 above it.
for port in 80 443; do
  while iptables -D INPUT -p tcp --dport "$port" -m state --state NEW -j ACCEPT 2>/dev/null; do :; done
  reject="$(iptables -L INPUT --line-numbers -n | awk '$2 == "REJECT" { print $1; exit }')"
  iptables -I INPUT "${reject:-1}" -p tcp --dport "$port" -m state --state NEW -j ACCEPT
done
netfilter-persistent save

echo "== check"
sleep 2
curl -fsS http://127.0.0.1:8000/healthz && echo
echo "Done. Once DNS resolves, https://$DOMAIN/healthz should answer."
