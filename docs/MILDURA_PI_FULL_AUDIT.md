# Mildura Pi — Full System Audit (2026-08-02)

Scope: Raspberry Pi boom-gate stack for Mildura Working Man's Club
(`mildura-boomgate` service, Wiegand keypad, ORO hold relay, Flask dashboard,
portal heartbeat, Airtable sync, healthcheck).

Live SSH from the cloud agent was **not available** (Tailscale IP
`100.103.206.69` unreachable; `.local/mildura.env` missing). This audit used
every on-disk snapshot: `_live/`, `boomgate-live.tgz`, `boomgate-fixed.tgz`,
`boomgate-dashboard.tgz`, and the management scripts in
`RJLMulticomSGPROCLIENTPORTAL`.

Fixes from this audit are in the hardened tree under `mildura-boomgate/`.

---

## Executive summary

| Severity | Count | Status |
|---|---|---|
| CRITICAL | 4 | Fixed in hardened package |
| HIGH | 9 | Fixed / mitigated |
| MEDIUM | 8 | Fixed / mitigated |
| LOW | 5 | Fixed / noted |
| OPS | 2 | Requires on-site action |

---

## CRITICAL

### C1 — Relay pulse can stick ON after exceptions
**Was:** `pulse_gate()` called `relay.off()` inside `try`, not `finally`.
**Impact:** Exception during sleep/GPIO leaves GPIO22 high → boom held open.
**Fix:** Always `_open_off()` + mirror off in `finally`, then re-assert hold
only via `hold_should_be_on()` (post-PIN stuck-open pattern).

### C2 — Dashboard `/gate/open` ignored lockdown
**Was:** Authenticated remote open did not check lockdown.
**Impact:** Admin session could open the gate during lockdown.
**Fix:** Fail-closed lockdown check on `/gate/open` + cut signals on lockdown enable.

### C3 — Device secrets committed in public tarball/repo
**Was:** `portal/rjl-portal.mildura-boom.env` contained live `DEVICE_KEY`,
`DEVICE_SECRET`, and Supabase anon JWT.
**Impact:** Anyone with repo/tarball access can forge portal heartbeats.
**Fix:** Removed real env; shipped `.env.example` only. **Rotate
`DEVICE_SECRET` in Supabase immediately.**

### C4 — Auto-open / reboot hold race (historical)
**Was:** Older live extract ignored lockdown on web keypad + scheduler and
could leave hold stuck after reboot.
**Fix:** Schedule vs manual hold desires + startup reconcile + lockdown cut-all.

---

## HIGH

| ID | Issue | Fix |
|---|---|---|
| H1 | Keypad/web still pulsed during auto-open | Ignore pulses while `hold_should_be_on()` |
| H2 | Lockdown did not cut in-flight pulse | `cut_all_signals` + post-pulse lockdown recheck |
| H3 | Manual hold fought schedule hold | Separate `_manual_hold` / `_schedule_hold` |
| H4 | Multi-worker GPIO duplication risk | Document single-process systemd unit |
| H5 | No Wiegand brute-force limiter | `wiegand_limiter` (12 fails / 180s) |
| H6 | Lockdown helpers optional (`hasattr`) | Require `is_lockdown`/`set_lockdown` at boot |
| H7 | CSRF missing on admin POSTs | Origin/Referer guard |
| H8 | Wiegand D0/D1 polarity non-standard | Standard polarity; `WIEGAND_INVERT_BITS=1` escape hatch |
| H9 | Staff PINs 4-digit `random` | 6-digit `secrets` generator |

---

## MEDIUM

| ID | Issue | Fix |
|---|---|---|
| M1 | Rate limiter not thread-safe | Locked `RateLimiter` class |
| M2 | SQLite no WAL / busy timeout | WAL + busy_timeout in `get_db()` |
| M3 | `get_keypad_stats(days)` string-format SQL | Bound parameter + clamp 1..365 |
| M4 | Schedule form times unvalidated | HH:MM + day validation |
| M5 | PDF export XSS + plaintext PINs | `html.escape` + masked PINs |
| M6 | Dashboard `innerHTML` XSS | `escHtml()` on live fields |
| M7 | Install lacked secrets EnvironmentFile | `/etc/boomgate.env` chmod 600 |
| M8 | Healthcheck ignored unsafe hold | API hold+lockdown check |

---

## LOW

| ID | Issue | Fix |
|---|---|---|
| L1 | Temporary Flask secret | Persist `.flask_secret` / prefer env |
| L2 | Public `/api/status` oversharing | Trimmed public payload |
| L3 | Logout GET | Accepts POST too |
| L4 | Startup `_was_auto_open` desync | Set during reconcile |
| L5 | Airtable access timestamps labeled UTC | Use Melbourne tz |

---

## OPS (on-site required)

1. **Rotate portal `DEVICE_SECRET`** and reinstall `/etc/rjl-portal.env` from the
   new example (never commit the real file).
2. **Deploy hardened package** to the Pi:

```bash
# From a machine on Tailscale:
scp -r mildura-boomgate rjlcommercial@100.103.206.69:~/
ssh rjlcommercial@100.103.206.69
cd ~/mildura-boomgate
# Preserve existing /etc/boomgate.env if present, else install.sh creates one
sudo ./install.sh   # or: sudo systemctl restart boomgate after rsync
curl -sS http://127.0.0.1:5000/api/status
./healthcheck.sh
```

3. If physical keypad PINs suddenly fail after deploy, set
   `WIEGAND_INVERT_BITS=1` in `/etc/boomgate.env` and restart.

4. Provide `.local/mildura.env` to cloud agents for future live audits:

```
MILDURA_SSH_HOST=100.103.206.69
MILDURA_SSH_USER=rjlcommercial
MILDURA_SSH_PASSWORD=...
```

---

## Verification performed (this agent)

- Static audit of all snapshots + management scripts
- `py_compile` on hardened modules
- Simulated GPIO smoke test: pulse force-off, schedule hold, lockdown ignore
- Live Tailscale SSH: **blocked from this environment**

---

## Files changed

- `mildura-boomgate/app.py` — hardened relay/hold/lockdown/auth
- `mildura-boomgate/database.py` — WAL, safe stats query
- `mildura-boomgate/wiegand.py` — polarity + invert flag
- `mildura-boomgate/install.sh` — EnvironmentFile secrets
- `mildura-boomgate/healthcheck.sh` — hold/lockdown safety
- `mildura-boomgate/import_staff.py` — `secrets` 6-digit PINs
- `mildura-boomgate/templates/dashboard.html` — XSS escape
- `mildura-boomgate/portal/*` — secrets removed; example only
- `mildura-boomgate/boomgate.env.example`
- `docs/PI_FULL_AUDIT.md` (this file)
