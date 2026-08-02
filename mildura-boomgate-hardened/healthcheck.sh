#!/bin/bash
# Boomgate healthcheck — service + API + unsafe hold detection
set -euo pipefail
LOG="${HOME}/boomgate/logs/healthcheck.log"
mkdir -p "$(dirname "$LOG")"
TS="$(date '+%Y-%m-%d %H:%M:%S')"
STATUS_URL="${STATUS_URL:-http://127.0.0.1:5000/api/status}"

fail() {
  echo "[$TS] FAIL: $*" | tee -a "$LOG"
  exit 1
}

systemctl is-active --quiet boomgate || fail "boomgate systemd not active"

HTTP_CODE="$(curl -sS -o /tmp/boomgate_status.json -w '%{http_code}' --max-time 5 "$STATUS_URL" || true)"
[[ "$HTTP_CODE" == "200" ]] || fail "api/status HTTP $HTTP_CODE"

# Parse with python for portability
python3 - <<'PY' || fail "status JSON parse/logic"
import json, sys
d = json.load(open("/tmp/boomgate_status.json"))
lockdown = bool(d.get("lockdown"))
hold = bool(d.get("hold"))
auto = bool(d.get("auto_open"))
# Unsafe: hold asserted while not auto-open and not intentional lockdown
# (manual hold is legitimate; we only flag hold+lockdown which should never happen)
if lockdown and hold:
    print("unsafe: hold ON during lockdown", file=sys.stderr)
    sys.exit(2)
print(f"ok lockdown={lockdown} hold={hold} auto_open={auto}")
PY

echo "[$TS] OK" >> "$LOG"
exit 0
