#!/usr/bin/env python3
"""
RJL Maintenance portal heartbeat.

Pushes Pi health into the RJL Maintenance app (Supabase) every
PORTAL_INTERVAL_S via the device-authenticated gate_heartbeat RPC.

Standalone systemd service. It reads /proc directly rather than the
local Flask app, so it never contends for the serial port and never
needs an app login. A portal failure must NEVER affect gate operation:
every cycle is wrapped, and the loop never exits on error.

Config: /etc/rjl-portal.env
    GATE_ID, DEVICE_KEY, DEVICE_SECRET, SUPABASE_URL, SUPABASE_ANON_KEY
    SERVICE_NAME   (systemd unit to report health for)
"""
import json
import os
import ssl
import subprocess
import time
import urllib.error
import urllib.request

ENV_PATH = os.environ.get("PORTAL_ENV", "/etc/rjl-portal.env")
INTERVAL_S = int(os.environ.get("PORTAL_INTERVAL_S", "300"))


def load_env(path):
    cfg = {}
    try:
        with open(path) as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                cfg[k.strip()] = v.strip().strip('"').strip("'")
    except FileNotFoundError:
        pass
    for k in ("GATE_ID", "DEVICE_KEY", "DEVICE_SECRET",
              "SUPABASE_URL", "SUPABASE_ANON_KEY", "SERVICE_NAME"):
        if os.environ.get(k):
            cfg[k] = os.environ[k]
    return cfg


def cpu_temp():
    try:
        with open("/sys/class/thermal/thermal_zone0/temp") as fh:
            return round(int(fh.read().strip()) / 1000.0, 1)
    except Exception:
        pass
    try:
        import glob
        for p in glob.glob("/sys/class/hwmon/hwmon*/temp1_input"):
            with open(p) as fh:
                return round(int(fh.read().strip()) / 1000.0, 1)
    except Exception:
        pass
    return None


def uptime_s():
    try:
        with open("/proc/uptime") as fh:
            return int(float(fh.readline().split()[0]))
    except Exception:
        return None


def mem_pct():
    try:
        info = {}
        with open("/proc/meminfo") as fh:
            for line in fh:
                k, v = line.split(":", 1)
                info[k] = int(v.strip().split()[0])
        total = info.get("MemTotal", 0)
        if not total:
            return None
        return round((1 - info.get("MemAvailable", 0) / total) * 100, 1)
    except Exception:
        return None


def disk_pct():
    try:
        st = os.statvfs("/")
        total = st.f_blocks * st.f_frsize
        free = st.f_bavail * st.f_frsize
        if not total:
            return None
        return round((1 - free / total) * 100, 1)
    except Exception:
        return None


def throttled():
    try:
        out = subprocess.run(["vcgencmd", "get_throttled"],
                             capture_output=True, text=True, timeout=5).stdout
        val = out.strip().split("=")[-1]
        return int(val, 16) != 0
    except Exception:
        return None


def service_ok(name):
    if not name:
        return None
    try:
        out = subprocess.run(["systemctl", "is-active", name],
                             capture_output=True, text=True, timeout=5).stdout
        return out.strip() == "active"
    except Exception:
        return None


def send(cfg):
    url = cfg["SUPABASE_URL"].rstrip("/") + "/rest/v1/rpc/gate_heartbeat"
    payload = {
        "p_gate_id":    cfg["GATE_ID"],
        "p_device_key": cfg["DEVICE_KEY"],
        "p_secret":     cfg["DEVICE_SECRET"],
        "p_cpu_temp_c": cpu_temp(),
        "p_service_ok": service_ok(cfg.get("SERVICE_NAME")),
        "p_throttled":  throttled(),
        "p_uptime_s":   uptime_s(),
        "p_disk_pct":   disk_pct(),
        "p_mem_pct":    mem_pct(),
        "p_extra":      {"agent": "portal_sync/1.0"},
    }
    req = urllib.request.Request(
        url, method="POST",
        data=json.dumps(payload).encode(),
        headers={
            "apikey": cfg["SUPABASE_ANON_KEY"],
            "Authorization": "Bearer " + cfg["SUPABASE_ANON_KEY"],
            "Content-Type": "application/json",
        })
    ctx = ssl.create_default_context()
    with urllib.request.urlopen(req, timeout=15, context=ctx) as resp:
        return resp.status


def main():
    cfg = load_env(ENV_PATH)
    missing = [k for k in ("GATE_ID", "DEVICE_KEY", "DEVICE_SECRET",
                           "SUPABASE_URL", "SUPABASE_ANON_KEY")
               if not cfg.get(k)]
    if missing:
        print("[portal] missing config: %s (%s) - idling" %
              (", ".join(missing), ENV_PATH), flush=True)

    while True:
        try:
            cfg = load_env(ENV_PATH)
            if all(cfg.get(k) for k in ("GATE_ID", "DEVICE_KEY", "DEVICE_SECRET",
                                        "SUPABASE_URL", "SUPABASE_ANON_KEY")):
                code = send(cfg)
                print("[portal] heartbeat OK (%s) gate=%s" %
                      (code, cfg["GATE_ID"]), flush=True)
        except urllib.error.HTTPError as e:
            body = ""
            try:
                body = e.read().decode()[:300]
            except Exception:
                pass
            print("[portal] HTTP %s: %s" % (e.code, body), flush=True)
        except Exception as e:
            print("[portal] error: %r" % (e,), flush=True)
        time.sleep(INTERVAL_S)


if __name__ == "__main__":
    main()
