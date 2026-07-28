# Deploy GSM API on another Firebase project

Use this when **`rjl-maintenance-app` cannot enable Blaze / Cloud Functions** (e.g. unpaid bill).

## Recommended model

| Piece | Where |
|--------|--------|
| **Cloud Function** `gsmDeviceApi` | **New** project (clean billing) |
| **Device credentials + whitelist + call logs** | **Same new** project, **(default)** Firestore |
| **Android client app** | Can stay on `rjl-maintenance-app` for Auth until you migrate |
| **ESP32** | Point `API_BASE` at the **new** function URL |

The phone app and the ESP32 do **not** have to share one project forever. For the **gate**, only the ESP32 ↔ API ↔ Firestore (callers + secrets) path matters.

---

## A. Create the new project

1. Open [Firebase Console](https://console.firebase.google.com/) as an account **with working Blaze billing**.
2. **Add project** → name e.g. `RJL GSM API` → note **Project ID** (e.g. `rjl-gsm-api`).
3. Upgrade that project to **Blaze**.
4. Enable:
   - **Firestore** → create **(default)** database (production mode is fine; rules can deny clients).
   - **Cloud Functions** (will enable when you deploy).
5. Optional: keep this project API-only (no Android app).

---

## B. Wire the CLI to the new project

```powershell
cd C:\Users\User\AndroidStudioProjects\RJLMulticomSGPROCLIENTPORTAL
firebase login --reauth
firebase projects:list
firebase use <NEW_PROJECT_ID>
```

Add an alias so you can switch later:

```powershell
firebase use --add
# pick new project, alias: gsm-api
# keep rjl-maintenance-app as alias: main (if you still have access)
```

---

## C. Build and deploy functions

```powershell
cd C:\Users\User\AndroidStudioProjects\RJLMulticomSGPROCLIENTPORTAL\functions
npm install
npm run build
cd ..
firebase deploy --only functions --project <NEW_PROJECT_ID>
```

When finished, note the URL, e.g.:

```text
https://australia-southeast1-<NEW_PROJECT_ID>.cloudfunctions.net/gsmDeviceApi
```

### Environment variable

Functions code defaults to Firestore **`(default)`**.  
If you ever point this codebase back at `rjl-maintenance-app`’s named DB, set:

| Name | Value |
|------|--------|
| `FIRESTORE_DATABASE_ID` | `maintenancejobs` |

On a clean project leave it unset (uses `(default)`).

---

## D. Seed device + account data (new project Firestore)

In Firebase Console → **Firestore** → **(default)** DB:

### 1. Account

Collection `clientAccounts` → document `acct_commercial_bc`:

```json
{
  "siteName": "337 SETTLEMENT ROAD - THOMASTOWN · SLIDING GATE",
  "gsmNumber": "+61414371302",
  "enabled": true,
  "timezone": "Australia/Melbourne",
  "whitelistVersion": 1,
  "modules": ["GSM"]
}
```

### 2. Device credentials

Collection `gsmDeviceCredentials` → document `device_commercial_bc_01`:

```json
{
  "accountId": "acct_commercial_bc",
  "secretHash": "<see below>",
  "enabled": true,
  "secretVersion": 1
}
```

**secretHash** (must match ESP32 `PROVISION` secret):

```text
secretHash = SHA256( utf8( secret + ":" + deviceId ) ) as hex lowercase
```

Example PowerShell (replace `YOUR_SECRET`):

```powershell
$deviceId = "device_commercial_bc_01"
$secret = "YOUR_SECRET"
$bytes = [Text.Encoding]::UTF8.GetBytes("$secret`:$deviceId")
$sha = [Security.Cryptography.SHA256]::Create()
($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString("x2") }) -join ""
```

### 3. Callers (whitelist)

Under `clientAccounts/acct_commercial_bc/gsmCallers/{callerId}` for each mobile:

```json
{
  "displayName": "Robert Ozzimo",
  "phoneNumberE164": "+61403311435",
  "enabled": true,
  "role": "OWNER",
  "notes": "Unit 1"
}
```

Only **enabled** numbers with valid E.164 (and optional validFrom/validUntil) are returned to the ESP32.

You can paste from `CommercialWhitelistSeed.kt` or export from the phone app later.

### 4. Optional device shell

`clientAccounts/acct_commercial_bc/gsmDevices/device_commercial_bc_01`:

```json
{
  "deviceName": "Thomastown Sliding Gate",
  "enabled": true,
  "whitelistVersion": 0
}
```

---

## E. Point the ESP32 at the new API

In `firmware/sgpro_gsm_controller/sgpro_gsm_controller.ino`:

```cpp
const char *API_BASE =
    "https://australia-southeast1-<NEW_PROJECT_ID>.cloudfunctions.net/gsmDeviceApi";
const char *DEVICE_ID = "device_commercial_bc_01";
```

Flash, then Serial:

```text
PROVISION <YOUR_SECRET>
WIFI "Jarrods wifi" <password>
RECONNECT
```

Expect:

```text
WiFi: CONNECTED ...
HTTP GET /gsm/device/device_commercial_bc_01/whitelist → 200
```

| Code | Meaning |
|------|--------|
| **200** | Good |
| **HTML 404** | Wrong project / not deployed / wrong URL |
| **401** | Missing/bad signature or time |
| **403** `device_disabled_or_unknown` / `auth_failed` | Credential doc or secret mismatch |
| **403** `secret_version_mismatch` | `secretVersion` ≠ NVS |

---

## F. Android app later

- App can stay on **`rjl-maintenance-app`** for login until the bill is paid.
- Portal “add caller” on the **old** project will **not** update the ESP32 until either:
  - you migrate app writes to the **new** project, or  
  - you move Functions back to `rjl-maintenance-app` after billing is fixed.

**Until then:** manage the ESP32 whitelist in the **new** project’s Firestore (Console or a small admin script).

---

## G. When the old bill is paid

1. Deploy functions to `rjl-maintenance-app` again.
2. Set `FIRESTORE_DATABASE_ID=maintenancejobs` if you still use that named DB.
3. Restore firmware `API_BASE` to the maintenance-app URL.
4. Copy `gsmDeviceCredentials` + callers if needed.
5. Decommission the temporary project (or keep it as staging).

---

## Checklist

- [ ] New project created + **Blaze** active  
- [ ] Firestore **(default)** created  
- [ ] `firebase use <NEW_PROJECT_ID>`  
- [ ] `firebase deploy --only functions`  
- [ ] `gsmDeviceCredentials/device_commercial_bc_01` + hash  
- [ ] `clientAccounts/.../gsmCallers` seeded  
- [ ] Firmware `API_BASE` updated + flashed  
- [ ] Serial: PROVISION + WIFI → whitelist **200**  
