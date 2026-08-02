"""Full remote audit of the Mildura Raspberry Pi boomgate stack.

Requires .local/mildura.env:
  MILDURA_SSH_HOST=...
  MILDURA_SSH_USER=...
  MILDURA_SSH_PASSWORD=...

Produces a structured report on stdout (and optional --out path).
Read-only by default. Pass --apply-safe-fixes to restart only if healthcheck fails
and the hardened markers are already present (never pushes code by itself).
"""
from __future__ import annotations

import argparse
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

try:
    import paramiko
except ImportError:
    print("pip install paramiko", file=sys.stderr)
    sys.exit(2)


CHECKS = [
    ("host", "hostname; date; uptime; uname -a"),
    ("disk", "df -h / | tail -1; free -h | head -2"),
    ("service", "systemctl is-active boomgate; systemctl show boomgate -p MainPID -p ActiveEnterTimestamp -p EnvironmentFiles --no-pager"),
    ("portal", "systemctl is-active rjl-portal 2>/dev/null || echo inactive; systemctl is-active airtable-sync 2>/dev/null || true"),
    ("status_api", "curl -sS --max-time 5 http://127.0.0.1:5000/api/status || echo STATUS_FAIL"),
    ("healthcheck", "test -x /home/rjlcommercial/boomgate/healthcheck.sh && /home/rjlcommercial/boomgate/healthcheck.sh || echo NO_HEALTHCHECK"),
    ("gpio_markers", "grep -nE 'post-PIN stuck-open fix|hold_should_be_on|cut_all_signals|IGNORE pulse during lockdown' /home/rjlcommercial/boomgate/app.py | head -30"),
    ("secrets_file", "sudo -n test -f /etc/boomgate.env && echo boomgate.env:present || echo boomgate.env:MISSING; sudo -n test -f /etc/rjl-portal.env && echo portal.env:present || echo portal.env:MISSING"),
    ("schedules", "sqlite3 -header -column /home/rjlcommercial/boomgate/boom_gate.db \"SELECT id,name,days,open_time,close_time,enabled FROM auto_schedules;\""),
    ("lockdown", "cd /home/rjlcommercial/boomgate && ./venv/bin/python - <<'PY'\nimport database as db\nprint('lockdown', db.is_lockdown())\nprint('auto_open', db.is_auto_open())\nPY"),
    ("recent_access", "sqlite3 -header -column /home/rjlcommercial/boomgate/boom_gate.db \"SELECT id,timestamp,user_name,method,CASE success WHEN 1 THEN 'OK' ELSE 'DENY' END r FROM access_log ORDER BY id DESC LIMIT 20;\""),
    ("journal", "journalctl -u boomgate -n 80 --no-pager"),
    ("tailscale", "tailscale ip -4 2>/dev/null || echo no-tailscale"),
    ("throttled", "vcgencmd get_throttled 2>/dev/null || echo n/a"),
]


def load_env() -> None:
    p = Path(__file__).resolve().parents[1] / ".local" / "mildura.env"
    if not p.exists():
        return
    for line in p.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        os.environ.setdefault(k.strip(), v.strip())


def run(c: paramiko.SSHClient, cmd: str, timeout: int = 90) -> str:
    _, o, e = c.exec_command(cmd, timeout=timeout)
    return (o.read() + e.read()).decode(errors="replace").rstrip()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", help="Write report markdown path")
    args = ap.parse_args()
    load_env()
    host = os.environ.get("MILDURA_SSH_HOST")
    user = os.environ.get("MILDURA_SSH_USER")
    pw = os.environ.get("MILDURA_SSH_PASSWORD")
    if not (host and user and pw):
        print(
            "Missing SSH credentials. Create .local/mildura.env with\n"
            "  MILDURA_SSH_HOST / MILDURA_SSH_USER / MILDURA_SSH_PASSWORD",
            file=sys.stderr,
        )
        return 2

    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(host, username=user, password=pw, timeout=25)
    lines = [
        f"# Mildura Pi live audit — {datetime.now(timezone.utc).isoformat()}",
        f"Host: `{host}` user: `{user}`",
        "",
    ]
    try:
        for name, cmd in CHECKS:
            print(f"=== {name} ===", flush=True)
            out = run(c, cmd)
            print(out, flush=True)
            print(flush=True)
            lines.append(f"## {name}")
            lines.append("```")
            lines.append(out)
            lines.append("```")
            lines.append("")
    finally:
        c.close()

    report = "\n".join(lines) + "\n"
    if args.out:
        Path(args.out).write_text(report, encoding="utf-8")
        print(f"Wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
