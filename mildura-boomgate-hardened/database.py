"""
database.py — Mildura Working Man's Club Boom Gate
All DB operations in one place.
"""
from __future__ import annotations

import json
import sqlite3
from contextlib import closing
from datetime import datetime
from typing import Optional

DB_NAME = "boom_gate.db"


def get_db():
    """Connection with WAL + busy timeout; closes via context manager."""
    conn = sqlite3.connect(DB_NAME, check_same_thread=False, timeout=30)
    conn.row_factory = sqlite3.Row
    try:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=5000")
        conn.execute("PRAGMA synchronous=NORMAL")
    except Exception:
        pass
    return closing(conn)


def init_db():
    with get_db() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS users (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                name          TEXT    NOT NULL,
                pin           TEXT    UNIQUE NOT NULL,
                enabled       INTEGER DEFAULT 1,
                schedule_type TEXT    DEFAULT 'always',
                schedule_data TEXT,
                created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE IF NOT EXISTS auto_schedules (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                name       TEXT    NOT NULL,
                days       TEXT    NOT NULL,
                open_time  TEXT    NOT NULL,
                close_time TEXT    NOT NULL,
                enabled    INTEGER DEFAULT 1,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE IF NOT EXISTS access_log (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                user_name  TEXT,
                method     TEXT DEFAULT 'pin',
                success    INTEGER,
                timestamp  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE IF NOT EXISTS changes_log (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                action     TEXT,
                detail     TEXT,
                changed_by TEXT,
                timestamp  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE IF NOT EXISTS auth_persons (
                id    INTEGER PRIMARY KEY AUTOINCREMENT,
                name  TEXT NOT NULL,
                role  TEXT,
                phone TEXT,
                pin   TEXT
            );

            CREATE TABLE IF NOT EXISTS settings (
                key   TEXT PRIMARY KEY,
                value TEXT
            );
            """
        )
        conn.commit()


def is_auto_open() -> tuple[bool, str]:
    """Overnight-aware auto-open. Morning tail belongs to previous scheduled day."""
    with get_db() as conn:
        schedules = conn.execute(
            "SELECT * FROM auto_schedules WHERE enabled = 1"
        ).fetchall()

    now = datetime.now()
    cur_day = now.weekday()
    prev_day = (cur_day - 1) % 7
    cur_time = now.time()

    for s in schedules:
        days = json.loads(s["days"])
        open_t = datetime.strptime(s["open_time"], "%H:%M").time()
        close_t = datetime.strptime(s["close_time"], "%H:%M").time()

        if open_t <= close_t:
            if cur_day in days and open_t <= cur_time <= close_t:
                return True, s["name"]
        else:
            if cur_day in days and cur_time >= open_t:
                return True, s["name"]
            if prev_day in days and cur_time <= close_t:
                return True, s["name"]

    return False, ""


def get_auto_schedules() -> list:
    with get_db() as conn:
        return conn.execute(
            "SELECT * FROM auto_schedules ORDER BY name"
        ).fetchall()


def add_auto_schedule(name: str, days: list, open_time: str, close_time: str):
    with get_db() as conn:
        conn.execute(
            "INSERT INTO auto_schedules (name, days, open_time, close_time) VALUES (?, ?, ?, ?)",
            (name, json.dumps(days), open_time, close_time),
        )
        conn.commit()


def delete_auto_schedule(sid: int):
    with get_db() as conn:
        conn.execute("DELETE FROM auto_schedules WHERE id = ?", (sid,))
        conn.commit()


def toggle_auto_schedule(sid: int) -> bool:
    with get_db() as conn:
        row = conn.execute(
            "SELECT enabled FROM auto_schedules WHERE id = ?", (sid,)
        ).fetchone()
        if row:
            new = 0 if row["enabled"] else 1
            conn.execute(
                "UPDATE auto_schedules SET enabled = ? WHERE id = ?", (new, sid)
            )
            conn.commit()
            return bool(new)
    return False


def has_access(pin: str) -> tuple[bool, str, str]:
    with get_db() as conn:
        row = conn.execute(
            "SELECT name, enabled, schedule_type, schedule_data FROM users WHERE pin = ?",
            (pin,),
        ).fetchone()

    if not row:
        return False, "—", "Invalid PIN"

    name, enabled, schedule_type, schedule_data = row

    if not enabled:
        return False, name, f"{name} — account disabled"

    if schedule_type == "always":
        return True, name, f"Welcome {name}"

    if schedule_type == "time_range":
        try:
            data = json.loads(schedule_data) if schedule_data else {}
            now = datetime.now()
            cur_day = now.weekday()
            prev_day = (cur_day - 1) % 7
            cur_time = now.time()
            allowed = data.get("days", [])
            start_s = data.get("start", "00:00")
            end_s = data.get("end", "23:59")

            start = datetime.strptime(start_s, "%H:%M").time()
            end = datetime.strptime(end_s, "%H:%M").time()

            if start <= end:
                in_window = (cur_day in allowed) and (start <= cur_time <= end)
            else:
                in_window = ((cur_day in allowed) and cur_time >= start) or (
                    (prev_day in allowed) and cur_time <= end
                )

            if in_window:
                return True, name, f"Welcome {name}"
            return False, name, f"{name} — outside hours ({start_s}–{end_s})"

        except Exception as e:
            return False, name, f"Schedule error: {e}"

    return False, name, "Invalid schedule"


def get_all_users() -> list:
    with get_db() as conn:
        return conn.execute("SELECT * FROM users ORDER BY name").fetchall()


def add_user(
    name: str,
    pin: str,
    schedule_type: str = "always",
    schedule_data: Optional[str] = None,
):
    with get_db() as conn:
        conn.execute(
            "INSERT INTO users (name, pin, schedule_type, schedule_data) VALUES (?, ?, ?, ?)",
            (name, pin, schedule_type, schedule_data),
        )
        conn.commit()


def delete_user(uid: int) -> Optional[str]:
    with get_db() as conn:
        row = conn.execute("SELECT name FROM users WHERE id = ?", (uid,)).fetchone()
        if row:
            conn.execute("DELETE FROM users WHERE id = ?", (uid,))
            conn.commit()
            return row["name"]
    return None


def toggle_user(uid: int) -> bool:
    with get_db() as conn:
        row = conn.execute(
            "SELECT name, enabled FROM users WHERE id = ?", (uid,)
        ).fetchone()
        if row:
            new = 0 if row["enabled"] else 1
            conn.execute("UPDATE users SET enabled = ? WHERE id = ?", (new, uid))
            conn.commit()
            return bool(new)
    return False


def update_user_schedule(
    uid: int, schedule_type: str, schedule_data: Optional[dict] = None
):
    data_str = json.dumps(schedule_data) if schedule_data else None
    with get_db() as conn:
        conn.execute(
            "UPDATE users SET schedule_type = ?, schedule_data = ? WHERE id = ?",
            (schedule_type, data_str, uid),
        )
        conn.commit()


def log_access(user_name: str, method: str, success: bool):
    with get_db() as conn:
        conn.execute(
            "INSERT INTO access_log (user_name, method, success, timestamp) VALUES (?, ?, ?, ?)",
            (
                user_name,
                method,
                int(success),
                datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            ),
        )
        conn.commit()


def get_logs(limit: int = 50) -> list:
    with get_db() as conn:
        return conn.execute(
            "SELECT * FROM access_log ORDER BY timestamp DESC LIMIT ?",
            (limit,),
        ).fetchall()


def log_change(action: str, detail: str = "", changed_by: str = "Admin") -> None:
    with get_db() as conn:
        conn.execute(
            "INSERT INTO changes_log (action, detail, changed_by) VALUES (?, ?, ?)",
            (action, detail, changed_by),
        )
        conn.commit()


def get_changes_log(limit: int = 50):
    with get_db() as conn:
        return conn.execute(
            "SELECT * FROM changes_log ORDER BY id DESC LIMIT ?", (limit,)
        ).fetchall()


def add_auth_person(name: str, role: str = "", phone: str = "", pin: str = "") -> None:
    with get_db() as conn:
        conn.execute(
            "INSERT INTO auth_persons (name, role, phone, pin) VALUES (?, ?, ?, ?)",
            (name, role, phone, pin),
        )
        conn.commit()


def get_auth_persons():
    with get_db() as conn:
        return conn.execute("SELECT * FROM auth_persons ORDER BY name").fetchall()


def delete_auth_person(pid: int) -> None:
    with get_db() as conn:
        conn.execute("DELETE FROM auth_persons WHERE id = ?", (pid,))
        conn.commit()


def get_keypad_stats(days: int = 30):
    try:
        days_i = max(1, min(int(days), 365))
    except (TypeError, ValueError):
        days_i = 30
    with get_db() as conn:
        return conn.execute(
            """SELECT user_name, COUNT(*) as count FROM access_log
               WHERE success = 1 AND method IN ('wiegand','web')
               AND timestamp >= datetime('now','localtime', ?)
               AND user_name NOT IN ('REMOTE','SCHEDULE','AUTO-OPEN','UNKNOWN')
               GROUP BY user_name ORDER BY count DESC LIMIT 10""",
            (f"-{days_i} days",),
        ).fetchall()


def enable_all_users() -> int:
    with get_db() as conn:
        cur = conn.execute("UPDATE users SET enabled = 1")
        conn.commit()
        return cur.rowcount


def disable_all_users() -> int:
    with get_db() as conn:
        cur = conn.execute("UPDATE users SET enabled = 0")
        conn.commit()
        return cur.rowcount


def delete_all_users() -> int:
    with get_db() as conn:
        cur = conn.execute("DELETE FROM users")
        conn.commit()
        return cur.rowcount


def _ensure_settings():
    with get_db() as conn:
        conn.execute(
            "CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)"
        )
        conn.commit()


def get_setting(key, default=None):
    _ensure_settings()
    with get_db() as conn:
        row = conn.execute(
            "SELECT value FROM settings WHERE key=?", (key,)
        ).fetchone()
    return row["value"] if row else default


def set_setting(key, value):
    _ensure_settings()
    with get_db() as conn:
        conn.execute(
            "INSERT INTO settings (key, value) VALUES (?,?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (key, str(value)),
        )
        conn.commit()


def is_lockdown() -> bool:
    return get_setting("lockdown", "0") == "1"


def set_lockdown(on: bool):
    set_setting("lockdown", "1" if on else "0")
    return bool(on)
