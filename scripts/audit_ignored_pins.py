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


def run(c, cmd: str) -> str:
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
        print("=== methods with IGNORE / all methods ===")
        print(
            run(
                c,
                "sqlite3 -header -column /home/rjlcommercial/boomgate/boom_gate.db "
                '"SELECT method, COUNT(*) n FROM access_log GROUP BY method ORDER BY n DESC;"',
            )
        )
        print()
        print("=== on_wiegand live code ===")
        print(run(c, "sed -n '454,495p' /home/rjlcommercial/boomgate/app.py"))
        print()
        print("=== journal last night IGNORE / PIN ===")
        print(
            run(
                c,
                "journalctl -u boomgate --since '2026-07-24 18:00' --until '2026-07-25 06:00' "
                "--no-pager | grep -iE 'IGNORE|WIEGAND|PIN entered|GRANTED|DENIED' | head -100",
            )
        )
        print()
        print("=== journal last 3 evenings IGNORE counts ===")
        print(
            run(
                c,
                "journalctl -u boomgate --since '2026-07-18' --no-pager | "
                "grep -c 'IGNORE during auto-open' ; "
                "journalctl -u boomgate --since '2026-07-18' --no-pager | "
                "grep 'IGNORE during auto-open' | tail -40",
            )
        )
    finally:
        c.close()


if __name__ == "__main__":
    main()
