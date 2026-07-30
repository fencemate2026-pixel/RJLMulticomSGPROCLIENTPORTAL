"use strict";
/**
 * Audit Multicom Firestore gsmCallers → canonical E.164.
 * Requires Firebase CLI login: firebase login --reauth
 *
 * Usage:
 *   node scripts/audit-gsm-callers.js
 *   node scripts/audit-gsm-callers.js --fix   (write-back phoneNumberE164 if wrong form)
 */
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");

const PROJECT_ID = "iiii-7b9e8";
const DATABASE_ID = "gsmsimcared";
const fix = process.argv.includes("--fix");

const tools = path.join(process.env.APPDATA || "", "npm", "node_modules", "firebase-tools", "lib");
const auth = require(path.join(tools, "auth.js"));
const api = require(path.join(tools, "api.js"));
const account = auth.getGlobalDefaultAccount();
if (!account?.tokens?.refresh_token) {
  throw new Error("Firebase CLI not authenticated. Run: firebase login --reauth");
}
const adc = path.join(os.tmpdir(), `rjl-adc-caller-audit-${process.pid}.json`);
fs.writeFileSync(
  adc,
  JSON.stringify({
    type: "authorized_user",
    client_id: api.clientId(),
    client_secret: api.clientSecret(),
    refresh_token: account.tokens.refresh_token,
  }),
  { mode: 0o600 }
);
process.env.GOOGLE_APPLICATION_CREDENTIALS = adc;
process.on("exit", () => {
  try {
    fs.unlinkSync(adc);
  } catch {}
});

const admin = require("../functions/node_modules/firebase-admin");
admin.initializeApp({ projectId: PROJECT_ID });
const { getFirestore } = require("../functions/node_modules/firebase-admin/lib/firestore/index.js");
const db = getFirestore(admin.app(), DATABASE_ID);

function normalize(raw) {
  let d = String(raw || "")
    .trim()
    .replace(/[\s()-]/g, "");
  if (d.startsWith("+")) d = d.slice(1);
  else if (d.startsWith("00")) d = d.slice(2);
  if (/^04\d{8}$/.test(d)) d = `61${d.slice(1)}`;
  else if (/^4\d{8}$/.test(d)) d = `61${d}`;
  return /^614\d{8}$/.test(d) ? `+${d}` : null;
}

async function main() {
  const callers = await db.collectionGroup("gsmCallers").get();
  const report = [];
  let ok = 0;
  let needs = 0;
  let bad = 0;
  let fixed = 0;
  for (const doc of callers.docs) {
    const x = doc.data() || {};
    const raw = String(x.phoneNumberE164 || x.phoneNumber || "");
    const e164 = normalize(raw);
    const accountId = doc.ref.path.split("/")[1];
    const enabled = x.enabled !== false && x.active !== false;
    let status = "OK";
    if (!e164) {
      status = "INVALID";
      bad++;
    } else if (e164 !== raw.trim()) {
      status = "NEEDS_NORMALIZE";
      needs++;
      if (fix) {
        await doc.ref.set({ phoneNumberE164: e164 }, { merge: true });
        fixed++;
        status = "FIXED";
      }
    } else {
      ok++;
    }
    report.push({
      accountId,
      callerId: doc.id,
      name: x.displayName || x.name || "",
      raw,
      e164,
      enabled,
      status,
    });
  }
  report.sort((a, b) =>
    `${a.accountId}${a.e164 || a.raw}`.localeCompare(`${b.accountId}${b.e164 || b.raw}`)
  );
  const outDir = path.join(__dirname, "..", ".local");
  fs.mkdirSync(outDir, { recursive: true });
  const out = path.join(outDir, "gsm_callers_audit.json");
  fs.writeFileSync(
    out,
    JSON.stringify(
      {
        generatedAt: new Date().toISOString(),
        fixApplied: fix,
        summary: { total: report.length, ok, needs_normalize: needs, invalid: bad, fixed },
        callers: report,
      },
      null,
      2
    )
  );
  console.log(
    JSON.stringify(
      { summary: { total: report.length, ok, needs_normalize: needs, invalid: bad, fixed }, wrote: out },
      null,
      2
    )
  );
  const invalid = report.filter((r) => r.status === "INVALID");
  if (invalid.length) {
    console.log("INVALID rows:");
    for (const r of invalid) console.log(JSON.stringify(r));
  }
}

main().catch((e) => {
  console.error(e.message || e);
  if (String(e.message || e).includes("invalid_rapt") || String(e).includes("invalid_grant")) {
    console.error("\nRe-auth required: firebase login --reauth");
  }
  process.exit(1);
});
