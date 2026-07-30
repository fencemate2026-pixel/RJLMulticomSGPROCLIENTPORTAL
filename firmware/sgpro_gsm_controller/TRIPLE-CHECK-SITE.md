# Triple-check report — Thomastown ESP (vs Mildura lessons)

**Date:** 2026-07-30  
**Scope:** Site-critical files for Multicom GSM gate (ESP32-S3 + SIM7600 + Roger dual relay)  
**Honesty rule:** Only claim what was verified in source/hashes. Live gate not proven until on-site tests pass.

## Files checked

| File | Role | Hash / note |
|------|------|-------------|
| `firmware/sgpro_gsm_controller/sgpro_gsm_controller.ino` | **Source of truth** | Flash this only |
| Desktop `ESP32-FLASH-THOMASTOWN/sgpro_gsm_controller.ino` | Must match source | SHA256 matched at last sync |
| Desktop `sgpro_gsm_controller-DOWNLOAD/*` | Zip kit | Must match source |
| `FLASH-NOW.txt` | Site checklist | Wiring + serial |
| `prebuilt-esp32s3/*` | **STALE — DO NOT USE** | Marked DO-NOT-USE |
| `*.bak` / old backups | Historical only | Do not flash |
| `build/` | Local Arduino build cache | Not for site deploy |

## Mildura-class failure modes mapped

| Mildura lesson | ESP equivalent | Status |
|----------------|----------------|--------|
| Pulse ON then cut skipped → stuck open | PP pulse must always go OFF after 3s | `updateRelay()` re-asserts OFF every tick |
| Hold/open line left high | AP HOLD must not stick at night | Night path **forces HOLD off every tick** |
| Day schedule looked like “stuck” | Day HOLD ON 06:00–18:00 is **by design** | Documented; `STATUS hold_ap=` |
| Lost proof / silent fail | Call events dropped offline | Fixed: retry until upload OK |
| Deployed wrong/old code | Stale prebuilt bins | **DO NOT USE** prebuilt |
| Secrets in repo | APN password was hardcoded | Removed; NVS `APN` / `PROVISION` only |

## Product behaviour (correct)

- **Day 06:00–18:00 Melbourne:** HOLD IO4 ON → Roger AP (gate held open)
- **Night:** HOLD off; whitelist call → CHUP → 3s PULSE IO5 → Roger PP
- **Day call:** hang-up only (no PP pulse)
- **Modem cable missing:** AT timeouts expected; Wi‑Fi/schedule/relays still work

## On-site pass criteria (do not leave without)

1. Serial: `MODEM: clean OK` or `MODEM_RETRY` success (`STATUS modem=1`)
2. `STATUS wifi_up=1 clock_ok=1` after WIFI + NTP
3. `SYNC` / whitelist `n=` expected
4. Day: HOLD closed on AP (or `DAY` test)
5. Night or `NIGHT` + `PULSE`: PP 3s then **open** (not stuck)
6. One real whitelist dial at night if possible

## Residual risks (not proven fixed without hardware)

- Wrong TXD/RXD / no GND → modem silent (install)
- No NTP → day hold stays off (night-safe, intentional)
- Roger/motor wiring vs dry-contact wrong → hardware damage risk (wire command side only)
- Live Multicom secret mismatch → whitelist empty
- This audit did **not** run on the physical Thomastown gate

## Flash command of record

Arduino IDE → ESP32S3 Dev Module → upload  
`firmware/sgpro_gsm_controller/sgpro_gsm_controller.ino`  
Serial 115200 NL → `HELP` `STATUS` `MODEM_RETRY`
