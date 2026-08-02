#!/usr/bin/env python3
"""Watch Mildura boomgate for the guaranteed first AP pulse after nightly close.

Theory under test: Roger AP dry-contact "cold contact" after hours idle since
~01:30/01:40 AEST ORO release — first PIN pulse sticks; later ones may not.

Read-only by default. Requires .local/mildura.env:
  MILDURA_SSH_HOST / MILDURA_SSH_USER / MILDURA_SSH_PASSWORD

Example (start ~01:20 AEST):
  python scripts/watch_mildura_first_pulse_after_close.py
  python scripts/watch_mildura_first_pulse_after_close.py --after-aest 01:40 --pre-roll-min 20
"""
from __future__ import annotations

import argparse
import os
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

try:
    import paramiko
except ImportError:
    print("pip install paramiko", file=sys.stderr)
    sys.exit(2)

AEST = ZoneInfo("Australia/Melbourne")


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


def run(c: paramiko.SSHClient, cmd: str, timeout: int = 60) -> str:
    _, o, e = c.exec_command(cmd, timeout=timeout)
    return (o.read() + e.read()).decode(errors="replace").rstrip()


def parse_hhmm(s: str) -> tuple[int, int]:
    hh, mm = s.strip().split(":")
    return int(hh), int(mm)


def next_close_aest(after_hhmm: str, now: datetime | None = None) -> datetime:
    """Next occurrence of after_hhmm in Australia/Melbourne."""
    now = now or datetime.now(AEST)
    h, m = parse_hhmm(after_hhmm)
    target = now.replace(hour=h, minute=m, second=0, microsecond=0)
    if target <= now:
        target = target + timedelta(days=1)
    return target


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--after-aest",
        default="01:40",
        help="Club ORO close time AEST (default 01:40; symptom described as ~01:30)",
    )
    ap.add_argument(
        "--pre-roll-min",
        type=int,
        default=20,
        help="Connect this many minutes before close (default 20)",
    )
    ap.add_argument(
        "--post-watch-min",
        type=int,
        default=180,
        help="Keep watching this many minutes after close for first pulse (default 180)",
    )
    ap.add_argument(
        "--now",
        action="store_true",
        help="Skip wait — start watching immediately (already past close / testing)",
    )
    args = ap.parse_args()

    load_env()
    host = os.environ.get("MILDURA_SSH_HOST")
    user = os.environ.get("MILDURA_SSH_USER")
    pw = os.environ.get("MILDURA_SSH_PASSWORD")
    if not (host and user and pw):
        print(
            "Missing .local/mildura.env with MILDURA_SSH_HOST/USER/PASSWORD.\n"
            "Do not deploy code — this watcher needs read-only SSH only.",
            file=sys.stderr,
        )
        return 2

    close_at = next_close_aest(args.after_aest)
    start_at = close_at - timedelta(minutes=args.pre_roll_min)
    end_at = close_at + timedelta(minutes=args.post_watch_min)
    now = datetime.now(AEST)

    print("=== Mildura first-pulse-after-close watcher ===")
    print(f"Theory: Roger AP cold-contact after idle since ~{args.after_aest} AEST")
    print(f"Now AEST:   {now.isoformat()}")
    print(f"Close AEST: {close_at.isoformat()}")
    print(f"Watch from: {start_at.isoformat()} → {end_at.isoformat()}")
    print("DO NOT DEPLOY. Read-only journal/status/GPIO sampling.")
    print()
    print("Also ask on site: after a stuck-open, if they manually close and")
    print("re-enter PIN — does the RETRY usually work, or stick again?")
    print()

    if not args.now and now < start_at:
        wait_s = (start_at - now).total_seconds()
        print(f"Sleeping {wait_s/60:.1f} min until pre-roll…", flush=True)
        time.sleep(wait_s)

    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(host, username=user, password=pw, timeout=25)

    try:
        print("=== baseline ===", flush=True)
        print(run(c, "date; timedatectl | head -8"), flush=True)
        print(run(c, "curl -sS --max-time 5 http://127.0.0.1:5000/api/status || true"), flush=True)
        print(
            run(
                c,
                "command -v pinctrl >/dev/null && pinctrl get 22; pinctrl get 23; "
                "command -v raspi-gpio >/dev/null && raspi-gpio get 22; raspi-gpio get 23; true",
            ),
            flush=True,
        )
        print(
            run(
                c,
                "sqlite3 -header -column /home/rjlcommercial/boomgate/boom_gate.db "
                "\"SELECT id,name,open_time,close_time,enabled FROM auto_schedules;\"",
            ),
            flush=True,
        )

        # Follow journal from "today" / last hour — print SIGNAL/PULSE/HOLD/WIEGAND lines
        print("=== journal follow (SIGNAL|PULSE|HOLD|WIEGAND|SCHEDULE|ORO|CUT) ===", flush=True)
        print("Waiting for: auto-close / hold OFF, then FIRST pulse after that.", flush=True)

        stdin, stdout, stderr = c.exec_command(
            "journalctl -u boomgate -f -n 30 --no-pager -o short-iso",
            timeout=None,
        )

        saw_close = args.now  # if --now, treat close as already happened
        first_pulse_after_close = None
        deadline = time.time() + max(60, (end_at - datetime.now(AEST)).total_seconds())

        while time.time() < deadline:
            if stdout.channel.recv_ready():
                chunk = stdout.channel.recv(4096).decode(errors="replace")
                for line in chunk.splitlines():
                    print(line, flush=True)
                    low = line.lower()
                    if any(
                        k in low
                        for k in (
                            "auto-close",
                            "auto-open finished",
                            "releasing hold",
                            "hold off",
                            "schedule ended",
                            "cut all",
                        )
                    ):
                        if not saw_close:
                            saw_close = True
                            print(
                                ">>> CLOSE/HOLD-RELEASE marker seen — "
                                "arming for FIRST pulse",
                                flush=True,
                            )
                    if saw_close and first_pulse_after_close is None:
                        if any(
                            k in low
                            for k in (
                                "short pulse",
                                "wiegand",
                                "triggered",
                                "[signal] short",
                                "pulse cut",
                            )
                        ):
                            first_pulse_after_close = line
                            print(
                                ">>> FIRST PULSE AFTER CLOSE — sample GPIO + status NOW",
                                flush=True,
                            )
                            print(
                                run(
                                    c,
                                    "date; curl -sS --max-time 5 "
                                    "http://127.0.0.1:5000/api/status; "
                                    "(pinctrl get 22; pinctrl get 23) 2>/dev/null; true",
                                ),
                                flush=True,
                            )
                            print(
                                ">>> If boom is stuck OPEN while GPIO22 is LOW: "
                                "meter Roger AP terminal now (decisive).",
                                flush=True,
                            )
                            print(
                                ">>> Note retry: manual close + second PIN — "
                                "works or sticks again?",
                                flush=True,
                            )
            else:
                time.sleep(0.25)
                # Soft timeout if channel died
                if stdout.channel.exit_status_ready():
                    break

        print("=== watcher done ===", flush=True)
        if first_pulse_after_close:
            print("First pulse line captured:", first_pulse_after_close)
        else:
            print("No post-close pulse seen in watch window — extend --post-watch-min or use --now.")
        return 0
    finally:
        c.close()


if __name__ == "__main__":
    raise SystemExit(main())
