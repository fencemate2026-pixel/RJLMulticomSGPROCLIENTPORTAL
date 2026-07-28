# Final Go-Live Plan — RJL Multicom SG-PRO

This document is the **production and on-site commissioning checklist** (with corrections applied).

> **This function will not be operational until 29 July 2026.**  
> Bench testing may continue; live sliding-gate use starts on that date.

| | |
|--|--|
| **Site** | 337 SETTLEMENT ROAD - THOMASTOWN · **SLIDING GATE** |
| **Gate SIM (E.164)** | `+61414371302` (display: 0414 371 302 / +61 414 371 302) |
| **RJL mobile (E.164)** | `+61400101132` (display: 0400 101 132) |

Related: [GSM-ESP32-COMMERCIAL.md](./GSM-ESP32-COMMERCIAL.md) · [ESP32_GSM_API.md](./ESP32_GSM_API.md)

---

## Step 1 — Deploy and verify Firebase

```bash
cd functions && npm install && npm run build && cd ..
firebase deploy --only functions:gsmDeviceApi
firebase deploy --only firestore:rules,firestore:indexes
```

Confirm:

1. Function name **`gsmDeviceApi`**
2. URL matches firmware `API_BASE`  
   `https://australia-southeast1-rjl-maintenance-app.cloudfunctions.net/gsmDeviceApi`
3. All requested Firestore indexes exist (including `gsmSmsQueue`)

### Live account — every phone number in E.164

```
clientAccounts/{accountId}
  siteName: "337 SETTLEMENT ROAD - THOMASTOWN · SLIDING GATE"
  gsmNumber: "+61414371302"
  whitelistVersion: <integer>
  enabled: true
```

**Production storage should always use E.164.** National formatting is display-only.

| Display | Storage (E.164) |
|---------|-----------------|
| 0414 371 302 | `+61414371302` |
| 0400 101 132 | `+61400101132` |

---

## Step 2 — Register and provision the controller

### Corrected provisioning

1. Flash `firmware/sgpro_gsm_controller/sgpro_gsm_controller.ino`.

2. Configure:
   - `DEVICE_ID`
   - `API_BASE`

   Set **only** these in firmware configuration. Do **not** put Wi-Fi password or `DEVICE_SECRET` in source.

3. Provision **Wi-Fi credentials** and **`DEVICE_SECRET` separately** through the supported provisioning process.

   Wi-Fi (Serial — never commit to GitHub or shared source):

   ```text
   WIFI <ssid> <password>
   ```

   The **Wi-Fi password is a secret**. It must **not** be committed to GitHub or placed in shared source code.

4. Open Serial Monitor and run:

   ```text
   PROVISION <long-random-device-secret>
   ```

   Optional version after rotate:

   ```text
   PROVISION <long-random-device-secret> 2
   ```

5. Store the secret in **encrypted NVS** where supported (ESP32 Preferences).

6. Store **only**:

   ```
   SHA256(secret + ":" + deviceId)
   ```

   in `gsmDeviceCredentials/{deviceId}`:

   ```
   accountId: "<live account id>"
   secretHash: "<hex of SHA256(secret + \":\" + deviceId)>"
   enabled: true
   secretVersion: 1
   ```

### Credential algorithm (must match backend exactly)

The following must be **identical** in the provisioning tool and Cloud Function:

```
SHA256(secret + ":" + deviceId)
```

Do **not** accidentally use:

- `SHA256(secret)`
- `SHA256(deviceId + ":" + secret)`
- `SHA256(secret:accountId)`

The order and punctuation must match exactly.

### Never store the plain `DEVICE_SECRET` in

- Firestore  
- GitHub  
- Android project  
- Committed Arduino sketch  

---

## Step 3 — Flash and bench-test

### Wiring (gate disconnected)

| DTU (SIM7600G-H) | ESP32 |
|------------------|--------|
| UART TXD | RX (GPIO18) |
| UART RXD | TX (GPIO17) |
| GND | GND |

| Relay | ESP32 |
|--------|--------|
| IN | GPIO5 |
| GND | GND |

Power DTU from **12 V / 7–36 V** — not from ESP32.

Gate must use **isolated dry contacts**. Do **not** feed voltage from the relay module into the gate controller’s trigger terminals unless its manual explicitly requires it.

### Registration / radio bench checks

Run Serial `MODEM_CHECK` or send:

```text
AT+CPIN?
AT+CSQ
AT+CREG?
AT+CEREG?
AT+COPS?
AT+CPSI?
```

| Command | Purpose |
|---------|---------|
| `AT+CPIN?` | SIM ready |
| `AT+CSQ` | Signal quality |
| `AT+CREG?` | Circuit-switched registration |
| `AT+CEREG?` | **LTE packet registration** |
| `AT+COPS?` | Operator |
| `AT+CPSI?` | Radio / system info |

### Last-known-good whitelist

Firmware must **not** replace the working whitelist until the new download has been:

1. Authenticated  
2. Fully downloaded  
3. Parsed successfully  
4. Validated  
5. Saved successfully  

A **failed or empty** response must **not** erase the local whitelist.

### Welcome SMS (idempotent)

- Each queue item: unique **`jobId`** in `gsmSmsQueue/{jobId}`  
- Status: `PENDING` → `SENDING` → `SENT` | `FAILED`  
- Mark **SENT** only after modem shows:

  ```text
  +CMGS: <reference>
  OK
  ```

- Restarting the ESP32 must **not** resend the same job after a successful ack.

### Relay electrical behaviour (before connecting gate)

| Condition | Required |
|-----------|----------|
| ESP32 starts | Relay remains **OFF** |
| ESP32 resets | Relay remains **OFF** |
| Modem reconnects | Relay remains **OFF** |
| Authorised call | **One** pulse only |
| Pulse duration | **~3 seconds** |

### Other bench checks

| Check | Pass |
|--------|------|
| AT response | ☐ |
| Network registration (CEREG/CREG/COPS) | ☐ |
| Whitelist download | ☐ |
| Local-cache reload after restart | ☐ |
| Welcome SMS once | ☐ |
| Authorised call hang-up | ☐ |
| Three-second relay pulse | ☐ |
| Unknown caller rejection | ☐ |
| Private/hidden caller rejection | ☐ |

---

## Step 4 — Production cleanup

Debug-only (must not ship in **release** builds):

| Item | Rule |
|------|------|
| `client@rjl.com.au` / `Client123!` | `BuildConfig.DEBUG` only |
| Seeded customer records | Seeder skipped when not DEBUG |
| Test device credentials | Never commit real secret / production hash |

Release: Firebase Authentication + live Firestore only.

---

## Device API protection (deployed)

The API **must** verify:

| Check | Header / field |
|--------|----------------|
| deviceId | `X-Device-Id` matches path |
| timestamp | `X-Timestamp` within ±5 minutes |
| nonce | `X-Nonce` unique (no reuse) |
| request signature | `X-Signature` = HMAC-SHA256(secret, METHOD\\npath\\nts\\nnonce\\nbody) |
| secretVersion | `X-Secret-Version` equals stored `secretVersion` |
| secret | `SHA256(secret + ":" + deviceId)` equals `secretHash` |

Reject:

- expired timestamps  
- reused nonces  
- incorrect signatures  
- disabled devices  
- incorrect / missing account IDs  
- old credential versions after rotation (`secret_version_mismatch`)  

---

## Final readiness decision

The system is ready to connect to the **sliding gate** only after **all** of these pass:

| # | Criterion | ☐ |
|---|-----------|---|
| 1 | Firebase API deployed | |
| 2 | Firestore rules and indexes deployed | |
| 3 | Device credential registered (`secretHash` = SHA256(secret+":"+deviceId)) | |
| 4 | ESP32 provisioned (secret + Wi-Fi in NVS) | |
| 5 | SIM7600 responds to AT commands | |
| 6 | Whitelist sync succeeds | |
| 7 | Whitelist survives restart (last-known-good) | |
| 8 | Welcome SMS sends **once** (jobId ack after +CMGS/OK) | |
| 9 | Authorised call hangs up | |
| 10 | Relay pulses **once** for **~3 seconds** | |
| 11 | Unknown caller does not activate relay | |
| 12 | Private caller does not activate relay | |
| 13 | Disabled caller stops working after sync | |
| 14 | Cached whitelist works without Wi-Fi | |
| 15 | Relay remains **OFF** during startup and reset | |

### On-site acceptance (operational day)

```
Add caller in portal
→ whitelist version increases
→ ESP32 syncs within ~60 seconds
→ welcome SMS arrives once
→ authorised call hangs up and pulses relay
→ disabled caller no longer opens
→ unknown/private caller never opens
→ cached whitelist still works with Wi-Fi disconnected
```

---

## Status

| Area | Status |
|------|--------|
| Code / architecture | Matches this plan |
| Secrets in Git | Must remain **out** of repo |
| Live until | **29 July 2026** |

**With these corrections, this document is the final production and on-site commissioning checklist.**
