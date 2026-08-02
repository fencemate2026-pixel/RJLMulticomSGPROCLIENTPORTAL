#!/usr/bin/env python3
"""
Set Mildura club constant-hold window to 09:55 → 01:40 (next day).

Desired behaviour:
  - Inside window: ORO constant hold — boom stays up (no keypad pulse needed)
  - Outside window: PIN / keypad short AP pulse, then free-close

Updates auto_schedules open_time/close_time (and name if it still says 01:30).
Does not disable schedules. Backs up boom_gate.db first.

Usage on Pi:
  python3 set_club_hold_hours.py --dry-run
  python3 set_club_hold_hours.py
  sudo systemctl restart boomgate
"""
from __future__ import annotations

import argparse
import shutil
import sqlite3
import sys
from datetime import datetime
from pathlib import Path

DEFAULT_DB = Path("/home/rjlcommercial/boomgate/boom_gate.db")
OPEN_TIME = "09:55"
CLOSE_TIME = "01:40"


def connect(db: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(str(db))
    conn.row_factory = sqlite3.Row
    return conn


def list_schedules(conn: sqlite3.Connection) -> list[sqlite3.Row]:
    return list(
        conn.execute(
            "SELECT id, name, days, open_time, close_time, enabled "
            "FROM auto_schedules ORDER BY id"
        )
    )


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("db", nargs="?", type=Path, default=DEFAULT_DB)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument(
        "--id",
        type=int,
        action="append",
        default=[],
        help="only update this schedule id (repeatable). Default: all rows",
    )
    ap.add_argument("--open", default=OPEN_TIME, help=f"default {OPEN_TIME}")
    ap.add_argument("--close", default=CLOSE_TIME, help=f"default {CLOSE_TIME}")
    args = ap.parse_args()
    db: Path = args.db

    if not db.is_file():
        print(f"FAIL: missing database {db}", file=sys.stderr)
        return 1

    conn = connect(db)
    try:
        rows = list_schedules(conn)
    except sqlite3.Error as err:
        print(f"FAIL: cannot read auto_schedules: {err}", file=sys.stderr)
        return 2

    if not rows:
        print("FAIL: no auto_schedules rows — add one in the dashboard first", file=sys.stderr)
        return 3

    print("Current auto_schedules:")
    for r in rows:
        flag = "ON " if r["enabled"] else "off"
        print(
            f"  id={r['id']} [{flag}] {r['name']!r} "
            f"{r['open_time']}-{r['close_time']} days={r['days']}"
        )

    targets = rows
    if args.id:
        targets = [r for r in rows if r["id"] in args.id]
        missing = set(args.id) - {r["id"] for r in rows}
        if missing:
            print(f"FAIL: unknown id(s): {sorted(missing)}", file=sys.stderr)
            return 4
        if not targets:
            print("FAIL: no matching rows", file=sys.stderr)
            return 4

    print(f"\nWill set open={args.open} close={args.close} on:")
    for r in targets:
        new_name = r["name"]
        if "01:30" in new_name:
            new_name = new_name.replace("01:30", args.close)
        elif "1:30" in new_name and args.close not in new_name:
            new_name = new_name.replace("1:30", args.close.lstrip("0") if False else args.close)
        print(f"  id={r['id']} {r['name']!r} → {args.open}-{args.close} name={new_name!r}")

    if args.dry_run:
        print("\nDRY-RUN: no changes written")
        return 0

    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    backup = db.with_name(f"{db.name}.bak_hold_hours_{stamp}")
    shutil.copy2(db, backup)
    print(f"\nbackup: {backup}")

    for r in targets:
        new_name = r["name"]
        if "01:30" in new_name:
            new_name = new_name.replace("01:30", args.close)
        conn.execute(
            "UPDATE auto_schedules "
            "SET open_time = ?, close_time = ?, name = ?, enabled = 1 "
            "WHERE id = ?",
            (args.open, args.close, new_name, int(r["id"])),
        )
    conn.commit()

    print("Updated auto_schedules:")
    for r in list_schedules(conn):
        flag = "ON " if r["enabled"] else "off"
        print(
            f"  id={r['id']} [{flag}] {r['name']!r} "
            f"{r['open_time']}-{r['close_time']}"
        )

    print("\nPASS: club hold window set")
    print("Next: sudo systemctl restart boomgate")
    print(
        f"Expect during {args.open}–{args.close}: ORO HIGH (hold). "
        "Outside: ORO LOW, keypad AP pulse only."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
