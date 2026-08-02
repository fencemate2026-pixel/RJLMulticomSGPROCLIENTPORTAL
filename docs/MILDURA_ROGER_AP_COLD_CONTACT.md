# Mildura — Roger AP “first pulse after idle” theory

**Status:** Working hypothesis. **Do not deploy software rewrites for this.**  
**Signature (confirmed):** guaranteed on the **first PIN / AP pulse after being idle since ~01:30 AEST** (club ORO hold ends ~01:40 — see `mildura-boomgate-hardened/patches/README_CLUB_HOLD_HOURS.md`).

## Theory (strengthened by “guaranteed, every time”)

Low-current dry-contact on the Roger **AP** input sits in one state for hours after the nightly close (ORO hold released; boom free-closes; AP pulse relay unused). A thin oxidation / resistance film builds on the contact. The **first** switch after that idle can be sluggish, bounce, or briefly fail to make/break cleanly — enough for the Roger’s AP-detection logic to treat the pulse as **persistent** (boom stays up / looks stuck open). Exercising the contact once often wipes the film, so later pulses look clean.

This is a known dry-contact / long-idle pattern — not a random software race.

## One question that splits the theory

When staff **manually close** the boom and **re-enter the PIN**:

| Retry behaviour | Interpretation |
|-----------------|----------------|
| Usually works on the **second** try | Clean match for “cold contact, self-clears after one exercise” |
| Sticks again, repeatedly | Contact more degraded, **or** Roger state confusion (not cleared by one cycle) |

Answer that before changing hardware or firmware.

## Decisive live test (will almost certainly catch it)

Because the failure is **guaranteed**, luck is not required — watch the right moment once.

1. SSH to the Pi **before** the ORO close (~01:20 AEST).
2. Tail boomgate journal + status.
3. Confirm schedule hold releases (`GPIO23` / hold → off; log shows auto-close).
4. Wait for the **very first** PIN / Wiegand / web pulse after that close.
5. Correlate:
   - Pi log: `SHORT PULSE` / `SHORT PULSE CUT` / open line low
   - Physical boom: stuck open vs free-close
6. **Best evidence (once):** multimeter on the Roger **AP** terminal a few seconds after the Pi log says the pulse line went low.  
   - Pi says low, AP still “seen” high/active → contact / Roger side  
   - Pi still high → software / GPIO stuck (different bug)

### Helper script

```bash
# Requires .local/mildura.env (gitignored)
python scripts/watch_mildura_first_pulse_after_close.py
# optional: --after-aest 01:40  --pre-roll-min 20
```

Read-only. No code deploy. No relay forcing unless you pass an explicit unsafe flag (default: none).

## What this is *not*

- Not a reason to replace `app.py` with `mildura-boomgate-hardened/`
- Not fixed by another full audit rewrite
- Software pulse `finally` force-off is still good hygiene, but it does **not** explain a guaranteed first-after-01:30 signature if the Pi already logs a clean CUT

## Likely follow-ups (only after the live catch)

- Confirm retry behaviour (table above)
- If cold-contact confirmed: contact cleaning / better relay for AP / sealed relay / parallel wipe pulse design **after** measuring AP
- If Pi GPIO still high when boom sticks: then revisit surgical pulse/hold patches
