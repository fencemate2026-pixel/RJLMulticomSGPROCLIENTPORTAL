# ESP32 GSM Device API — Production contract

Base URL (after deploy):

```
https://australia-southeast1-rjl-maintenance-app.cloudfunctions.net/gsmDeviceApi
```

## Authentication (every request)

| Header | Value |
|--------|--------|
| `X-Device-Id` | device id (must match path) |
| `X-Device-Secret` | raw secret (TLS only; NVS on device) |
| `X-Timestamp` | Unix **milliseconds** (±5 min) |
| `X-Nonce` | unique random string per request (no reuse) |
| `X-Secret-Version` | integer matching `gsmDeviceCredentials.secretVersion` |
| `X-Signature` | hex HMAC-SHA256 |

### Signature payload (exact)

```
METHOD\n
path\n
timestamp\n
nonce\n
body
```

Example path: `/gsm/device/device_commercial_bc_01/whitelist`  
GET body is empty string.

### Credential hash (exact — provisioning tool + Cloud Function)

```
secretHash = SHA256( secret + ":" + deviceId )
```

**Do not** use `SHA256(secret)`, `SHA256(deviceId + ":" + secret)`, or `SHA256(secret:accountId)`.

### Rejects

| Condition | Error |
|-----------|--------|
| Bad/expired timestamp | `timestamp_invalid` |
| Reused nonce | `nonce_reused_or_invalid` |
| Wrong signature | `bad_signature` |
| Disabled / unknown device | `device_disabled_or_unknown` |
| Hash mismatch | `auth_failed` |
| Wrong secretVersion | `secret_version_mismatch` |
| Account disabled | `account_disabled` |
| Rate limit | `rate_limited` |

---

## GET `/gsm/device/{deviceId}/whitelist`

Returns active **E.164** callers + pending SMS jobs.

```json
{
  "accountId": "…",
  "deviceId": "…",
  "version": 12,
  "siteName": "…",
  "gateSimE164": "+61414371302",
  "callers": [
    { "id": "…", "name": "…", "phoneNumberE164": "+614…", "enabled": true }
  ],
  "smsJobs": [
    {
      "jobId": "unique-id",
      "callerId": "…",
      "phoneNumberE164": "+614…",
      "message": "…",
      "status": "PENDING",
      "attemptCount": 0
    }
  ]
}
```

### Device last-known-good rules

1. Authenticated request fails or body empty → **keep LKG**  
2. Parse / validation fails → **keep LKG**  
3. Success → temp list → validate → **atomic activate** → save NVS  
4. Never erase working list on failed download  

---

## POST `/gsm/device/{deviceId}/sms-claim`

```json
{ "jobIds": ["job1", "job2"] }
```

Marks jobs `SENDING` for this device/account only.

## POST `/gsm/device/{deviceId}/sms-ack`

Mark **SENT** only after modem:

```
+CMGS: <reference>
OK
```

```json
{
  "results": [
    {
      "jobId": "job1",
      "ok": true,
      "cmgsReference": "3",
      "phoneNumberE164": "+614…"
    },
    { "jobId": "job2", "ok": false, "error": "cmgs_failed" }
  ]
}
```

Unique `jobId` + ack prevents duplicate welcome SMS after restart.

## POST `/gsm/device/{deviceId}/events`

Call outcome log (does not drive relay timing).

## POST `/gsm/device/{deviceId}/heartbeat`

Liveness; may include `accountWhitelistVersion` to force early pull.

---

## Collections

| Path | Role |
|------|------|
| `gsmDeviceCredentials/{deviceId}` | secretHash, accountId, enabled, **secretVersion** |
| `gsmDeviceNonces/{deviceId_nonce}` | replay protection |
| `clientAccounts/{id}` | `gsmNumber` in **E.164**, `whitelistVersion` |
| `clientAccounts/{id}/gsmCallers` | whitelist (E.164 phones) |
| `clientAccounts/{id}/gsmDevices` | heartbeat / last sync |
| `gsmSmsQueue/{jobId}` | welcome SMS jobs |

## Incoming call (device)

Local only — never wait for cloud:

1. +CLIP  
2. Normalise E.164  
3. Local LKG lookup  
4. **AT+CHUP**  
5. Authorised → **one** ~3s dry-contact pulse  
6. Upload event when online  

## Serial provisioning

| Command | Purpose |
|---------|---------|
| `PROVISION <secret> [version]` | Secret → NVS |
| `WIFI <ssid> <password>` | Wi-Fi → NVS (secret; not in Git) |
| `MODEM_CHECK` | CPIN, CSQ, CREG, CEREG, COPS, CPSI |
| `SYNC` | Pull whitelist now |
| `STATUS` | Credential / list / Wi-Fi / relay |

## Operational date

**Not operational until 29 July 2026.**
