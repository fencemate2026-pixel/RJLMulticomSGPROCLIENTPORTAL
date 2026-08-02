# Mildura Working Man's Club — Boom Gate Controller

> **DO NOT DEPLOY** this tree to the live Pi. See [`DO_NOT_DEPLOY.md`](DO_NOT_DEPLOY.md).
> Next real move: live read-only SSH audit, then surgical patches only.

Raspberry Pi 5 powered boom/boom gate access control system.

- Physical **Sebury sTouch 2** Wiegand keypad (GPIO 17/27)
- Web dashboard for staff + schedule management
- Public web keypad page (`/keypad`) for phones/tablets
- Time-based per-user access + "auto-open" periods (gate stays open)
- Hardware relay trigger on GPIO 22 (1.5s pulse)
- Access logging
- Runs as a systemd service + optional Tailscale for remote access from anywhere

## Project Layout

```
mildura-boomgate/
├── app.py                 # Main Flask app + hardware logic
├── database.py            # SQLite helpers (users, schedules, logs)
├── wiegand.py             # Sebury Wiegand 26-bit reader (daemon thread)
├── import_staff.py        # One-time CSV → random PIN import (75 staff)
├── staff_list.csv         # Source list of staff by category
├── requirements.txt
├── install.sh             # One-command setup for the Pi
├── boomgate.env.example   # Template for /etc/boomgate.env
├── healthcheck.sh         # Service + unsafe-hold check
├── portal/                # Optional RJL maintenance heartbeat
├── README.md
└── templates/
    ├── login.html
    ├── keypad.html        # Big-button public entry page
    └── dashboard.html     # Full admin management UI
```

## Quick Start (on the Pi)

```bash
# 1. Copy the entire folder to the Pi (scp, rsync, git, USB, etc.)
#    Example:
scp -r mildura-boomgate rjlcommercial@192.168.1.248:~/

# 2. SSH in and run the installer
ssh rjlcommercial@192.168.1.248
cd ~/mildura-boomgate
chmod +x install.sh
./install.sh
```

The installer:
- Creates a venv
- Installs Python deps + lgpio
- Creates `boom_gate.db`
- Registers and starts the `boomgate` systemd service
- Prints the LAN IP and next steps

## Import Staff + PINs (75 people)

```bash
cd ~/mildura-boomgate
source venv/bin/activate
python import_staff.py --dry     # preview assignments
python import_staff.py           # real import — prints every name + PIN
```

**Save the printed PIN list.** Distribute the 4-digit codes to staff.  
They can use them on the physical Sebury keypad **or** the web keypad page.

## Access the System

- **Admin dashboard** (manage users/schedules): `http://<pi-ip>:5000`  
  Admin PIN comes from `/etc/boomgate.env` (`ADMIN_PIN`) — never a code default.
- **Staff keypad** (big buttons, phone-friendly): `http://<pi-ip>:5000/keypad`

## GPIO Wiring (Sebury sTouch 2 + Relay)

| Wire / Device       | Connection                  | Notes |
|---------------------|-----------------------------|-------|
| Sebury Green (D0)   | BCM 17 (pin 11)             | Pull-up enabled in code |
| Sebury White (D1)   | BCM 27 (pin 13)             | Pull-up enabled in code |
| Sebury Black (GND)  | Any GND on Pi               | Common ground |
| Sebury Red (+12V)   | **Separate 12V PSU**        | **Critical** — do NOT use Pi 5V rail |
| Relay control       | BCM 22 (pin 15)             | Active-high, 1.5s pulse to open gate |
| Relay power / contacts | As per gate controller   | Usually dry contact to gate open input |

The Sebury sTouch 2 requires 12 V. Use a proper 12 V supply for the reader.

## Schedules

Two kinds of schedules exist:

1. **Per-user schedules** (set on each staff record)
   - `always` — 24/7 access
   - `time_range` — specific days + start/end times (Mon=0 … Sun=6)

2. **Auto-open schedules** (global, no PIN required)
   - When any enabled auto-open window is active the gate stays open.
   - Useful for events, busy Friday nights, deliveries, etc.
   - Keypad activity while auto-open is active simply re-triggers the relay.

## Changing the Admin PIN

Edit `/etc/boomgate.env`:

```bash
sudo nano /etc/boomgate.env   # ADMIN_PIN=......
sudo systemctl restart boomgate
```

## Useful Commands (on Pi)

```bash
sudo systemctl status boomgate
sudo systemctl restart boomgate
sudo journalctl -u boomgate -f     # live logs (very useful while testing keypad)

# Check Tailscale IP
tailscale ip -4
```

## Development / Testing Without Hardware

- The app starts even if GPIO is unavailable (lgpio / gpiozero missing or not on Pi).
- It runs in "simulation mode" — gate triggers are just `time.sleep(0.5)` + logs.
- You can still use the web keypad and dashboard fully.

## Production Tips

- Run behind Tailscale or a simple reverse proxy (nginx + basic auth or mTLS) if exposing beyond Tailscale.
- Consider adding a small UPS or at least good power filtering at the gate.
- The physical Sebury reader and the web `/keypad` both call the same `has_access()` logic.
- Logs are in the SQLite `access_log` table (viewable on the dashboard).

## Credits

Built from the original Claude conversation for RJL Commercial Fencing & Gates (Mildura Working Man's Club boom gate project).

Wiring, schedules, and staff categories are taken directly from the provided spec and CSV.

## License

Internal / project use.
