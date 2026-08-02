/**
 * Dev-only preload shim for the Firebase FUNCTIONS emulator.
 *
 * Why this exists:
 *   The Cloud Functions emulator (firebase-tools) stubs `firebase-admin` and, for
 *   the `firestore` namespace, returns `admin.firestore.bind(admin)` whenever the
 *   value is not detected as a constructor. `firebase-admin@12` exposes
 *   `admin.firestore` as a plain getter function (no `.prototype`), so the bind
 *   drops its static members — `FieldValue`, `Timestamp`, `GeoPoint`, `FieldPath`.
 *   Any code using `admin.firestore.FieldValue.serverTimestamp()` (this app does,
 *   e.g. the nonce claim, welcome-SMS trigger, and action logging) then throws
 *   "Cannot read properties of undefined (reading 'serverTimestamp')" — but ONLY
 *   under the emulator; deployed Cloud Functions and plain Node are unaffected.
 *
 * What this does:
 *   Preloaded via NODE_OPTIONS="--require <this file>" before the emulator stubs
 *   admin. It redefines `admin.firestore` as a stable NAMED function (which has a
 *   real `.prototype`, so the emulator treats it as a constructor and does NOT
 *   bind it) while copying across the static members. This restores
 *   `admin.firestore.FieldValue` et al inside the emulator.
 *
 * This changes no application code and is a no-op in any process that does not
 * have firebase-admin on its resolution path (e.g. the firebase CLI itself).
 */
try {
  const candidatePaths = [
    process.env.FUNCTIONS_DIR,
    "/workspace/functions",
    process.cwd(),
    require("path").join(process.cwd(), "functions"),
  ].filter(Boolean);

  const resolved = require.resolve("firebase-admin", { paths: candidatePaths });
  const admin = require(resolved);
  const real = admin.firestore; // getter result: fn with statics attached

  if (real && typeof real === "function" && typeof real.FieldValue !== "undefined") {
    function firestore(...args) {
      return real.apply(this, args);
    }
    // Copy every own static (FieldValue, Timestamp, GeoPoint, FieldPath, v1, ...).
    for (const key of Object.getOwnPropertyNames(real)) {
      if (key === "length" || key === "name" || key === "prototype") continue;
      try {
        Object.defineProperty(
          firestore,
          key,
          Object.getOwnPropertyDescriptor(real, key)
        );
      } catch (_) {
        /* ignore non-copyable */
      }
    }
    Object.defineProperty(admin, "firestore", {
      value: firestore,
      writable: true,
      configurable: true,
      enumerable: true,
    });
  }
} catch (_) {
  // firebase-admin not resolvable in this process — nothing to patch.
}
