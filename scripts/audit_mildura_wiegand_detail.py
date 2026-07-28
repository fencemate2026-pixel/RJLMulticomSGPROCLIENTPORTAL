from __future__ import annotations

import os
import sys
from pathlib import Path

import paramiko


def load_env() -> None:
    p = Path(__file__).resolve().parents[1] / ".local" / "mildura.env"
    if not p.is_file():
        return
    for line in p.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        os.environ.setdefault(k.strip(), v.strip())


def run(c: paramiko.SSHClient, cmd: str) -> str:
    _, o, e = c.exec_command(cmd, timeout=90)
    return (o.read() + e.read()).decode(errors="replace").rstrip()


def main() -> None:
    load_env()
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(
        os.environ["MILDURA_SSH_HOST"],
        username=os.environ["MILDURA_SSH_USER"],
        password=os.environ["MILDURA_SSH_PASSWORD"],
        timeout=25,
    )
    try:
        print("=== auto schedules ===")
        print(
            run(
                c,
                "sqlite3 -header -column /home/rjlcommercial/boomgate/boom_gate.db "
                '"SELECT id,name,days,open_time,close_time,enabled FROM auto_schedules;"',
            )
        )
        print()
        print("=== ALL wiegand since 2026-07-11 ===")
        print(
            run(
                c,
                "sqlite3 -header -column /home/rjlcommercial/boomgate/boom_gate.db "
                "\"SELECT timestamp, user_name, "
                "CASE success WHEN 1 THEN 'OK' ELSE 'DENY' END AS r, "
                "COALESCE(attempted_pin,'') AS pin "
                "FROM access_log WHERE method='wiegand' AND timestamp>='2026-07-11' "
                'ORDER BY timestamp;"',
            )
        )
        print()
        print("=== journal WIEGAND since Jul 22 (no reader-start noise) ===")
        print(
            run(
                c,
                "journalctl -u boomgate --since '2026-07-22' --no-pager | "
                "grep -i WIEGAND | grep -vi 'Reader started' | tail -120",
            )
        )
        print()
        print("=== on_pin / auto-open handling in live app.py ===")
        print(
            run(
                c,
                "grep -n -E 'def |auto_open|log_access|WIEGAND|PIN entered|GRANTED|set-time' "
                "/home/rjlcommercial/boomgate/app.py | head -80",
            )
        )
        print()
        # Extract the Wiegand handler function body roughly
        print(
            run(
                c,
                "sed -n '360,430p' /home/rjlcommercial/boomgate/app.py",
            )
        )
    finally:
        c.close()


if __name__ == "__main__":
    main()
