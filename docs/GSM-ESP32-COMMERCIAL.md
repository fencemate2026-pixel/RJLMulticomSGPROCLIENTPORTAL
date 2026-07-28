# RJL Multicom SG-PRO — Confirmed production flow

> **Operational date: 29 July 2026.**  
> This function will not be operational until then. Bench testing may continue; live gate use starts from that date.

This is the **final** behaviour for:

- `firmware/sgpro_gsm_controller/sgpro_gsm_controller.ino`
- `functions/` (`gsmDeviceApi`)
- Portal **Authorised callers**

---

## Architecture

```
Portal add / delete
        ↓
Firebase: gsmCallers + whitelistVersion++
        (+ gsmSmsQueue job on add)
        ↓
ESP32 polls ~ every 60 seconds
        ↓
Validate temp list → atomic activate → NVS save
        ↓
Gate calls use local whitelist immediately
```

**Incoming calls never wait for Firebase.**

```
Incoming call
    ↓
Read +CLIP
    ↓
Normalise to E.164
    ↓
Check locally cached whitelist
    ↓
AT+CHUP  (always before relay)
    ↓
Authorised → one 3s relay pulse
Unauthorised / hidden / expired / disabled → no relay
```

---

## Welcome SMS queue

Collection: **`gsmSmsQueue/{jobId}`**

| Field | Type |
|--------|------|
| accountId | string |
| deviceId | string |
| callerId | string |
| phoneNumberE164 | string |
| message | string |
| status | `PENDING` \| `SENDING` \| `SENT` \| `FAILED` |
| attemptCount | number |
| createdAt | timestamp |
| sentAt | timestamp \| null |
| lastError | string \| null |
| cmgsReference | string \| null |

### Message template (production)

```
Hi {name},
Welcome to the RJL Multicom GSM opener.
Your mobile number has been listed and you now have access to the SLIDING GATE at 337 Settlement Road, Thomastown.
Instructions:
On your mobile, call +61 414 371 302 once. The call will hang up automatically and the gate will begin to open. You do not need to do anything else — the gate will close by itself.
Please ensure your number is not set to private, otherwise access will be denied.
Smart Home Integration:
You can integrate Amazon Alexa or Google Home to open the gate hands-free by voice. Save the gate number as a contact (e.g. “Gate” or “Sliding Gate”), then say “Alexa, call Gate” or “Hey Google, call Gate”.
How to set it up:
• Alexa: https://www.amazon.com.au/b?ie=UTF8&node=5425662051
• Google Home / Nest: https://support.google.com/googlenest/answer/9849261
If you have any issues, contact RJL Commercial on 0400 101 132.
```

### Flow

1. Portal **Add** → create caller → `whitelistVersion++` → create SMS job(s) `PENDING`  
2. ESP32 whitelist GET returns `smsJobs[]`  
3. ESP32 optional **sms-claim** → status `SENDING`  
4. Device sends via SIM7600 `AT+CMGS`  
5. Only after **`+CMGS: <ref>`** and **`OK`** → **sms-ack** with `jobId`  
6. Backend marks job **`SENT`** (or **`FAILED`** / re-queue `PENDING` with attemptCount++)

This prevents duplicate SMS after restarts or failed acks.

**SMS must never block the relay path.** SMS waits until internet returns.

---

## ESP32 local whitelist rules

| Rule | Behaviour |
|------|-----------|
| Power loss | Last **valid** list restored from NVS |
| Bad / failed download | **Do not** erase working list |
| Good download | Write temp → validate → **atomic** activate → persist |
| Hidden / withheld / unknown | Always reject |
| Disabled / expired / not-started | Not in cloud list / rejected |
| Delete in portal | Stops after **next successful** sync |
| Wi‑Fi / Firebase down | **Calls still work** from cache |
| One call | **One** relay pulse (lockout) |
| Hang-up | **Always before** relay |

---

## Device credentials

**Do not** put live `DEVICE_SECRET` or **Wi-Fi password** in a public sketch or git.

### Corrected provisioning

1. Flash firmware (`DEVICE_ID`, `API_BASE` only in source).  
2. Serial: `WIFI <ssid> <password>` → NVS (Wi-Fi password is a secret).  
3. Serial: `PROVISION <long-random-secret> [version]` → NVS.  
4. Store **only** in Firestore:

```
secretHash = SHA256(secret + ":" + deviceId)
```

Exactly that order and punctuation — do **not** use `SHA256(secret)`, `SHA256(deviceId + ":" + secret)`, or `SHA256(secret:accountId)`.

```
gsmDeviceCredentials/{deviceId}:
  accountId
  secretHash
  enabled: true
  secretVersion: 1
```

### Every device request headers

| Header | Meaning |
|--------|---------|
| `X-Device-Id` | device id |
| `X-Device-Secret` | raw secret (TLS only) |
| `X-Timestamp` | Unix ms (±5 min) |
| `X-Nonce` | unique per request |
| `X-Secret-Version` | must match stored secretVersion |
| `X-Signature` | HMAC-SHA256(secret, METHOD\\npath\\nts\\nnonce\\nbody) |

Backend rejects: expired timestamps, reused nonces, bad signatures, disabled devices, wrong secretVersion, wrong account.

---

## API endpoints

Base:  
`https://australia-southeast1-rjl-maintenance-app.cloudfunctions.net/gsmDeviceApi`

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/gsm/device/{id}/whitelist` | Callers + `smsJobs` + version |
| POST | `/gsm/device/{id}/sms-claim` | Mark jobs `SENDING` |
| POST | `/gsm/device/{id}/sms-ack` | Job-level SENT/FAILED after `+CMGS` |
| POST | `/gsm/device/{id}/events` | Call outcome log |
| POST | `/gsm/device/{id}/heartbeat` | Online + version hint |

---

## Portal operator model

| Action | Result |
|--------|--------|
| **Add person** | Save caller → version++ → queue SMS → ESP32 sync + SMS |
| **Delete / disable** | version++ → ESP32 drops on next sync |
| **Welcome SMS** | New queue job → device sends when online |
| **Test open** | Your phone calls gate SIM (optional) |

Commercial end clients **do not need the app** — they only call the gate SIM.

**Site:** 337 SETTLEMENT ROAD - THOMASTOWN  
**Gate:** SLIDING GATE  

Gate SIM: **0414 371 302** → `+61414371302`  
Admin handset: **0400 101 132** → `+61400101132`

---

## Deploy

```bash
cd functions && npm install && npm run build
firebase deploy --only functions:gsmDeviceApi
```

## Serial commands (device)

| Command | Effect |
|---------|--------|
| `PROVISION <secret>` | Save secret to NVS |
| `SYNC` | Pull whitelist now |
| `STATUS` | Secret / version / count / Wi‑Fi |

---

## Wiring

| ESP32 | DTU / relay |
|--------|-------------|
| GPIO17 TX | DTU RXD |
| GPIO18 RX | DTU TXD |
| GND | DTU GND |
| GPIO5 | Relay IN |
| GND | Relay GND |

Power DTU from 12 V / 7–36 V — **not** from ESP32.
