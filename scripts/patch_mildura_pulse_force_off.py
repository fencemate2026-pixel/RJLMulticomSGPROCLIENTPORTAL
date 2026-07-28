"""
Patch Mildura boomgate app.py:

After every short keypad/remote pulse, ALWAYS force the open line OFF,
then re-assert constant hold ONLY if auto-open schedule (or manual hold)
is still active.

This stops a stuck-high GPIO after a PIN when the boom should free-close.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

import paramiko

REMOTE_APP = "/home/rjlcommercial/boomgate/app.py"


def load_env() -> None:
    p = Path(__file__).resolve().parents[1] / ".local" / "mildura.env"
    for line in p.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        os.environ.setdefault(k.strip(), v.strip())


def run(c: paramiko.SSHClient, cmd: str, timeout: int = 120) -> tuple[int, str]:
    _, o, e = c.exec_command(cmd, timeout=timeout)
    code = o.channel.recv_exit_status()
    out = (o.read() + e.read()).decode(errors="replace")
    return code, out


# Exact current pulse_gate from live Pi (Jul 2026) — replace with hardened version
OLD_PULSE_GATE = '''def pulse_gate(reason: str = "manual") -> bool:
    """Keypad short pulse on open line (ONLY when boom is down).

    During auto-open: constant hold only — never pulse, never accept commands.
    """
    long_hold, hold_reason = hold_should_be_on()
    if long_hold:
        print(
            f"[SIGNAL] IGNORE pulse during auto-open ({hold_reason}): {reason!r}",
            flush=True,
        )
        return False
    if not _relay_lock.acquire(blocking=False):
        print("[SIGNAL] Already driving open line — skip", flush=True)
        return False
    try:
        print(f"[SIGNAL] SHORT PULSE {PULSE_SECONDS}s — {reason}", flush=True)
        if GPIO_AVAILABLE and relay is not None:
            relay.on()
            time.sleep(PULSE_SECONDS)
        else:
            time.sleep(0.5)
        return True
    except Exception as err:
        print(f"[SIGNAL] pulse error: {err}", flush=True)
        return False
    finally:
        # Boom-down path only (auto-open never enters pulse_gate body)
        _open_off()
        with _hold_lock:
            _mirror_off()
        print(f"[SIGNAL] SHORT PULSE CUT — boom free-closes ({reason})", flush=True)
        _relay_lock.release()
'''

NEW_PULSE_GATE = '''def pulse_gate(reason: str = "manual") -> bool:
    """Keypad short pulse on open line (ONLY when boom is down).

    During auto-open: constant hold only — never pulse, never accept commands.

    SAFETY (post-PIN stuck-open fix):
      Always force the open line OFF after the pulse window, even if an
      exception occurs. Then re-assert constant hold ONLY if schedule /
      manual hold still wants it. Keypad must never leave GPIO22 stuck ON
      when auto-open is idle.
    """
    long_hold, hold_reason = hold_should_be_on()
    if long_hold:
        print(
            f"[SIGNAL] IGNORE pulse during auto-open ({hold_reason}): {reason!r}",
            flush=True,
        )
        return False
    if not _relay_lock.acquire(blocking=False):
        print("[SIGNAL] Already driving open line — skip", flush=True)
        return False
    ok = False
    try:
        print(f"[SIGNAL] SHORT PULSE {PULSE_SECONDS}s — {reason}", flush=True)
        if GPIO_AVAILABLE and relay is not None:
            relay.on()
            time.sleep(PULSE_SECONDS)
        else:
            time.sleep(0.5)
        ok = True
        return True
    except Exception as err:
        print(f"[SIGNAL] pulse error: {err}", flush=True)
        return False
    finally:
        # 1) Always force open line + mirror OFF first (never leave stuck high)
        try:
            _open_off()
        except Exception as err:
            print(f"[SIGNAL] force open OFF failed: {err}", flush=True)
        try:
            with _hold_lock:
                _mirror_off()
        except Exception as err:
            print(f"[SIGNAL] force mirror OFF failed: {err}", flush=True)

        print(f"[SIGNAL] SHORT PULSE CUT — boom free-closes ({reason})", flush=True)

        # 2) Re-assert constant hold only if schedule/manual still requires it
        #    (keypad path should be idle here; this covers races with scheduler)
        try:
            want, why = hold_should_be_on()
            if want:
                print(
                    f"[SIGNAL] re-assert constant hold after pulse ({why})",
                    flush=True,
                )
                assert_long_hold(f"post-pulse:{why}")
            else:
                # Belt-and-braces: cut again if hardware still reads ON
                if pulse_open_state():
                    print(
                        f"[SIGNAL] stuck-on after pulse — forcing CUT ({reason})",
                        flush=True,
                    )
                    cut_all_signals(f"post-pulse-stuck:{reason}", quiet=False)
        except Exception as err:
            print(f"[SIGNAL] post-pulse reconcile error: {err}", flush=True)
            try:
                cut_all_signals(f"post-pulse-error:{reason}", quiet=False)
            except Exception:
                pass

        try:
            _relay_lock.release()
        except Exception:
            pass
'''


def main() -> int:
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
        sftp = c.open_sftp()
        with sftp.file(REMOTE_APP, "r") as f:
            original = f.read().decode("utf-8")

        if "post-PIN stuck-open fix" in original or "post-pulse-stuck" in original:
            print("Patch already present on remote app.py — skipping replace.")
            code, out = run(c, "curl -sS http://127.0.0.1:5000/api/status")
            print(out)
            return 0

        if OLD_PULSE_GATE not in original:
            print("ERROR: expected pulse_gate() block not found — aborting (no write).")
            # Show a hint around pulse_gate for debugging
            idx = original.find("def pulse_gate")
            print("pulse_gate index:", idx)
            if idx >= 0:
                print(original[idx : idx + 800])
            return 1

        patched = original.replace(OLD_PULSE_GATE, NEW_PULSE_GATE, 1)
        if patched == original:
            print("ERROR: replace produced no change")
            return 1

        # Backup then write
        bak = f"{REMOTE_APP}.bak_pulse_force_off"
        code, out = run(c, f"cp -a {REMOTE_APP} {bak} && ls -la {bak}")
        print(out)
        if code != 0:
            print("Backup failed")
            return 1

        # Write via temp + mv
        tmp = "/tmp/app_pulse_force_off.py"
        with sftp.file(tmp, "w") as f:
            f.write(patched.encode("utf-8"))
        code, out = run(
            c,
            f"python3 -m py_compile {tmp} && "
            f"cp {tmp} {REMOTE_APP} && "
            f"grep -n 'post-PIN stuck-open fix\\|post-pulse-stuck\\|SHORT PULSE CUT' {REMOTE_APP} | head -20",
        )
        print(out)
        if code != 0:
            print("Compile/install failed — restoring backup")
            run(c, f"cp -a {bak} {REMOTE_APP}")
            return 1

        print("=== restart boomgate ===")
        code, out = run(
            c,
            "sudo systemctl restart boomgate && sleep 2 && "
            "systemctl is-active boomgate && "
            "curl -sS http://127.0.0.1:5000/api/status && echo && "
            "journalctl -u boomgate -n 40 --no-pager",
        )
        print(out)
        if code != 0:
            print("Restart reported non-zero; check output above")
            return 1

        print("OK — pulse force-off patch deployed.")
        return 0
    finally:
        c.close()


if __name__ == "__main__":
    sys.exit(main())
