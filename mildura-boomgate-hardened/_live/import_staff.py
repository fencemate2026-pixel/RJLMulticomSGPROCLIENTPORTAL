"""
import_staff.py — Mildura Working Man's Club
Imports staff from staff_list.csv into boom_gate.db
Run once: python import_staff.py

Categories found in the CSV:
  Admin, Duty Manager       → always (24/7 access)
  Bistro, Bistro Bar,
  Bistro Manager,
  Gaming/Main Bar, Main Bar → Mon-Sun 09:00-23:30
  Kitchen Staff             → Mon-Sun 06:00-23:00
  Cleaners                  → Mon-Sun 05:00-10:00
  Reception, Cellar         → Mon-Fri 08:00-18:00
"""

import csv
import random
import sqlite3
import json
import sys
from database import DB_NAME, init_db

# Schedule templates per category
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
    while True:
        pin = str(random.randint(1000, 9999))
        if not pin.startswith('0') and pin not in used:
            return pin


def run(csv_file: str = "staff_list.csv", dry_run: bool = False):
    init_db()
    conn = sqlite3.connect(DB_NAME)
    c = conn.cursor()

    c.execute("SELECT pin FROM users")
    used_pins = {row[0] for row in c.fetchall()}

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
                print(f"[DRY] {name:30s} PIN={pin}  {stype}  {category}")
                added += 1
                continue

            try:
                c.execute(
                    "INSERT INTO users (name, pin, schedule_type, schedule_data, enabled) "
                    "VALUES (?, ?, ?, ?, 1)",
                    (name, pin, stype, sdata_str)
                )
                print(f"✓ {name:30s} PIN={pin}  {stype}  [{category}]")
                added += 1
            except sqlite3.IntegrityError:
                print(f"  SKIP (duplicate): {name}")
                skipped += 1

    if not dry_run:
        conn.commit()
    conn.close()

    print(f"\n{'DRY RUN — ' if dry_run else ''}Added: {added}  |  Skipped: {skipped}")
    if unknown:
        print(f"Unknown categories (not imported):")
        for u in unknown:
            print(f"  • {u}")

    if not dry_run:
        print("\nSave the PIN list above — give each staff member their PIN.")


if __name__ == "__main__":
    dry = '--dry' in sys.argv
    run(dry_run=dry)
