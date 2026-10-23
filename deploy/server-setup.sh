#!/usr/bin/env bash
# Hardens a fresh Ubuntu 24.04 server and installs what a deploy needs. Idempotent: safe to run again.
# Run as root (or with sudo), over SSH as the deploy user:   ssh deploy@HOST 'sudo bash -s' < deploy/server-setup.sh
# Assumes a non-root user `deploy` with your public key already works, because root and password logins are switched off at the end.
set -euo pipefail

DEPLOY_USER="${DEPLOY_USER:-deploy}"
APP_DIR="${APP_DIR:-/opt/circaso}"

id "$DEPLOY_USER" >/dev/null 2>&1 || { echo "user $DEPLOY_USER does not exist"; exit 1; }
[ -s "/home/$DEPLOY_USER/.ssh/authorized_keys" ] || { echo "$DEPLOY_USER has no authorized key; refusing to lock the other doors"; exit 1; }

export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y ufw fail2ban unattended-upgrades ca-certificates curl gnupg

# ---- Docker (official repository) --------------------------------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
# Logs are rotated, so a chatty container cannot fill the disk. The docker group is root-equivalent: only the deploy user is in it.
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'JSON'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" },
  "live-restore": true,
  "no-new-privileges": true
}
JSON
systemctl enable --now docker
systemctl reload docker || systemctl restart docker
usermod -aG docker "$DEPLOY_USER"

# ---- firewall: SSH, HTTP, HTTPS and nothing else ----------------------------------------------------------------------------
# Docker publishes ports around ufw, so compose files must publish only 80 and 443 (the proxy); the app and Redis stay on the internal network.
ufw default deny incoming
ufw default allow outgoing
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

# ---- brute force and patching ------------------------------------------------------------------------------------------------
cat > /etc/fail2ban/jail.d/circaso.local <<'JAIL'
[sshd]
enabled = true
maxretry = 4
findtime = 10m
bantime = 1h
JAIL
systemctl enable --now fail2ban
systemctl restart fail2ban

cat > /etc/apt/apt.conf.d/20auto-upgrades <<'APT'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
APT
systemctl enable --now unattended-upgrades

# ---- where the app lives ----------------------------------------------------------------------------------------------------------
install -d -m 750 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$APP_DIR"

# ---- SSH: keys only, no root, last, and only after the config is proven valid --------------------------------------------------------
# Named 00- so it is read before any provider file that turns passwords back on.
cat > /etc/ssh/sshd_config.d/00-hardening.conf <<SSH
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
MaxAuthTries 3
LoginGraceTime 20
X11Forwarding no
AllowUsers $DEPLOY_USER
SSH
sshd -t
systemctl reload ssh

echo "server hardened: docker $(docker --version | cut -d' ' -f3 | tr -d ,), ufw on, fail2ban on, root and password SSH off"
