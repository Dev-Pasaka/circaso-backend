#!/usr/bin/env bash
# Lets only Cloudflare's servers reach ports 443 (and nothing else new): anyone who finds the server's address can not skip Cloudflare's protection
# and Access. SSH stays open to everyone (keys only). Run on the server as root; it reads Cloudflare's published ranges, so run it again now and then
# (it installs itself in /etc/cron.weekly). Idempotent.
set -euo pipefail

v4="$(curl -fsS https://www.cloudflare.com/ips-v4)"
v6="$(curl -fsS https://www.cloudflare.com/ips-v6)"
[ -n "$v4" ] && [ -n "$v6" ] || { echo "could not read Cloudflare's ranges; leaving the firewall alone"; exit 1; }

# Add the allowed ranges first, so there is never a moment when 443 is closed to Cloudflare.
for range in $v4 $v6; do
  ufw allow from "$range" to any port 443 proto tcp comment 'cloudflare' >/dev/null
done
# Then close the open rules.
ufw delete allow 443/tcp >/dev/null 2>&1 || true
ufw delete allow 80/tcp >/dev/null 2>&1 || true
ufw reload >/dev/null

if [ ! -e /etc/cron.weekly/circaso-cloudflare-firewall ]; then
  install -m 755 "$0" /etc/cron.weekly/circaso-cloudflare-firewall 2>/dev/null || true
fi
echo "443 is now open to Cloudflare only: $(ufw status | grep -c cloudflare) rules"
