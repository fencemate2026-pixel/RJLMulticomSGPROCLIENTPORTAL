#!/usr/bin/env bash
# Install the RJL Maintenance portal heartbeat on this Pi.
# Usage:  sudo bash install_portal.sh <env-file>
# e.g.    sudo bash install_portal.sh rjl-portal.mildura-boom.env
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ENVF="${1:-}"
if [[ -z "$ENVF" || ! -f "$HERE/$ENVF" ]]; then
  echo "Pick the env file for THIS Pi (copy from *.env.example first):"
  ls "$HERE"/rjl-portal.*.env* 2>/dev/null | xargs -n1 basename || true
  exit 1
fi
if [[ "$ENVF" == *.example ]]; then
  echo "Refusing to install an .example file. Copy it first and fill real secrets."
  exit 1
fi
install -d -m 755 /opt/rjl-portal
install -m 755 "$HERE/portal_sync.py" /opt/rjl-portal/portal_sync.py
install -m 600 "$HERE/$ENVF" /etc/rjl-portal.env
install -m 644 "$HERE/rjl-portal.service" /etc/systemd/system/rjl-portal.service
python3 -m py_compile /opt/rjl-portal/portal_sync.py
systemctl daemon-reload
systemctl enable --now rjl-portal
sleep 3
systemctl is-active --quiet rjl-portal && echo "OK: rjl-portal running" || { echo "FAILED - logs:"; journalctl -u rjl-portal -n 30 --no-pager; exit 1; }
echo "Verify in portal: gate last_seen should update within 5 min."
