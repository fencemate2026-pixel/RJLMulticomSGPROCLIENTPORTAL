"""Dashboard API routes — system metrics, keypad stats, site + network info."""
import os, subprocess, socket, glob
from flask import jsonify


def _read_int(path):
    try:
        with open(path) as f:
            return int(f.read().strip())
    except Exception:
        return None


def get_cpu_temp():
    """Robust CPU temperature (°C). Tries the thermal zone, then any hwmon
    sensor, then vcgencmd. Returns None only if all fail."""
    v = _read_int("/sys/class/thermal/thermal_zone0/temp")
    if v is not None:
        return round(v / 1000.0, 1)
    for p in glob.glob("/sys/class/hwmon/hwmon*/temp*_input"):
        v = _read_int(p)
        if v is not None:
            return round(v / 1000.0, 1)
    try:
        out = subprocess.run(["vcgencmd", "measure_temp"],
                             capture_output=True, text=True, timeout=3).stdout
        # temp=45.6'C
        return round(float(out.split("=")[1].split("'")[0]), 1)
    except Exception:
        return None


def _run(cmd, timeout=3):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout).stdout.strip()
    except Exception:
        return ""


def _local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "—"


def _pi_model():
    try:
        with open("/proc/device-tree/model") as f:
            return f.read().strip("\x00").strip()
    except Exception:
        return "—"


def _throttled():
    """Decode vcgencmd get_throttled into plain English."""
    out = _run(["vcgencmd", "get_throttled"])
    if not out or "=" not in out:
        return {"raw": None, "ok": True, "flags": []}
    try:
        val = int(out.split("=")[1], 16)
    except Exception:
        return {"raw": out, "ok": True, "flags": []}
    bits = {
        0: "under-voltage NOW", 1: "ARM freq capped NOW", 2: "throttled NOW",
        3: "soft temp limit NOW",
        16: "under-voltage occurred", 17: "ARM freq cap occurred",
        18: "throttling occurred", 19: "soft temp limit occurred",
    }
    flags = [txt for bit, txt in bits.items() if val & (1 << bit)]
    return {"raw": hex(val), "ok": (val == 0), "flags": flags}


def get_cpu_temp_safe():
    return get_cpu_temp()


def register_dashboard_api(app, db, login_required):
    @app.route("/api/site")
    @login_required
    def api_site():
        return jsonify({
            "lat": -34.1875455,
            "lng": 142.1603563,
            "address": "100-110 Deakin Ave, Mildura VIC 3500",
            "name": "Mildura Working Man's Club",
        })

    @app.route("/api/access_breakdown")
    @login_required
    def api_breakdown():
        with db.get_db() as conn:
            g = conn.execute(
                "SELECT COUNT(*) c FROM access_log "
                "WHERE success=1 AND method IN ('wiegand','web')"
            ).fetchone()["c"]
            d = conn.execute(
                "SELECT COUNT(*) c FROM access_log WHERE success=0"
            ).fetchone()["c"]
            a = conn.execute(
                "SELECT COUNT(*) c FROM access_log WHERE method LIKE '%auto%'"
            ).fetchone()["c"]
        return jsonify({"granted": g, "denied": d, "auto": a})

    @app.route("/api/temperature")
    @login_required
    def api_temperature():
        temp = get_cpu_temp()
        return jsonify({
            "temperature": temp,
            "unit": "°C",
            "source": "cpu" if temp is not None else "unavailable",
        })

    @app.route("/api/sysmetrics")
    @login_required
    def api_sysmetrics():
        import time as _t
        import shutil as _sh

        def _cpu():
            with open("/proc/stat") as f:
                v = list(map(int, f.readline().split()[1:]))
            idle = v[3] + v[4]
            return idle, sum(v)

        i1, t1 = _cpu()
        _t.sleep(0.12)
        i2, t2 = _cpu()
        dt = t2 - t1
        cpu = round((1 - (i2 - i1) / dt) * 100, 1) if dt > 0 else 0.0

        mem = {}
        with open("/proc/meminfo") as f:
            for ln in f:
                k, val = ln.split(":", 1)
                mem[k] = int(val.strip().split()[0])
        mtot = mem.get("MemTotal", 1) or 1
        mavail = mem.get("MemAvailable", 0)
        mem_pct = round((1 - mavail / mtot) * 100, 1)

        du = _sh.disk_usage("/")
        disk_pct = round(du.used / du.total * 100, 1)

        with open("/proc/uptime") as f:
            up = float(f.readline().split()[0])
        days = int(up // 86400)
        hrs = int((up % 86400) // 3600)
        mins = int((up % 3600) // 60)
        if days > 0:
            up_str = "%dd %dh" % (days, hrs)
        elif hrs > 0:
            up_str = "%dh %dm" % (hrs, mins)
        else:
            up_str = "%dm" % mins

        return jsonify({
            "cpu": cpu,
            "mem": mem_pct,
            "disk": disk_pct,
            "uptime": up_str,
            "uptime_days": days,
            "temp": get_cpu_temp(),
        })

    @app.route("/api/keypad_stats")
    @login_required
    def api_keypad_stats():
        rows = db.get_keypad_stats(30) if hasattr(db, "get_keypad_stats") else []
        out = []
        for r in rows:
            try:
                out.append(dict(r))
            except Exception:
                out.append(r)
        return jsonify(out)

    @app.route("/api/netinfo")
    @login_required
    def api_netinfo():
        # DB size (main + WAL + SHM)
        db_bytes = 0
        for ext in ("", "-wal", "-shm"):
            try:
                db_bytes += os.path.getsize(getattr(db, "DB_NAME", "boom_gate.db") + ext)
            except Exception:
                pass
        tailscale_ip = _run(["tailscale", "ip", "-4"]).splitlines()
        service = _run(["systemctl", "is-active", "boomgate"]) or "unknown"
        return jsonify({
            "hostname": socket.gethostname(),
            "local_ip": _local_ip(),
            "tailscale_ip": (tailscale_ip[0] if tailscale_ip else "—"),
            "model": _pi_model(),
            "throttled": _throttled(),
            "db_size_kb": round(db_bytes / 1024.0, 1),
            "service": service,
        })
