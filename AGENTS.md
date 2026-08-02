# AGENTS.md

## Cursor Cloud specific instructions

This repo contains several components (see `README.md`): a customer Android app
(`app/`), the Firebase Cloud Functions backend (`functions/`), and ESP32-S3
firmware (`firmware/`). In a headless Linux cloud VM, **only the Firebase Cloud
Functions backend is runnable/testable** — the Android app needs the Android SDK
+ a device/emulator, and the firmware needs physical ESP32/SIM7600 hardware.

### Backend service: Firebase Cloud Functions (`functions/`)

- TypeScript → CommonJS. Build: `npm --prefix functions run build` (`tsc` → `lib/`).
- The dependency-refresh update script already runs `npm ... ci` for `functions`
  and installs the `firebase-tools` CLI globally, so `firebase` is on `PATH`.
- Firestore uses a **named database `gsmsimcared`** (env `FIRESTORE_DATABASE_ID`,
  set in `functions/.env.iiii-7b9e8`). Seed and read against that database id, not
  `(default)`.
- The core API is `gsmDeviceApi` (HTTP), which the ESP32 polls for its caller
  whitelist. Auth is HMAC-SHA256 over `METHOD\npath\nX-Timestamp\nX-Nonce\nbody`;
  auth v2 signs with the stored `secretHash` (see `docs/GO_LIVE.md` for the scheme).

#### Running the emulator (IMPORTANT non-obvious caveat)

Start the backend with the Firestore + Functions emulators:

```bash
cd /workspace
FUNCTIONS_DIR=/workspace/functions \
NODE_OPTIONS="--require /workspace/scripts/emulator-admin-fix.js" \
firebase emulators:start --only functions,firestore --project iiii-7b9e8
```

The `NODE_OPTIONS` preload shim (`scripts/emulator-admin-fix.js`) is **required**.
Without it, the Cloud Functions emulator (firebase-tools) binds `admin.firestore`
and drops its static members, so `admin.firestore.FieldValue.serverTimestamp()`
throws `Cannot read properties of undefined (reading 'serverTimestamp')` at runtime
— this breaks the nonce claim, the welcome-SMS trigger, and action logging, causing
`gsmDeviceApi` to return `401 nonce_reused_or_invalid`. This only happens under the
emulator; deployed Cloud Functions and plain Node are unaffected. The shim is a
no-op in processes without `firebase-admin` (e.g. the `firebase` CLI itself).

Emulator UI: http://127.0.0.1:4000 · Functions: 127.0.0.1:5001 · Firestore: 127.0.0.1:8080.
The `MetadataLookupWarning` / "not currently authenticated" lines at startup are
benign in the emulator.

#### End-to-end smoke test

`/tmp/hello_world.js` (created during setup; recreate if missing) seeds a device
credential + Thomastown account + callers into the Firestore emulator, then makes a
signed whitelist request. Run with the emulator up:

```bash
cd /workspace/functions && NODE_PATH=/workspace/functions/node_modules node /tmp/hello_world.js
```

Expected: TEST 1 (valid signed whitelist GET) → HTTP 200 with the caller list;
TEST 2 (tampered signature) → HTTP 403 `bad_signature`.

### Gotchas

- **`functions/node_modules` is committed to the repo** (from a Windows machine),
  and its `.bin` shims reference `node.exe` — unusable on Linux. The update script
  reinstalls (`npm ci`) to regenerate valid Linux bin symlinks. Because those files
  are tracked, `git status` will show many `functions/node_modules/...` changes after
  startup — **do not commit them**. (`core.fileMode false` is set locally so exec-bit
  churn is hidden.)
- `npm --prefix functions run lint` does **not** work: the `lint` script calls
  `eslint`, but eslint is not a dependency and there is no eslint config. There is no
  working lint step for this project.
- There are no automated tests in this repo.
