#!/usr/bin/env python3
# Drains boom_gate.db access_log + changes_log to the Airtable "Logs" table.
# Local DB is source of truth; standalone service so it never touches the gate.

import os, sqlite3, time, requests, logging
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

BASE_ID  = "appv6EVjfYiSIasBq"
TABLE_ID = "tblRFyPSkAZmudVIc"
DB_PATH  = "/home/rjlcommercial/boomgate/boom_gate.db"
UNIT_ID  = os.environ.get("UNIT_ID", "mildura-001")
TOKEN    = os.environ["AIRTABLE_TOKEN"]

SYNC_INTERVAL = 60
MAX_PER_CYCLE = 200
CHUNK         = 10
URL     = f"https://api.airtable.com/v0/{BASE_ID}/{TABLE_ID}"
HEADERS = {"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"}
MELB    = ZoneInfo("Australia/Melbourne")
SOURCE_MAP = {"pin": "Keypad", "wiegand": "Keypad", "auto": "System", "remote": "Dashboard"}

logging.basicConfig(level=logging.INFO, format="%(asctime)s sync %(message)s")

def iso(raw, tz):
    if not raw: return None
    s = str(raw).strip().replace("T", " ").split(".")[0]
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M"):
        try:
            return datetime.strptime(s, fmt).replace(tzinfo=tz).isoformat()
        except ValueError:
            continue
    return None

def connect():
    c = sqlite3.connect(DB_PATH, timeout=15)
    c.row_factory = sqlite3.Row
    c.execute("PRAGMA journal_mode=WAL;")
    c.execute("PRAGMA busy_timeout=10000;")
    return c

def ensure_columns(c):
    for t in ("access_log", "changes_log"):
        try:
            c.execute(f"ALTER TABLE {t} ADD COLUMN synced INTEGER DEFAULT 0")
            logging.info("added synced column to %s", t)
        except sqlite3.OperationalError:
            pass
    c.commit()

def build_access(r):
    ok = (r["success"] == 1)
    method = (r["method"] or "pin")
    return {
        "Log Key": f"{UNIT_ID}-access-{r['id']}",
        "Unit ID": UNIT_ID,
        # Access log timestamps are local (Australia/Melbourne), not UTC.
        "Timestamp": iso(r["timestamp"], MELB),
        "Category": "Access",
        "Result": "Granted" if ok else "Denied",
        "User": r["user_name"] or "",
        "Detail": f"{method} entry {'granted' if ok else 'denied'}",
        "Source": SOURCE_MAP.get(method.lower(), "System"),
    }

def build_change(r):
    detail = r["action"] or ""
    if r["detail"]:
        detail = f"{r['action']}: {r['detail']}"
    return {
        "Log Key": f"{UNIT_ID}-change-{r['id']}",
        "Unit ID": UNIT_ID,
        "Timestamp": iso(r["timestamp"], MELB),
        "Category": "Change",
        "Result": "Info",
        "User": r["changed_by"] or "Admin",
        "Detail": detail,
        "Source": "Dashboard",
    }

SOURCES = [
    ("access_log",
     "SELECT id,user_name,method,success,timestamp FROM access_log WHERE synced=0 ORDER BY id LIMIT ?",
     build_access),
    ("changes_log",
     "SELECT id,timestamp,action,detail,changed_by FROM changes_log WHERE synced=0 ORDER BY id LIMIT ?",
     build_change),
]

def push(records):
    payload = {"performUpsert": {"fieldsToMergeOn": ["Log Key"]},
               "records": [{"fields": f} for f in records]}
    r = requests.patch(URL, headers=HEADERS, json=payload, timeout=(5, 20))
    r.raise_for_status()

def cycle(c):
    for table, sql, build in SOURCES:
        rows = c.execute(sql, (MAX_PER_CYCLE,)).fetchall()
        if not rows: continue
        for i in range(0, len(rows), CHUNK):
            batch = rows[i:i+CHUNK]
            recs  = [build(r) for r in batch]
            ids   = [r["id"] for r in batch]
            push(recs)
            q = ",".join("?" * len(ids))
            c.execute(f"UPDATE {table} SET synced=1 WHERE id IN ({q})", ids)
            c.commit()
            logging.info("synced %d from %s", len(ids), table)

def main():
    c = connect()
    ensure_columns(c)
    logging.info("syncer started -> %s", UNIT_ID)
    while True:
        try:
            cycle(c)
        except requests.exceptions.RequestException as e:
            logging.warning("deferred (net/airtable): %s", e)
        except Exception as e:
            logging.error("unexpected: %s", e)
        time.sleep(SYNC_INTERVAL)

if __name__ == "__main__":
    main()
