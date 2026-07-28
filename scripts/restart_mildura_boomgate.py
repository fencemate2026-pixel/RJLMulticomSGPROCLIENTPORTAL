from __future__ import annotations

import os
import time
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


def main() -> None:
    load_env()
    host = os.environ["MILDURA_SSH_HOST"]
    user = os.environ["MILDURA_SSH_USER"]
    pw = os.environ["MILDURA_SSH_PASSWORD"]

    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(host, username=user, password=pw, timeout=25)

    def run(cmd: str, timeout: int = 90) -> str:
        _, o, e = c.exec_command(cmd, timeout=timeout)
        return (o.read() + e.read()).decode(errors="replace")

    # sudo -S
    print("=== sudo restart ===")
    stdin, stdout, stderr = c.exec_command("sudo -S systemctl restart boomgate", timeout=60)
    stdin.write(pw + "\n")
    stdin.flush()
    print(stdout.read().decode(errors="replace"))
    print(stderr.read().decode(errors="replace"))

    time.sleep(3)
    print("=== status ===")
    print(run("systemctl is-active boomgate"))
    print(run("curl -sS http://127.0.0.1:5000/api/status"))
    print()
    print("=== journal ===")
    print(run("journalctl -u boomgate -n 45 --no-pager"))
    print()
    print("=== patch markers ===")
    print(run("grep -n 'post-PIN stuck-open fix\\|post-pulse-stuck\\|re-assert constant hold' /home/rjlcommercial/boomgate/app.py | head -10"))
    c.close()


if __name__ == "__main__":
    main()
