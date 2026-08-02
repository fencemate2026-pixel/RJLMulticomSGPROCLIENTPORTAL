#!/bin/bash
# ============================================================
#  Mildura Working Man's Club - Boom Gate Install
#  RJL Commercial Fencing & Gates
#  Raspberry Pi 5 + Sebury/LA5353 Wiegand keypad + relay
# ============================================================
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVICE="boomgate"
USER="$(whoami)"
ENV_FILE="/etc/boomgate.env"

echo ""
echo "============================================"
echo "  Mildura Boom Gate - Setup"
echo "============================================"
echo ""
echo "Running from: $APP_DIR"
echo "User: $USER"
echo ""

# ── 1. System packages ────────────────────────────────────────
echo "[1/7] Installing system packages..."
sudo apt update -y
sudo apt install -y python3-venv python3-pip python3-lgpio libgpiod2 curl git sqlite3

# ── 2. Python virtual environment ────────────────────────────
echo "[2/7] Setting up Python virtual environment..."
python3 -m venv "$APP_DIR/venv"
# shellcheck disable=SC1091
source "$APP_DIR/venv/bin/activate"
pip install --upgrade pip --quiet
pip install -r "$APP_DIR/requirements.txt" --quiet
echo "      Flask + gpiozero + lgpio installed"

# ── 3. Secrets EnvironmentFile ────────────────────────────────
echo "[3/7] Ensuring $ENV_FILE ..."
if [[ ! -f "$ENV_FILE" ]]; then
  ADMIN_GEN="$(python3 - <<'PY'
import secrets
print(secrets.choice('123456789') + ''.join(secrets.choice('0123456789') for _ in range(5)))
PY
)"
  SECRET_GEN="$(python3 - <<'PY'
import secrets
print(secrets.token_hex(32))
PY
)"
  sudo tee "$ENV_FILE" >/dev/null <<EOF
ADMIN_PIN=${ADMIN_GEN}
FLASK_SECRET_KEY=${SECRET_GEN}
GPIOZERO_PIN_FACTORY=lgpio
PYTHONUNBUFFERED=1
WIEGAND_INVERT_BITS=0
EOF
  sudo chmod 600 "$ENV_FILE"
  sudo chown root:root "$ENV_FILE"
  echo "      Created $ENV_FILE (chmod 600)"
  echo "      *** SAVE THIS ADMIN PIN NOW: ${ADMIN_GEN} ***"
else
  echo "      Existing $ENV_FILE left unchanged"
fi

# ── 4. Initialise database ────────────────────────────────────
echo "[4/7] Initialising database..."
set -a
# shellcheck disable=SC1090
source <(sudo grep -E '^(ADMIN_PIN|FLASK_SECRET_KEY|GPIOZERO_PIN_FACTORY|PYTHONUNBUFFERED|WIEGAND_INVERT_BITS)=' "$ENV_FILE" | sed 's/^/export /')
set +a
"$APP_DIR/venv/bin/python" -c "
import sys
sys.path.insert(0, '$APP_DIR')
import database
database.init_db()
print('      boom_gate.db created/verified')
"

# ── 5. systemd service ────────────────────────────────────────
echo "[5/7] Registering systemd service..."
sudo tee /etc/systemd/system/${SERVICE}.service > /dev/null <<UNIT
[Unit]
Description=Mildura Working Man's Club - Boom Gate Controller
Documentation=https://www.rjlcommercial.com.au
After=network-online.target time-sync.target
Wants=network-online.target

[Service]
Type=simple
User=${USER}
WorkingDirectory=${APP_DIR}
EnvironmentFile=-${ENV_FILE}
Environment=GPIOZERO_PIN_FACTORY=lgpio
Environment=PYTHONUNBUFFERED=1
ExecStart=${APP_DIR}/venv/bin/python ${APP_DIR}/app.py
Restart=always
RestartSec=10
StartLimitIntervalSec=300
StartLimitBurst=5
# Single process — GPIO / Wiegand must not be duplicated
StandardOutput=journal
StandardError=journal
SyslogIdentifier=boomgate-mildura

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable ${SERVICE}
sudo systemctl restart ${SERVICE}
echo "      Service enabled + started"

# ── 6. Tailscale (optional) ───────────────────────────────────
echo "[6/7] (Optional) Tailscale remote access..."
if ! command -v tailscale >/dev/null 2>&1; then
  echo "   Tailscale not installed. Run manually later if needed:"
  echo "   curl -fsSL https://tailscale.com/install.sh | sh"
  echo "   sudo tailscale up --accept-routes"
else
  echo "   Tailscale already present."
fi

# ── 7. Staff import reminder ───────────────────────────────────
echo "[7/7] Staff import"
echo "   To import staff with random PINs:"
echo "     cd $APP_DIR && source venv/bin/activate"
echo "     python import_staff.py --dry"
echo "     python import_staff.py"
echo ""

LAN_IP=$(hostname -I | awk '{print $1}' || echo "unknown")
echo "============================================"
echo "  SETUP COMPLETE"
echo "============================================"
echo "  Admin dashboard: http://${LAN_IP}:5000"
echo "  Staff keypad:    http://${LAN_IP}:5000/keypad"
echo "  Secrets file:    ${ENV_FILE}"
echo "  REMOTE: use Tailscale; never expose :5000 to the public internet."
echo "============================================"
