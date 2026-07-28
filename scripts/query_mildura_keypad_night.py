"""Query Mildura boomgate access_log for last night's keypad activity."""
from __future__ import annotations

import sys
from datetime import datetime, timedelta

try:
    import paramiko
except ImportError:
    print("paramiko required: pip install paramiko")
    sys.exit(1)

import os
from pathlib import Path


def _load_local_env() -> None:
    """Load .local/mildura.env if present (gitignored)."""
    root = Path(__file__).resolve().parents[1]
    env_path = root / ".local" / "mildura.env"
    if not env_path.is_file():
        return
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        os.environ.setdefault(key.strip(), val.strip())


_load_local_env()

HOST = os.environ.get("MILDURA_SSH_HOST", "100.103.206.69")
USER = os.environ.get("MILDURA_SSH_USER", "rjlcommercial")
PASSWORD = os.environ.get("MILDURA_SSH_PASSWORD", "")
DB = os.environ.get(
    "MILDURA_DB_PATH", "/home/rjlcommercial/boomgate/boom_gate.db"
)


def run(client: paramiko.SSHClient, cmd: str, timeout: int = 60) -> str:
    _, stdout, stderr = client.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode(errors="replace")
    err = stderr.read().decode(errors="replace")
    return (out + (("\n" + err) if err.strip() else "")).strip()


def main() -> None:
    if not PASSWORD:
        print(
            "Missing MILDURA_SSH_PASSWORD.\n"
            "Set env var or create .local/mildura.env with:\n"
            "  MILDURA_SSH_PASSWORD=..."
        )
        sys.exit(2)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=PASSWORD, timeout=25)

    try:
        now_s = run(client, "date '+%Y-%m-%d %H:%M:%S %Z'")
        print(f"Pi time: {now_s}")
        print(f"DB: {DB}")
        print(run(client, f"ls -la {DB}"))
        print()

        # Last night window: yesterday 18:00 -> today 06:00 (local on Pi)
        win = run(
            client,
            r"""python3 - <<'PY'
from datetime import datetime, timedelta
now = datetime.now()
start = (now.replace(hour=0, minute=0, second=0, microsecond=0) - timedelta(days=1)).replace(hour=18)
end = now.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(hours=6)
print(start.strftime("%Y-%m-%d %H:%M:%S"))
print(end.strftime("%Y-%m-%d %H:%M:%S"))
PY""",
        )
        lines = [ln.strip() for ln in win.splitlines() if ln.strip()]
        start, end = lines[0], lines[1]
        print(f"Window: {start}  →  {end}")
        print()

        def sql(query: str) -> str:
            # Escape for remote double-quoted shell string
            q = query.replace('"', '\\"')
            return run(client, f'sqlite3 -header -column {DB} "{q}"')

        print("=== KEYPAD ONLY (wiegand physical + web keypad) ===")
        print(
            sql(
                f"""
SELECT timestamp, user_name, method,
CASE success WHEN 1 THEN 'GRANTED' ELSE 'DENIED' END AS result,
COALESCE(attempted_pin,'') AS pin_tried
FROM access_log
WHERE timestamp >= '{start}' AND timestamp < '{end}'
  AND method IN ('wiegand','web')
ORDER BY timestamp;
""".strip()
            )
            or "(no keypad rows in this window)"
        )
        print()

        print("=== SUMMARY BY METHOD ===")
        print(
            sql(
                f"""
SELECT method,
SUM(success=1) AS granted,
SUM(success=0) AS denied,
COUNT(*) AS total
FROM access_log
WHERE timestamp >= '{start}' AND timestamp < '{end}'
GROUP BY method
ORDER BY method;
""".strip()
            )
            or "(no events)"
        )
        print()

        print("=== ALL ACCESS EVENTS (incl. dashboard etc.) ===")
        print(
            sql(
                f"""
SELECT timestamp, user_name, method,
CASE success WHEN 1 THEN 'GRANTED' ELSE 'DENIED' END AS result,
COALESCE(attempted_pin,'') AS pin_tried
FROM access_log
WHERE timestamp >= '{start}' AND timestamp < '{end}'
ORDER BY timestamp
LIMIT 100;
""".strip()
            )
            or "(no events)"
        )
        print()

        print("=== WHO USED KEYPAD (granted) ===")
        print(
            sql(
                f"""
SELECT user_name, COUNT(*) AS opens
FROM access_log
WHERE timestamp >= '{start}' AND timestamp < '{end}'
  AND method IN ('wiegand','web')
  AND success=1
GROUP BY user_name
ORDER BY opens DESC;
""".strip()
            )
            or "(nobody granted via keypad)"
        )
    finally:
        client.close()


if __name__ == "__main__":
    main()
