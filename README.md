# RJL Multicom SG-PRO — Client Portal

Customer-facing Android app for property owners and family members.

The app manages authorised GSM callers for an on-site ESP32-S3 and SIM7600G-H gate controller. It also supports Wi-Fi, RFID, and LPR access-control modules.

## Go-live status

> The project is mostly built. It is not ready to connect to the gate until all four commissioning stages pass.

See [docs/GO_LIVE.md](docs/GO_LIVE.md) for the complete deployment, provisioning, bench-testing, and production checklist.

### Commissioning stages

| Stage | Code status | Remaining action |
|---|---|---|
| 1. Deploy Firebase | Ready in `functions/` | Deploy `gsmDeviceApi`, Firestore rules, and required indexes |
| 2. Provision controller | Ready | Create `gsmDeviceCredentials/{deviceId}` and provision the secret into ESP32 NVS |
| 3. Bench-test | Ready in `firmware/` | Test AT communication, whitelist sync, SMS, caller rejection, and relay pulse |
| 4. Production cleanup | Supported | Build a release version using live Firebase data without demo seeds |

### Already implemented

- Portal caller add, edit, disable, delete, and list functions
- Automatic whitelist-version updates
- ESP32 whitelist polling and local NVS caching
- Gate operation using the last valid cached whitelist when internet access is unavailable
- Incoming caller-ID detection
- E.164 phone-number normalisation
- Immediate modem hang-up before relay activation
- Three-second relay pulse
- Hidden, unknown, disabled, and expired caller rejection
- Welcome SMS queue and acknowledgement handling
- Device heartbeat and event reporting
- Debug-only demo login and seeded contacts
- Device-secret provisioning through Serial and NVS

### Required before connecting the gate

The following tests must pass while the gate remains disconnected:

1. SIM7600 responds to AT commands.
2. The ESP32 downloads and activates the whitelist.
3. The whitelist survives an ESP32 restart.
4. A newly added caller receives the welcome SMS.
5. An authorised caller is hung up and triggers one three-second relay pulse.
6. An unknown caller does not activate the relay.
7. A private or withheld caller does not activate the relay.
8. A disabled caller stops working after the next successful sync.
9. The cached whitelist continues working with Wi-Fi disconnected.
10. The relay remains off during startup, reset, and modem reconnection.

## Thomastown site

| Setting | Value |
|---|---|
| Site | 337 Settlement Road, Thomastown |
| Gate type | Sliding gate |
| Gate SIM | `+61414371302` |
| RJL support mobile | `+61400101132` |

## Device workflow

```text
Add caller in portal
→ save caller in Firestore
→ increment whitelistVersion
→ queue welcome SMS
→ ESP32 downloads and validates whitelist
→ ESP32 saves whitelist to NVS
→ SIM7600 sends welcome SMS
→ SMS job is acknowledged as sent

Delete or disable caller
→ update Firestore
→ increment whitelistVersion
→ ESP32 downloads updated whitelist
→ caller is removed from the active local list

Incoming authorised call
→ read caller ID
→ normalise to E.164
→ check local whitelist
→ send AT+CHUP
→ pulse isolated relay for three seconds

Internet unavailable
→ incoming calls continue using the cached whitelist
→ cloud synchronisation and queued SMS resume when connectivity returns
```

## Production requirements

Production builds must use:

- Firebase Authentication
- Live Firestore account and caller records
- Device-specific credentials
- HTTPS API requests
- Secure ESP32 NVS storage
- Deployed Firestore rules and indexes

Production builds must not contain:

- Demo passwords
- Live device secrets
- Hard-coded authorised callers
- Production credentials in source control
- Debug customer seeds

## Documentation

| Document | Description |
|---|---|
| [docs/GO_LIVE.md](docs/GO_LIVE.md) | Go-live checklist |
| [docs/ESP32_GSM_API.md](docs/ESP32_GSM_API.md) | ESP32 GSM API |
| [docs/GSM-ESP32-COMMERCIAL.md](docs/GSM-ESP32-COMMERCIAL.md) | Commercial GSM controller |
| [docs/MILDURA_PI_FULL_AUDIT.md](docs/MILDURA_PI_FULL_AUDIT.md) | Mildura Pi full audit + fixes |

## Firmware

The production controller firmware is located at:

`firmware/sgpro_gsm_controller/sgpro_gsm_controller.ino`

## Related projects

| Project | Role |
|---|---|
| mildura-boomgate | On-site Raspberry Pi gate portal and controller |
| mildura-boomgate-app | RJL installer and ProGate management app |
| RJL Multicom SG-PRO Client Portal | Customer and family access-management app |
