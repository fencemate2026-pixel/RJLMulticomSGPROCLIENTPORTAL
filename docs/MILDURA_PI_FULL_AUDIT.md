# Mildura Pi — Full System Audit (2026-08-02)

> **DO NOT DEPLOY** the `mildura-boomgate-hardened/` tree to the live Pi.
> It is a reference rewrite from offline snapshots only. The live Pi already
> has a more advanced `app.py` than those snapshots (see
> `scripts/patch_mildura_pulse_force_off.py`, which expects live helpers like
> `hold_should_be_on` / `_relay_lock`). Blind deploy risks regressing working
> gate behaviour.
>
> **Next real move:** get Tailscale SSH credentials into `.local/mildura.env`,
> run a **read-only** live audit (`scripts/full_mildura_pi_audit.py`), pull a
> copy of the live `app.py` / `database.py` / unit file, diff against this
> report, then apply **surgical** patches only (never a full tree replace).

Scope: Raspberry Pi boom-gate stack for Mildura Working Man's Club
(`mildura-boomgate` service, Wiegand keypad, ORO hold relay, Flask dashboard,
portal heartbeat, Airtable sync, healthcheck).

Live SSH from the cloud agent was **not available** (Tailscale IP
`100.103.206.69` unreachable; `.local/mildura.env` missing). This audit used
every on-disk snapshot: `_live/`, `boomgate-live.tgz`, `boomgate-fixed.tgz`,
`boomgate-dashboard.tgz`, and the management scripts in
`RJLMulticomSGPROCLIENTPORTAL`.

Reference fixes (not for blind deploy) live under `mildura-boomgate-hardened/`.

---

## Executive summary

| Severity | Count | Status |
|---|---|---|
| CRITICAL | 4 | Documented; verify on live before patching |
| HIGH | 9 | Documented; verify on live before patching |
| MEDIUM | 8 | Documented |
| LOW | 5 | Documented |
| OPS | 1 | **Next:** live read-only SSH audit (do not deploy rewrite) |

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

## OPS — next real move (ordered)

1. **Do not deploy** `mildura-boomgate-hardened/` (or any full-tree replace).
2. **Provide live SSH access** for agents / laptop on Tailscale:

```
# .local/mildura.env  (gitignored)
MILDURA_SSH_HOST=100.103.206.69
MILDURA_SSH_USER=rjlcommercial
MILDURA_SSH_PASSWORD=...
```

3. **Read-only live audit** (no code write):

```bash
python scripts/full_mildura_pi_audit.py --out /tmp/pi-live-audit.md
```

4. **Pull live sources** and diff against this report / the reference tree:
   - `/home/rjlcommercial/boomgate/app.py`
   - `/home/rjlcommercial/boomgate/database.py`
   - `/home/rjlcommercial/boomgate/wiegand.py`
   - systemd unit + `/etc/boomgate.env` presence (not secrets in git)
5. **Only then** apply surgical patches (prefer existing tools like
   `scripts/patch_mildura_pulse_force_off.py` / tiny targeted edits).
6. Separately: **rotate** any portal `DEVICE_SECRET` that appeared in old
   public tarballs — that is credential hygiene, not a gate deploy.

---

## Verification performed (this agent)

- Static audit of all snapshots + management scripts
- `py_compile` + simulated GPIO smoke on the *reference* rewrite
- Live Tailscale SSH: **blocked from this environment**
- **No live deploy attempted**

---

## Files in this PR (reference only)

- `mildura-boomgate-hardened/` — offline reference rewrite (**not for deploy**)
- `docs/MILDURA_PI_FULL_AUDIT.md` — this file
- `scripts/full_mildura_pi_audit.py` — live read-only auditor
