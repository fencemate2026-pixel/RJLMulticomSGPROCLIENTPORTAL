"""Deep audit of Mildura access_log — find where keypad events actually live."""
from __future__ import annotations

import os
import sys
from pathlib import Path

try:
    import paramiko
except ImportError:
    print("pip install paramiko")
    sys.exit(1)


def load_env() -> None:
    env_path = Path(__file__).resolve().parents[1] / ".local" / "mildura.env"
    if not env_path.is_file():
        return
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        os.environ.setdefault(k.strip(), v.strip())


load_env()
HOST = os.environ.get("MILDURA_SSH_HOST", "100.103.206.69")
USER = os.environ.get("MILDURA_SSH_USER", "rjlcommercial")
PASSWORD = os.environ.get("MILDURA_SSH_PASSWORD", "")


def run(c: paramiko.SSHClient, cmd: str, timeout: int = 90) -> str:
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = o.read().decode(errors="replace")
    err = e.read().decode(errors="replace")
    return (out + (("\n[stderr]\n" + err) if err.strip() else "")).rstrip()


def main() -> None:
    if not PASSWORD:
        print("Missing password in .local/mildura.env")
        sys.exit(2)

    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PASSWORD, timeout=25)

    try:
        print("=== TIME / SERVICE ===")
        print(run(c, "date; hostname; systemctl is-active boomgate 2>/dev/null; systemctl show boomgate -p WorkingDirectory -p ExecStart -p Environment --no-pager 2>/dev/null | head -20"))
        print()

        print("=== FIND ALL boom_gate.db ===")
        print(run(c, "find /home/rjlcommercial /var /opt /srv -name 'boom_gate.db' 2>/dev/null; ls -la /home/rjlcommercial/boomgate/ 2>/dev/null | head -40"))
        print()

        print("=== PROCESS CWD / open DB files ===")
        print(run(c, r"""
pid=$(systemctl show boomgate -p MainPID --value 2>/dev/null)
echo "MainPID=$pid"
if [ -n "$pid" ] && [ "$pid" != "0" ]; then
  ls -l /proc/$pid/cwd 2>/dev/null
  tr '\0' '\n' < /proc/$pid/environ 2>/dev/null | grep -E 'ADMIN|DB|PWD|HOME' || true
  ls -l /proc/$pid/fd 2>/dev/null | grep -i '\.db' || true
  # also list open files via lsof if present
  command -v lsof >/dev/null && sudo lsof -p $pid 2>/dev/null | grep -i db || lsof -p $pid 2>/dev/null | grep -i db || true
fi
"""))
        print()

        dbs = run(c, "find /home/rjlcommercial -name 'boom_gate.db' 2>/dev/null").splitlines()
        dbs = [d.strip() for d in dbs if d.strip()]
        if not dbs:
            dbs = ["/home/rjlcommercial/boomgate/boom_gate.db"]

        for db in dbs:
            print(f"######## DB: {db} ########")
            print(run(c, f"ls -la '{db}'; stat '{db}' 2>/dev/null | head -8"))
            print()
            print("--- counts / methods / date range ---")
            print(
                run(
                    c,
                    f'''sqlite3 -header -column "{db}" "
SELECT COUNT(*) AS total_rows FROM access_log;
SELECT MIN(timestamp) AS oldest, MAX(timestamp) AS newest FROM access_log;
SELECT method, COUNT(*) AS n, SUM(success=1) AS granted, SUM(success=0) AS denied
FROM access_log GROUP BY method ORDER BY n DESC;
"''',
                )
            )
            print()
            print("--- last 40 access_log rows (any method) ---")
            print(
                run(
                    c,
                    f'''sqlite3 -header -column "{db}" "
SELECT id, timestamp, user_name, method,
CASE success WHEN 1 THEN 'OK' ELSE 'DENY' END AS result,
COALESCE(attempted_pin,'') AS pin
FROM access_log ORDER BY id DESC LIMIT 40;
"''',
                )
            )
            print()
            print("--- last 7 days by day + method ---")
            print(
                run(
                    c,
                    f'''sqlite3 -header -column "{db}" "
SELECT substr(timestamp,1,10) AS day, method, COUNT(*) AS n
FROM access_log
WHERE timestamp >= datetime('now','localtime','-7 days')
GROUP BY day, method
ORDER BY day DESC, method;
"''',
                )
            )
            print()
            print("--- yesterday full day (00:00-24:00) ---")
            print(
                run(
                    c,
                    f'''sqlite3 -header -column "{db}" "
SELECT timestamp, user_name, method,
CASE success WHEN 1 THEN 'OK' ELSE 'DENY' END AS result,
COALESCE(attempted_pin,'') AS pin
FROM access_log
WHERE substr(timestamp,1,10) = date('now','localtime','-1 day')
ORDER BY timestamp;
"''',
                )
            )
            print()
            print("--- today so far ---")
            print(
                run(
                    c,
                    f'''sqlite3 -header -column "{db}" "
SELECT timestamp, user_name, method,
CASE success WHEN 1 THEN 'OK' ELSE 'DENY' END AS result,
COALESCE(attempted_pin,'') AS pin
FROM access_log
WHERE substr(timestamp,1,10) = date('now','localtime')
ORDER BY timestamp;
"''',
                )
            )
            print()

        print("=== JOURNAL boomgate last 200 lines (keypad/PIN/wiegand) ===")
        print(
            run(
                c,
                "journalctl -u boomgate -n 200 --no-pager 2>/dev/null | grep -iE 'wiegand|pin|access|open|denied|GRANTED|keypad' | tail -80",
            )
        )
        print()

        print("=== changes_log last 20 ===")
        print(
            run(
                c,
                f'''sqlite3 -header -column /home/rjlcommercial/boomgate/boom_gate.db "
SELECT timestamp, action, detail, changed_by FROM changes_log ORDER BY id DESC LIMIT 20;
" 2>/dev/null''',
            )
        )
        print()

        print("=== users table sample (enabled count) ===")
        print(
            run(
                c,
                f'''sqlite3 -header -column /home/rjlcommercial/boomgate/boom_gate.db "
SELECT COUNT(*) AS users, SUM(enabled=1) AS enabled FROM users;
SELECT name, pin, enabled FROM users ORDER BY name LIMIT 30;
" 2>/dev/null''',
            )
        )
    finally:
        c.close()


if __name__ == "__main__":
    main()
