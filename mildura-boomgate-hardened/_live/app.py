"""
app.py — Mildura Working Man's Club Boom Gate Controller
Flask web app + Wiegand physical reader + auto-open scheduler
All running in one process.

Hardware:
  Relay (gate trigger) → BCM GPIO 22 (physical pin 15)
  Hold / ORO           → BCM GPIO 23 (physical pin 16)
  Wiegand D0 (keypad)  → BCM GPIO 17 (physical pin 11)
  Wiegand D1 (keypad)  → BCM GPIO 27 (physical pin 13)

Audit hardening (2026-08):
  - pulse_gate always force-OFF in finally + re-assert hold only if wanted
  - separate manual vs schedule hold desires (hold_should_be_on)
  - lockdown fail-closed on every actuation path including /gate/open
  - ignore keypad/web pulses during auto-open (constant hold only)
  - thread-safe rate limiters + Wiegand brute-force lockout
  - Origin/Referer CSRF guard on admin POSTs
  - HTML-escaped PDF export; PINs masked
  - schedule form validation; admin PIN reserved
"""

from __future__ import annotations

import html
import json
import hmac
import os
import re
import secrets
import sqlite3
import threading
import time
from collections import defaultdict
from datetime import datetime, timedelta
from functools import wraps
from urllib.parse import urlparse

from flask import (
    Flask,
    abort,
    flash,
    jsonify,
    redirect,
    render_template,
    request,
    session,
    url_for,
)

import database as db

# ── ADMIN LOGIN ───────────────────────────────────────────────────────────────
# NEVER ship a default PIN. Must be set in the systemd EnvironmentFile.
ADMIN_PIN = os.environ.get("ADMIN_PIN")
if not ADMIN_PIN:
    raise RuntimeError(
        "ADMIN_PIN environment variable is required. "
        "Set it in /etc/boomgate.env (EnvironmentFile) and restart."
    )
if not ADMIN_PIN.isdigit() or not (4 <= len(ADMIN_PIN) <= 8):
    raise RuntimeError("ADMIN_PIN must be 4–8 digits.")

# Require lockdown helpers — fail closed, never silently skip lockdown.
for _req in ("is_lockdown", "set_lockdown"):
    if not hasattr(db, _req):
        raise RuntimeError(f"database.{_req} missing — refusing to start")

_HHMM = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")


def _valid_hhmm(value: str) -> bool:
    return bool(_HHMM.match((value or "").strip()))


def _parse_days(raw_list) -> list[int]:
    days: list[int] = []
    for d in raw_list:
        try:
            i = int(d)
        except (TypeError, ValueError):
            continue
        if 0 <= i <= 6 and i not in days:
            days.append(i)
    return days


# ── RATE LIMITING / LOCKOUT ───────────────────────────────────────────────────
class RateLimiter:
    def __init__(self, max_fails=5, window=60, lockout=300):
        self.max_fails = max_fails
        self.window = window
        self.lockout = lockout
        self._attempts: dict[str, list[float]] = defaultdict(list)
        self._lockouts: dict[str, float] = {}
        self._lock = threading.Lock()

    def is_limited(self, key: str) -> bool:
        now = time.time()
        with self._lock:
            until = self._lockouts.get(key, 0)
            if now < until:
                return True
            self._attempts[key] = [
                t for t in self._attempts[key] if now - t < self.window
            ]
            if len(self._attempts[key]) >= self.max_fails:
                self._lockouts[key] = now + self.lockout
                self._attempts[key].clear()
                return True
            return False

    def record_fail(self, key: str) -> None:
        with self._lock:
            self._attempts[key].append(time.time())

    def record_success(self, key: str) -> None:
        with self._lock:
            self._attempts.pop(key, None)
            self._lockouts.pop(key, None)


login_limiter = RateLimiter(max_fails=5, window=60, lockout=300)
gate_limiter = RateLimiter(max_fails=8, window=60, lockout=300)
wiegand_limiter = RateLimiter(max_fails=12, window=180, lockout=300)


def _client_ip() -> str:
    # Real socket address ONLY — never trust X-Forwarded-For without a proxy.
    return request.remote_addr or "unknown"


# ── GPIO / RELAY ──────────────────────────────────────────────────────────────
RELAY_PIN = 22  # BCM GPIO 22 — gate pulse relay
HOLD_PIN = 23   # BCM GPIO 23 — hold-open relay (Roger ORO)
PULSE_SECONDS = float(os.environ.get("PULSE_SECONDS", "1.5"))

os.environ.setdefault("GPIOZERO_PIN_FACTORY", "lgpio")

try:
    from gpiozero import OutputDevice

    relay = OutputDevice(RELAY_PIN, active_high=True, initial_value=False)
    hold_relay = OutputDevice(HOLD_PIN, active_high=True, initial_value=False)
    GPIO_AVAILABLE = True
    print(f"[GPIO] Relay ready on BCM {RELAY_PIN}, hold on BCM {HOLD_PIN}")
except Exception as err:
    print(f"[GPIO] Unavailable ({err}) — simulation mode")
    GPIO_AVAILABLE = False
    relay = None
    hold_relay = None

_relay_lock = threading.Lock()
_hold_lock = threading.RLock()
_gate_lock = threading.RLock()  # serialises lockdown / hold / pulse decisions
_manual_hold = False
_schedule_hold = False
_sim_open = False
_sim_hold = False


def _open_on() -> None:
    global _sim_open
    if GPIO_AVAILABLE and relay is not None:
        relay.on()
    else:
        _sim_open = True


def _open_off() -> None:
    global _sim_open
    if GPIO_AVAILABLE and relay is not None:
        relay.off()
    else:
        _sim_open = False


def _mirror_on() -> None:
    global _sim_hold
    if GPIO_AVAILABLE and hold_relay is not None:
        hold_relay.on()
    else:
        _sim_hold = True


def _mirror_off() -> None:
    global _sim_hold
    if GPIO_AVAILABLE and hold_relay is not None:
        hold_relay.off()
    else:
        _sim_hold = False


def pulse_open_state() -> bool:
    if GPIO_AVAILABLE and relay is not None:
        try:
            return bool(relay.value)
        except Exception:
            return False
    return _sim_open


def hold_open_state() -> bool:
    if GPIO_AVAILABLE and hold_relay is not None:
        try:
            return bool(hold_relay.value)
        except Exception:
            return False
    return _sim_hold


def hold_should_be_on() -> tuple[bool, str]:
    """Desired constant-hold state from manual + schedule desires + lockdown."""
    if db.is_lockdown():
        return False, "lockdown"
    with _hold_lock:
        if _manual_hold:
            return True, "manual"
        if _schedule_hold:
            return True, "schedule"
    return False, "idle"


def assert_long_hold(reason: str = "hold") -> None:
    with _hold_lock:
        _mirror_on()
    print(f"[SIGNAL] CONSTANT HOLD ON — {reason}", flush=True)


def cut_all_signals(reason: str = "cut", quiet: bool = False) -> None:
    """Force open + hold lines OFF (safe state)."""
    try:
        _open_off()
    except Exception as err:
        print(f"[SIGNAL] open OFF failed: {err}", flush=True)
    try:
        with _hold_lock:
            _mirror_off()
    except Exception as err:
        print(f"[SIGNAL] hold OFF failed: {err}", flush=True)
    if not quiet:
        print(f"[SIGNAL] CUT ALL — {reason}", flush=True)


def apply_hold_desire(reason: str = "reconcile") -> None:
    want, why = hold_should_be_on()
    if want:
        assert_long_hold(f"{reason}:{why}")
    else:
        cut_all_signals(f"{reason}:{why}", quiet=False)


def pulse_gate(reason: str = "manual") -> bool:
    """Keypad short pulse on open line (ONLY when boom is down).

    During auto-open / manual hold: constant hold only — never pulse.

    SAFETY (post-PIN stuck-open fix):
      Always force the open line OFF after the pulse window, even if an
      exception occurs. Then re-assert constant hold ONLY if schedule /
      manual hold still wants it. Keypad must never leave GPIO22 stuck ON
      when auto-open is idle.
    """
    with _gate_lock:
        if db.is_lockdown():
            print(f"[SIGNAL] IGNORE pulse during lockdown: {reason!r}", flush=True)
            return False
        long_hold, hold_reason = hold_should_be_on()
        if long_hold:
            print(
                f"[SIGNAL] IGNORE pulse during hold ({hold_reason}): {reason!r}",
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

        try:
            with _gate_lock:
                if db.is_lockdown():
                    cut_all_signals(f"post-pulse-lockdown:{reason}", quiet=False)
                else:
                    want, why = hold_should_be_on()
                    if want:
                        print(
                            f"[SIGNAL] re-assert constant hold after pulse ({why})",
                            flush=True,
                        )
                        assert_long_hold(f"post-pulse:{why}")
                    elif pulse_open_state() or hold_open_state():
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


def trigger_async(reason: str) -> None:
    threading.Thread(target=pulse_gate, args=(reason,), daemon=True).start()


def set_manual_hold(on: bool, actor: str = "Admin") -> bool:
    global _manual_hold
    with _gate_lock:
        if on and db.is_lockdown():
            return False
        with _hold_lock:
            _manual_hold = bool(on)
        apply_hold_desire(f"manual:{actor}")
    detail = "Hold-open ENABLED (ORO held)" if on else "Hold-open DISABLED"
    if hasattr(db, "log_change"):
        try:
            db.log_change("HOLD", detail, actor)
        except Exception:
            pass
    print(f"[HOLD] {detail}", flush=True)
    return bool(on)


def set_schedule_hold(on: bool, reason: str = "schedule") -> None:
    global _schedule_hold
    with _gate_lock:
        with _hold_lock:
            _schedule_hold = bool(on) and not db.is_lockdown()
        apply_hold_desire(reason)


# ── AUTO-OPEN SCHEDULER ───────────────────────────────────────────────────────
_was_auto_open = False


def check_auto_open() -> None:
    """Holds boom OPEN via ORO for the whole auto-open window; releases at end.
    Lockdown overrides everything. Manual hold is independent."""
    global _was_auto_open
    with _gate_lock:
        locked = db.is_lockdown()
        now_open, sched_name = db.is_auto_open()
        if locked:
            now_open = False

        if now_open and not _was_auto_open:
            print(f"[SCHEDULE] Auto-open started: {sched_name}", flush=True)
            db.log_access("SCHEDULE", "auto-open", True)
            set_schedule_hold(True, "auto-open:" + str(sched_name))
        elif now_open and _was_auto_open:
            want, _ = hold_should_be_on()
            if not want or not hold_open_state():
                set_schedule_hold(True, "auto-open:" + str(sched_name))
        elif not now_open and _was_auto_open:
            why = "lockdown" if locked else "schedule ended"
            print(f"[SCHEDULE] Auto-open finished ({why}) — releasing schedule hold", flush=True)
            db.log_access("SCHEDULE", "auto-close", True)
            set_schedule_hold(False, "auto-close")
        elif locked and (_schedule_hold or hold_open_state()):
            set_schedule_hold(False, "lockdown")
            cut_all_signals("scheduler-lockdown", quiet=False)

        _was_auto_open = now_open


def scheduler_thread() -> None:
    while True:
        try:
            check_auto_open()
        except Exception as e:
            print(f"[SCHEDULER] Error: {e}", flush=True)
        time.sleep(30)


# ── WIEGAND CALLBACK ──────────────────────────────────────────────────────────
def on_wiegand(raw_pin: str) -> None:
    pin = (raw_pin or "").strip()
    if not pin.isdigit():
        return

    with _gate_lock:
        if db.is_lockdown():
            print("[WIEGAND] BLOCKED - lockdown active", flush=True)
            db.log_access("—", "wiegand-BLOCKED", False)
            return
        long_hold, hold_reason = hold_should_be_on()
        if long_hold:
            # Constant hold already open — never pulse during auto-open/manual hold.
            print(
                f"[WIEGAND] IGNORE during hold ({hold_reason}) — constant hold only",
                flush=True,
            )
            db.log_access("AUTO-OPEN", "wiegand-ignored", True)
            return

    if wiegand_limiter.is_limited("wiegand"):
        print("[WIEGAND] Locked out — too many bad PINs", flush=True)
        db.log_access("LOCKED", "wiegand", False)
        return

    granted, user_name, message = db.has_access(pin)
    db.log_access(user_name, "wiegand", granted)
    print(f"[WIEGAND] {message}", flush=True)
    if granted:
        wiegand_limiter.record_success("wiegand")
        trigger_async("wiegand")
    else:
        wiegand_limiter.record_fail("wiegand")


# ── FLASK ─────────────────────────────────────────────────────────────────────
app = Flask(__name__)

_secret = os.environ.get("FLASK_SECRET_KEY") or os.environ.get("SECRET_KEY")
if not _secret:
    # Persist a local secret so sessions survive restart even if env is missing.
    _secret_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".flask_secret")
    try:
        if os.path.exists(_secret_path):
            _secret = open(_secret_path, "r", encoding="utf-8").read().strip()
        if not _secret:
            _secret = secrets.token_hex(32)
            fd = os.open(_secret_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                fh.write(_secret)
            print(
                "WARNING: FLASK_SECRET_KEY not set — wrote one to .flask_secret. "
                "Prefer EnvironmentFile in production.",
                flush=True,
            )
    except Exception as err:
        _secret = secrets.token_hex(32)
        print(f"WARNING: could not persist Flask secret ({err}) — temporary key", flush=True)

app.secret_key = _secret
app.permanent_session_lifetime = timedelta(days=7)
app.config["SESSION_COOKIE_HTTPONLY"] = True
app.config["SESSION_COOKIE_SAMESITE"] = "Strict"
# Enable with TLS if ever exposed beyond Tailscale:
# app.config["SESSION_COOKIE_SECURE"] = True


@app.before_request
def csrf_protect():
    """Reject cross-site state-changing requests on admin POSTs.
    /gate is public keypad and uses rate-limiting instead."""
    if request.method != "POST":
        return
    if request.path == "/gate":
        return
    origin = request.headers.get("Origin")
    referer = request.headers.get("Referer")
    host = request.host
    for candidate in (origin, referer):
        if candidate:
            try:
                if urlparse(candidate).netloc == host:
                    return
            except Exception:
                pass
            abort(403)
    # No Origin/Referer (some older clients) — allow same-host form posts.
    return


def login_required(f):
    @wraps(f)
    def wrapper(*args, **kwargs):
        if not session.get("admin"):
            return redirect(url_for("login", next=request.path))
        return f(*args, **kwargs)

    return wrapper


def _format_days(day_list: list) -> str:
    names = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    return " ".join(names[d] for d in sorted(day_list) if 0 <= d <= 6)


app.jinja_env.filters["fromjson"] = json.loads
app.jinja_env.filters["format_days"] = _format_days

from dashboard_api import register_dashboard_api, get_cpu_temp

register_dashboard_api(app, db, login_required)


@app.route("/login", methods=["GET", "POST"])
def login():
    if request.method == "POST":
        ip = _client_ip()
        if login_limiter.is_limited(ip):
            return render_template(
                "login.html",
                error="Too many attempts. Try again in a few minutes.",
            ), 429

        entered = request.form.get("pin", "").strip()
        if hmac.compare_digest(entered, ADMIN_PIN):
            login_limiter.record_success(ip)
            session.permanent = True
            session["admin"] = True
            nxt = request.args.get("next") or url_for("dashboard")
            if not (nxt and nxt.startswith("/") and not nxt.startswith("//")):
                nxt = url_for("dashboard")
            return redirect(nxt)

        login_limiter.record_fail(ip)
        return render_template("login.html", error="Incorrect admin PIN")

    if session.get("admin"):
        return redirect(url_for("dashboard"))
    return render_template("login.html", error=None)


@app.route("/logout", methods=["POST", "GET"])
def logout():
    session.clear()
    return redirect(url_for("login"))


@app.route("/")
@login_required
def dashboard():
    auto_open, auto_name = db.is_auto_open()
    lockdown = db.is_lockdown()
    try:
        hold = hold_open_state() or hold_should_be_on()[0]
    except Exception:
        hold = False
    auth_persons = db.get_auth_persons() if hasattr(db, "get_auth_persons") else []
    changes = db.get_changes_log(40) if hasattr(db, "get_changes_log") else []
    return render_template(
        "dashboard.html",
        users=db.get_all_users(),
        logs=db.get_logs(40),
        auto_schedules=db.get_auto_schedules(),
        auth_persons=auth_persons,
        changes=changes,
        current_requester=session.get("requester"),
        lockdown=lockdown,
        hold_open=hold,
        auto_open=auto_open,
        auto_name=auto_name,
        gpio=GPIO_AVAILABLE,
        now=datetime.now().strftime("%a %d %b %H:%M"),
    )


@app.route("/gate", methods=["POST"])
def gate():
    """Web keypad PIN entry (used by /keypad page)."""
    if db.is_lockdown():
        db.log_access("—", "web-BLOCKED", False)
        return jsonify({"ok": False, "message": "Gate is in lockdown."}), 403

    ip = _client_ip()
    if gate_limiter.is_limited(ip):
        return jsonify({"ok": False, "message": "Too many attempts. Try again later."}), 429

    data = request.get_json(silent=True) or {}
    pin = str(data.get("pin", "")).strip()

    long_hold, hold_reason = hold_should_be_on()
    if long_hold:
        # Constant hold already open — do not pulse.
        db.log_access("AUTO-OPEN", "web-ignored", True)
        return jsonify({
            "ok": True,
            "name": f"Hold active ({hold_reason})",
            "triggered": False,
            "message": "Gate already held open — no pulse sent.",
        })

    granted, user_name, message = db.has_access(pin)
    db.log_access(user_name, "web", granted)

    if granted:
        gate_limiter.record_success(ip)
        trigger_async("web")
        return jsonify({"ok": True, "name": user_name, "message": message})

    gate_limiter.record_fail(ip)
    return jsonify({"ok": False, "message": message}), 401


@app.route("/gate/open", methods=["POST"])
@login_required
def gate_open():
    """Manual remote trigger from dashboard."""
    if db.is_lockdown():
        db.log_access("—", "dashboard-BLOCKED", False)
        flash("Gate is in LOCKDOWN — remote open blocked.", "error")
        return redirect(url_for("dashboard"))
    long_hold, hold_reason = hold_should_be_on()
    if long_hold:
        flash(f"Gate already held open ({hold_reason}) — no pulse sent.", "success")
        return redirect(url_for("dashboard"))
    trigger_async("remote-dashboard")
    db.log_access("REMOTE", "dashboard", True)
    flash("Gate triggered remotely.", "success")
    return redirect(url_for("dashboard"))


@app.route("/api/status")
def api_status():
    """Minimal public status for keypad polling."""
    auto_open, auto_name = db.is_auto_open()
    try:
        hold = hold_open_state() or hold_should_be_on()[0]
    except Exception:
        hold = False
    return jsonify({
        "auto_open": auto_open,
        "auto_name": auto_name if auto_open else "",
        "lockdown": db.is_lockdown(),
        "hold": hold,
        "gpio": GPIO_AVAILABLE,
        "time": datetime.now().strftime("%H:%M"),
    })


@app.route("/add_user", methods=["POST"])
@login_required
def add_user():
    name = request.form.get("name", "").strip()
    pin = request.form.get("pin", "").strip()
    schedule_type = request.form.get("schedule_type", "always")
    days = _parse_days(request.form.getlist("days"))
    start = request.form.get("start", "08:00").strip()
    end = request.form.get("end", "17:00").strip()

    if not name or not pin:
        flash("Name and PIN are required.", "error")
        return redirect(url_for("dashboard"))
    if not pin.isdigit() or not (4 <= len(pin) <= 8):
        flash("PIN must be 4–8 digits.", "error")
        return redirect(url_for("dashboard"))
    if pin.startswith("0"):
        flash("PIN cannot start with 0 (keypad hardware limitation).", "error")
        return redirect(url_for("dashboard"))
    if hmac.compare_digest(pin, ADMIN_PIN):
        flash("That PIN is reserved for admin login.", "error")
        return redirect(url_for("dashboard"))
    if schedule_type == "time_range":
        if not days:
            flash("Select at least one day for timed access.", "error")
            return redirect(url_for("dashboard"))
        if not (_valid_hhmm(start) and _valid_hhmm(end)):
            flash("Start/end must be HH:MM.", "error")
            return redirect(url_for("dashboard"))

    sched = (
        json.dumps({"days": days, "start": start, "end": end})
        if schedule_type == "time_range"
        else None
    )
    try:
        db.add_user(name, pin, schedule_type, sched)
        flash(f"{name} added (PIN set).", "success")
    except sqlite3.IntegrityError:
        flash("That PIN is already in use.", "error")
    except Exception as e:
        flash(f"Could not add user: {e}", "error")
    return redirect(url_for("dashboard"))


@app.route("/delete_user/<int:uid>", methods=["POST"])
@login_required
def delete_user(uid):
    name = db.delete_user(uid)
    if name:
        flash(f"{name} removed.", "success")
    return redirect(url_for("dashboard"))


@app.route("/toggle_user/<int:uid>", methods=["POST"])
@login_required
def toggle_user(uid):
    db.toggle_user(uid)
    return redirect(url_for("dashboard"))


@app.route("/update_schedule/<int:uid>", methods=["POST"])
@login_required
def update_schedule(uid):
    schedule_type = request.form.get("schedule_type", "always")
    days = _parse_days(request.form.getlist("days"))
    start = request.form.get("start", "08:00").strip()
    end = request.form.get("end", "17:00").strip()
    if schedule_type == "time_range":
        if not days or not (_valid_hhmm(start) and _valid_hhmm(end)):
            flash("Invalid schedule (days + HH:MM required).", "error")
            return redirect(url_for("dashboard"))
        data = {"days": days, "start": start, "end": end}
    else:
        data = None
    db.update_user_schedule(uid, schedule_type, data)
    flash("Schedule updated.", "success")
    return redirect(url_for("dashboard"))


@app.route("/add_auto_schedule", methods=["POST"])
@login_required
def add_auto_schedule():
    name = request.form.get("sched_name", "").strip()
    days = _parse_days(request.form.getlist("sched_days"))
    open_time = request.form.get("sched_open", "10:00").strip()
    close_time = request.form.get("sched_close", "22:00").strip()

    if not name:
        flash("Schedule name required.", "error")
        return redirect(url_for("dashboard"))
    if not days:
        flash("Select at least one day.", "error")
        return redirect(url_for("dashboard"))
    if not (_valid_hhmm(open_time) and _valid_hhmm(close_time)):
        flash("Open/close must be HH:MM.", "error")
        return redirect(url_for("dashboard"))

    db.add_auto_schedule(name, days, open_time, close_time)
    flash(f'Auto-open schedule "{name}" added.', "success")
    return redirect(url_for("dashboard"))


@app.route("/delete_auto_schedule/<int:sid>", methods=["POST"])
@login_required
def delete_auto_schedule(sid):
    db.delete_auto_schedule(sid)
    flash("Schedule removed.", "success")
    return redirect(url_for("dashboard"))


@app.route("/toggle_auto_schedule/<int:sid>", methods=["POST"])
@login_required
def toggle_auto_schedule(sid):
    db.toggle_auto_schedule(sid)
    return redirect(url_for("dashboard"))


@app.route("/hold_open", methods=["POST"])
@login_required
def hold_open():
    if db.is_lockdown():
        flash("Gate is in LOCKDOWN - hold-open blocked.", "error")
        return redirect(url_for("dashboard"))
    with _hold_lock:
        new_state = not _manual_hold
    ok = set_manual_hold(new_state, session.get("requester", "Admin"))
    if not ok and new_state:
        flash("Gate is in LOCKDOWN - hold-open blocked.", "error")
    else:
        flash("Gate held OPEN." if new_state else "Hold-open released.", "success")
    return redirect(url_for("dashboard"))


@app.route("/lockdown", methods=["POST"])
@login_required
def lockdown():
    new_state = request.form.get("state", "").strip().lower() == "on"
    if new_state == db.is_lockdown():
        flash("Lockdown already " + ("enabled." if new_state else "cleared."), "success")
        return redirect(url_for("dashboard"))

    with _gate_lock:
        db.set_lockdown(new_state)
        if new_state:
            global _manual_hold, _schedule_hold, _was_auto_open
            with _hold_lock:
                _manual_hold = False
                _schedule_hold = False
            _was_auto_open = False
            cut_all_signals("lockdown-enabled", quiet=False)
            if hasattr(db, "log_change"):
                db.log_change(
                    "LOCKDOWN",
                    "Gate LOCKED DOWN - all access blocked",
                    session.get("requester", "Admin"),
                )
            flash("LOCKDOWN ENABLED - all gate access blocked.", "error")
        else:
            if hasattr(db, "log_change"):
                db.log_change(
                    "LOCKDOWN",
                    "Lockdown cleared - normal operation resumed",
                    session.get("requester", "Admin"),
                )
            # Reconcile schedule after unlock
            check_auto_open()
            flash("Lockdown cleared - normal operation resumed.", "success")
    return redirect(url_for("dashboard"))


@app.route("/set_requester", methods=["POST"])
@login_required
def set_requester():
    name = request.form.get("person_name", "").strip()
    if name:
        session["requester"] = name
        if hasattr(db, "log_change"):
            db.log_change("SESSION", f"Change session started by {name}", name)
        flash(f"Session started - requested by {name}.", "success")
    return redirect(url_for("dashboard"))


@app.route("/clear_requester", methods=["POST"])
@login_required
def clear_requester():
    who = session.pop("requester", None)
    if who and hasattr(db, "log_change"):
        db.log_change("SESSION", "Change session ended", who)
    flash("Session ended.", "success")
    return redirect(url_for("dashboard"))


@app.route("/add_auth_person", methods=["POST"])
@login_required
def add_auth_person():
    name = request.form.get("ap_name", "").strip()
    role = request.form.get("ap_role", "").strip()
    phone = request.form.get("ap_phone", "").strip()
    pin = request.form.get("ap_pin", "").strip()
    if not name or not pin:
        flash("Name and verification PIN are required.", "error")
        return redirect(url_for("dashboard"))
    db.add_auth_person(name, role, phone, pin)
    if hasattr(db, "log_change"):
        db.log_change(
            "AUTH-PERSON",
            f"Added authorised person {name}",
            session.get("requester", "Admin"),
        )
    flash(f"Authorised person {name} added.", "success")
    return redirect(url_for("dashboard"))


@app.route("/delete_auth_person/<int:pid>", methods=["POST"])
@login_required
def delete_auth_person(pid):
    db.delete_auth_person(pid)
    flash("Authorised person removed.", "success")
    return redirect(url_for("dashboard"))


@app.route("/enable_all", methods=["POST"])
@login_required
def enable_all():
    n = db.enable_all_users()
    if hasattr(db, "log_change"):
        db.log_change("USERS", f"Enabled all users ({n})", session.get("requester", "Admin"))
    flash(f"Enabled {n} user(s).", "success")
    return redirect(url_for("dashboard"))


@app.route("/disable_all", methods=["POST"])
@login_required
def disable_all():
    n = db.disable_all_users()
    if hasattr(db, "log_change"):
        db.log_change("USERS", f"Disabled all users ({n})", session.get("requester", "Admin"))
    flash(f"Disabled {n} user(s).", "success")
    return redirect(url_for("dashboard"))


@app.route("/delete_all", methods=["POST"])
@login_required
def delete_all():
    confirm = request.form.get("confirm_pin", "").strip()
    if not hmac.compare_digest(confirm, ADMIN_PIN):
        flash("Admin PIN incorrect - nothing deleted.", "error")
        return redirect(url_for("dashboard"))
    n = db.delete_all_users()
    if hasattr(db, "log_change"):
        db.log_change("USERS", f"DELETED ALL users ({n})", session.get("requester", "Admin"))
    flash(f"Deleted {n} user(s).", "success")
    return redirect(url_for("dashboard"))


@app.route("/api/live")
@login_required
def api_live():
    auto_open, auto_name = db.is_auto_open()
    try:
        hold = hold_open_state() or hold_should_be_on()[0]
    except Exception:
        hold = False
    lockdown = db.is_lockdown()
    today = datetime.now().strftime("%Y-%m-%d")
    with db.get_db() as conn:
        r = conn.execute(
            "SELECT COUNT(*) t, SUM(success=1) g, SUM(success=0) d "
            "FROM access_log WHERE date(timestamp)=?",
            (today,),
        ).fetchone()
        t_total, t_grant, t_deny = (r["t"] or 0), (r["g"] or 0), (r["d"] or 0)
        ur = conn.execute("SELECT COUNT(*) t, SUM(enabled=1) e FROM users").fetchone()
        u_total, u_en = (ur["t"] or 0), (ur["e"] or 0)
        last = conn.execute(
            "SELECT user_name, method, success, timestamp FROM access_log "
            "ORDER BY id DESC LIMIT 1"
        ).fetchone()
        denials = conn.execute(
            "SELECT user_name, method, timestamp FROM access_log "
            "WHERE success=0 ORDER BY id DESC LIMIT 6"
        ).fetchall()
    top = db.get_keypad_stats(30) if hasattr(db, "get_keypad_stats") else []

    def d(r):
        try:
            return dict(r)
        except Exception:
            return r

    return jsonify({
        "gate": {
            "auto_open": auto_open,
            "auto_name": auto_name,
            "hold": hold,
            "lockdown": lockdown,
            "gpio": GPIO_AVAILABLE,
        },
        "temp": get_cpu_temp(),
        "today": {"total": t_total, "granted": t_grant, "denied": t_deny},
        "users": {"total": u_total, "enabled": u_en, "disabled": u_total - u_en},
        "last": d(last) if last else None,
        "recent_denials": [d(x) for x in denials],
        "top_users": [d(x) for x in top],
        "requester": session.get("requester"),
        "time": datetime.now().strftime("%H:%M:%S"),
    })


@app.route("/export_pdf")
@login_required
def export_pdf():
    """Printable report (open then Ctrl-P -> Save as PDF). PINs are masked."""
    users = db.get_all_users()
    logs = db.get_logs(60)
    with db.get_db() as conn:
        sr = conn.execute(
            "SELECT COUNT(*) t, SUM(success=1) g, SUM(success=0) d FROM access_log"
        ).fetchone()
    total, grant, deny = (sr["t"] or 0), (sr["g"] or 0), (sr["d"] or 0)

    def esc(v):
        return html.escape("" if v is None else str(v))

    def mask_pin(p):
        s = "" if p is None else str(p)
        if len(s) <= 2:
            return "****"
        return "*" * (len(s) - 2) + s[-2:]

    rows_u = "".join(
        f"<tr><td>{esc(u['name'])}</td><td>{esc(mask_pin(u['pin']))}</td>"
        f"<td>{'Enabled' if u['enabled'] else 'DISABLED'}</td>"
        f"<td>{esc(u['schedule_type'])}</td></tr>"
        for u in users
    )
    rows_l = "".join(
        f"<tr><td>{esc(l['timestamp'])}</td><td>{esc(l['user_name'])}</td>"
        f"<td>{esc(l['method'])}</td>"
        f"<td>{'GRANTED' if l['success'] else 'DENIED'}</td></tr>"
        for l in logs
    )
    return f"""<!doctype html><html><head><meta charset=utf-8>
<title>Boom Gate Report</title><style>
body{{font-family:Arial,sans-serif;margin:24px;color:#16203a}}
h1{{color:#FF6B00}} h2{{margin-top:1.4rem;border-bottom:2px solid #FF6B00;padding-bottom:4px}}
table{{width:100%;border-collapse:collapse;font-size:12px;margin-top:8px}}
th,td{{border:1px solid #ccc;padding:4px 8px;text-align:left}} th{{background:#f3f3f3}}
.k{{display:inline-block;margin-right:24px;font-size:14px}}
@media print{{.noprint{{display:none}}}}</style></head><body>
<h1>Mildura Working Man's Club - Boom Gate Report</h1>
<p>Generated {esc(datetime.now().strftime('%a %d %b %Y %H:%M'))}</p>
<p><span class=k><b>Users:</b> {len(users)}</span>
<span class=k><b>Total accesses:</b> {total}</span>
<span class=k><b>Granted:</b> {grant}</span>
<span class=k><b>Denied:</b> {deny}</span></p>
<button class=noprint onclick="window.print()">Print / Save as PDF</button>
<h2>Users</h2><table><tr><th>Name</th><th>PIN (masked)</th><th>Status</th><th>Schedule</th></tr>{rows_u}</table>
<h2>Recent Access Log (last 60)</h2><table><tr><th>Time</th><th>User</th><th>Method</th><th>Result</th></tr>{rows_l}</table>
</body></html>"""


@app.route("/keypad")
def keypad():
    return render_template("keypad.html")


# ── STARTUP ───────────────────────────────────────────────────────────────────
def reconcile_hold_on_startup() -> None:
    global _was_auto_open, _schedule_hold, _manual_hold
    try:
        with _gate_lock:
            if db.is_lockdown():
                with _hold_lock:
                    _manual_hold = False
                    _schedule_hold = False
                _was_auto_open = False
                cut_all_signals("startup-lockdown", quiet=False)
                print("[HOLD] startup: lockdown active — hold OFF", flush=True)
                return
            now_open, sched_name = db.is_auto_open()
            with _hold_lock:
                _schedule_hold = bool(now_open)
            _was_auto_open = bool(now_open)
            apply_hold_desire("startup:" + (str(sched_name) if now_open else "idle"))
            print(
                f"[HOLD] startup: schedule={'ON:' + str(sched_name) if now_open else 'OFF'}",
                flush=True,
            )
    except Exception as e:
        print(f"[HOLD] startup reconcile error: {e}", flush=True)


def _start_background_services() -> None:
    if getattr(_start_background_services, "_started", False):
        return
    _start_background_services._started = True

    def _log(msg):
        print(msg, flush=True)
        try:
            with open("/tmp/boomgate-startup.log", "a") as f:
                f.write(msg + "\n")
        except Exception:
            pass

    try:
        db.init_db()
        _log("[DB] init_db OK")
    except Exception as e:
        _log(f"[DB] init_db error: {e}")
    try:
        threading.Thread(
            target=scheduler_thread, daemon=True, name="auto-scheduler"
        ).start()
        _log("[SCHEDULER] Background scheduler started (30s interval)")
    except Exception as e:
        _log(f"[SCHEDULER] failed: {e}")
    try:
        from wiegand import WiegandReader

        reader = WiegandReader(d0_pin=17, d1_pin=27, callback=on_wiegand)
        reader.start()
        _log("[WIEGAND] Reader started")
    except Exception as e:
        _log(f"[WIEGAND] Not started: {e}")


_start_background_services()
reconcile_hold_on_startup()

if __name__ == "__main__":
    print("[APP] Starting Flask on 0.0.0.0:5000")
    # Prefer a single process so GPIO/Wiegand are not duplicated.
    app.run(host="0.0.0.0", port=5000, debug=False, threaded=True)
