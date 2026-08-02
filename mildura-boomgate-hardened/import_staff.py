"""
import_staff.py — Mildura Working Man's Club
Imports staff from staff_list.csv into boom_gate.db

Run:
    python import_staff.py --dry     # preview only
    python import_staff.py           # import for real

Categories map to schedules:
  Admin, Duty Manager       → always (24/7)
  Bistro / Bistro Bar / Bistro Manager / Gaming/Main Bar / Main Bar
                            → 09:00-23:30 every day
  Kitchen Staff             → 06:00-23:00 every day
  Cleaners                  → 05:00-10:00 every day
  Reception, Cellar         → 08:00-18:00 Mon-Fri only
"""

import csv
import json
import secrets
import sys

import database as db


# Schedule templates per category (from the original spec)
SCHEDULES = {
    "Admin":          ("always", None),
    "Duty Manager":   ("always", None),
    "Bistro":         ("time_range", {"days": [0,1,2,3,4,5,6], "start": "09:00", "end": "23:30"}),
    "Bistro Bar":     ("time_range", {"days": [0,1,2,3,4,5,6], "start": "09:00", "end": "23:30"}),
    "Bistro Manager": ("time_range", {"days": [0,1,2,3,4,5,6], "start": "09:00", "end": "23:30"}),
    "Gaming/Main Bar":("time_range", {"days": [0,1,2,3,4,5,6], "start": "09:00", "end": "23:30"}),
    "Main Bar":       ("time_range", {"days": [0,1,2,3,4,5,6], "start": "09:00", "end": "23:30"}),
    "Kitchen Staff":  ("time_range", {"days": [0,1,2,3,4,5,6], "start": "06:00", "end": "23:00"}),
    "Cleaners":       ("time_range", {"days": [0,1,2,3,4,5,6], "start": "05:00", "end": "10:00"}),
    "Reception":      ("time_range", {"days": [0,1,2,3,4],     "start": "08:00", "end": "18:00"}),
    "Cellar":         ("time_range", {"days": [0,1,2,3,4],     "start": "08:00", "end": "18:00"}),
}


def unique_pin(used: set) -> str:
    """6-digit cryptographically random PIN (never leading zero)."""
    while True:
        pin = secrets.choice("123456789") + "".join(
            secrets.choice("0123456789") for _ in range(5)
        )
        if pin not in used:
            return pin


def run(csv_file: str = "staff_list.csv", dry_run: bool = False):
    db.init_db()

    # Load already-used PINs
    existing = db.get_all_users()
    used_pins = {u['pin'] for u in existing if u['pin']}

    added, skipped, unknown = 0, 0, []

    with open(csv_file, newline='', encoding='utf-8') as f:
        for row in csv.DictReader(f):
            name     = f"{row['First Name'].strip()} {row['Last Name'].strip()}"
            category = row['Category'].strip()

            if category not in SCHEDULES:
                unknown.append(f"{name} ({category})")
                continue

            stype, sdata = SCHEDULES[category]
            sdata_str = json.dumps(sdata) if sdata else None
            pin = unique_pin(used_pins)
            used_pins.add(pin)

            if dry_run:
                print(f"[DRY] {name:30s} PIN={pin}  {stype:12s}  [{category}]")
                added += 1
                continue

            try:
                db.add_user(name, pin, stype, sdata_str)
                print(f"✓ {name:30s} PIN={pin}  {stype:12s}  [{category}]")
                added += 1
            except Exception:
                print(f"  SKIP (duplicate PIN or name conflict): {name}")
                skipped += 1

    print(f"\n{'DRY RUN — ' if dry_run else ''}Added: {added}  |  Skipped: {skipped}")
    if unknown:
        print("Unknown categories (not imported):")
        for u in unknown:
            print(f"  • {u}")

    if not dry_run:
        print("\nIMPORTANT: Save/print the PIN list above and give each staff member their 4-digit PIN.")
        print("They can use it on the physical Sebury keypad or the web keypad at /keypad")


if __name__ == "__main__":
    dry = '--dry' in sys.argv or '-d' in sys.argv
    run(dry_run=dry)
