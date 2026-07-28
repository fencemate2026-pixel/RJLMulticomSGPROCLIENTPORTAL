"""Diagnose Mildura gate stuck-open after keypad pulse."""
from __future__ import annotations

import os
from pathlib import Path

import paramiko


def load_env() -> None:
    p = Path(__file__).resolve().parents[1] / ".local" / "mildura.env"
    for line in p.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        os.environ.setdefault(k.strip(), v.strip())


def run(c: paramiko.SSHClient, cmd: str, timeout: int = 60) -> str:
    _, o, e = c.exec_command(cmd, timeout=timeout)
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
        print("=== TIME / STATUS API ===")
        print(run(c, "date; curl -sS http://127.0.0.1:5000/api/status"))
        print()

        print("=== SERVICE / PROCESS ===")
        print(
            run(
                c,
                "systemctl is-active boomgate; systemctl show boomgate -p ActiveEnterTimestamp -p MainPID --no-pager; "
                "ps -o pid,lstart,cmd -p $(systemctl show boomgate -p MainPID --value)",
            )
        )
        print()

        print("=== AUTO SCHEDULES NOW ===")
        print(
            run(
                c,
                "sqlite3 -header -column /home/rjlcommercial/boomgate/boom_gate.db "
                "\"SELECT id,name,days,open_time,close_time,enabled FROM auto_schedules;\"",
            )
        )
        print()

        print("=== is_auto_open python check ===")
        print(
            run(
                c,
                "cd /home/rjlcommercial/boomgate && ./venv/bin/python - <<'PY'\n"
                "import database as db\n"
                "print('is_auto_open', db.is_auto_open())\n"
                "print('lockdown', getattr(db, 'is_lockdown', lambda: False)())\n"
                "from datetime import datetime\n"
                "print('now', datetime.now())\n"
                "PY",
            )
        )
        print()

        print("=== RECENT SIGNAL / PULSE / ORO / WIEGAND journal (today) ===")
        print(
            run(
                c,
                "journalctl -u boomgate --since today --no-pager | "
                "grep -iE 'SIGNAL|PULSE|ORO|WIEGAND|SCHEDULE|HOLD|CONSTANT|stuck|CUT|open' | tail -120",
            )
        )
        print()

        print("=== RECENT journal last 80 raw ===")
        print(run(c, "journalctl -u boomgate -n 80 --no-pager"))
        print()

        print("=== pulse_gate / open_for_staff / apply_hold / may_send (code) ===")
        print(
            run(
                c,
                "grep -n -E 'def pulse_gate|def open_for_staff|def apply_hold|def may_send|"
                "def assert_long|def cut_all|SHORT PULSE|CONSTANT|PULSE_SECONDS|GPIO' "
                "/home/rjlcommercial/boomgate/app.py | head -60",
            )
        )
        print()
        print(run(c, "sed -n '199,450p' /home/rjlcommercial/boomgate/app.py"))
        print()

        print("=== last 15 access_log ===")
        print(
            run(
                c,
                "sqlite3 -header -column /home/rjlcommercial/boomgate/boom_gate.db "
                "\"SELECT id,timestamp,user_name,method,"
                "CASE success WHEN 1 THEN 'OK' ELSE 'DENY' END r "
                "FROM access_log ORDER BY id DESC LIMIT 15;\"",
            )
        )
        print()

        print("=== last 15 changes_log ===")
        print(
            run(
                c,
                "sqlite3 -header -column /home/rjlcommercial/boomgate/boom_gate.db "
                "\"SELECT timestamp,action,detail FROM changes_log ORDER BY id DESC LIMIT 15;\"",
            )
        )
    finally:
        c.close()


if __name__ == "__main__":
    main()
