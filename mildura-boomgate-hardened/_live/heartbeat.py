"""heartbeat.py — reports gate status to RJL Gate Fleet (Airtable)"""
import os, threading, time, urllib.request, json
from datetime import datetime
import database as db

AIRTABLE_TOKEN = os.environ.get('AIRTABLE_TOKEN', '')
BASE_ID  = 'appv6EVjfYiSIasBq'
TABLE_ID = 'tbllDuBz0rz0j2lOc'
RECORD_ID = 'rec8m5rgYhzzX8Jp0'
INTERVAL  = 300

def _entries_today():
    try:
        with db.get_db() as conn:
            r = conn.execute("SELECT COUNT(*) c FROM access_log WHERE success=1 AND method IN ('wiegand','web') AND date(timestamp)=date('now','localtime')").fetchone()
            return r['c'] if r else 0
    except Exception:
        return 0

def _last_access():
    try:
        with db.get_db() as conn:
            r = conn.execute("SELECT user_name, timestamp FROM access_log WHERE success=1 ORDER BY id DESC LIMIT 1").fetchone()
            if r:
                return f"{r['user_name']} @ {r['timestamp'][:16]}"
    except Exception:
        pass
    return "No access yet"

def _total_users():
    try:
        with db.get_db() as conn:
            return conn.execute("SELECT COUNT(*) c FROM users").fetchone()['c']
    except Exception:
        return 0

def _send():
    if not AIRTABLE_TOKEN:
        print("[HEARTBEAT] No AIRTABLE_TOKEN set — skipping")
        return
    url = f"https://api.airtable.com/v0/{BASE_ID}/{TABLE_ID}/{RECORD_ID}"
    payload = {"fields": {
        "flddoKhf9fkHDiQzk": "online",
        "fldMRvLdqGmv6ajgF": datetime.now().astimezone().isoformat(),
        "fldjgruff1jxisfoR": _last_access(),
        "fldVCaWJHxX8EgPiL": _entries_today(),
        "fldsNIxP09dDp1gbT": _total_users(),
    }}
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), method='PATCH', headers={
        'Authorization': f'Bearer {AIRTABLE_TOKEN}',
        'Content-Type': 'application/json',
    })
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            if r.status == 200:
                print(f"[HEARTBEAT] Sent OK at {datetime.now().strftime('%H:%M')}")
    except Exception as e:
        print(f"[HEARTBEAT] Failed: {e}")

def _loop():
    time.sleep(10)
    while True:
        try:
            _send()
        except Exception as e:
            print(f"[HEARTBEAT] Loop error (thread still alive, will retry): {e}")
        time.sleep(INTERVAL)

def start():
    threading.Thread(target=_loop, daemon=True, name="heartbeat").start()
    print("[HEARTBEAT] Started (reporting every 5 min)")
