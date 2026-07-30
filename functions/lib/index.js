"use strict";
/**
 * RJL Multicom SG-PRO — ESP32 GSM Device API (production)
 *
 * Final behaviour:
 *   Portal add/delete → whitelistVersion++ → optional gsmSmsQueue job
 *   ESP32 polls whitelist (~60s), atomic local cache
 *   Calls: local list only, CHUP then relay 3s
 *   Welcome SMS: gsmSmsQueue PENDING→SENDING→SENT|FAILED, ack by jobId
 *
 * Auth: X-Device-Id, X-Timestamp, X-Nonce, X-Signature (HMAC-SHA256)
 *        secret verified via sha256(secret:deviceId) == secretHash
 *        Nonces stored short-lived to block replay.
 */
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.queueWelcomeSmsOnCallerCreated = exports.gsmDeviceApi = exports.createSmsCampaign = exports.askPortalAssistant = exports.requestRemoteGateTest = exports.SMS_MULTIPART_SAFE_CHARS = exports.THOMASTOWN_WELCOME_TEMPLATE = void 0;
exports.buildWelcomeSmsParts = buildWelcomeSmsParts;
exports.provisionDeviceCredential = provisionDeviceCredential;
const admin = __importStar(require("firebase-admin"));
const firestore_1 = require("firebase-admin/firestore");
const crypto = __importStar(require("crypto"));
const https_1 = require("firebase-functions/v2/https");
const firestore_2 = require("firebase-functions/v2/firestore");
const v2_1 = require("firebase-functions/v2");
const params_1 = require("firebase-functions/params");
(0, v2_1.setGlobalOptions)({ region: "australia-southeast1", maxInstances: 40 });
admin.initializeApp();
/**
 * Firestore target for this Functions deployment.
 * Default: "(default)" — must match the Database ID in Firebase Console.
 * Override with env FIRESTORE_DATABASE_ID if you created a named DB.
 */
const FIRESTORE_DATABASE_ID = process.env.FIRESTORE_DATABASE_ID?.trim() || "gsmsimcared";
const db = (0, firestore_1.getFirestore)(admin.app(), FIRESTORE_DATABASE_ID === "" ? "(default)" : FIRESTORE_DATABASE_ID);
const MAX_SKEW_MS = 5 * 60 * 1000;
const NONCE_TTL_MS = 10 * 60 * 1000;
const RATE_WINDOW_MS = 60000;
const RATE_MAX = 60;
const SMS_MAX_ATTEMPTS = 3;
const SMS_CLAIM_LEASE_MS = 2 * 60 * 1000;
const SMS_RETRY_DELAYS_MS = [30000, 120000, 600000];
exports.THOMASTOWN_WELCOME_TEMPLATE = "Hi {name}, gate access is active at 337 Settlement Rd, Thomastown. Call 0414 371 302 to open. Caller ID must be visible. Help: 0400 101 132.";
exports.SMS_MULTIPART_SAFE_CHARS = 160;
const WELCOME_CAMPAIGN_ID = "thomastown-short-welcome-2026-07-27";
const NEVER_RESEND_NUMBER = "+61400101132";
const XAI_API_KEY = (0, params_1.defineSecret)("XAI_API_KEY");
const rateBuckets = new Map();
exports.requestRemoteGateTest = (0, https_1.onCall)(async (request) => {
    const uid = request.auth?.uid;
    if (!uid)
        throw new https_1.HttpsError("unauthenticated", "Sign in is required.");
    const profile = await db.collection("clientUsers").doc(uid).get();
    const user = profile.data();
    if (!profile.exists || user?.enabled !== true || user?.role !== "OWNER") {
        throw new https_1.HttpsError("permission-denied", "Only the property owner can run a gate test.");
    }
    const accountId = String(user.accountId || "");
    const accountRef = db.collection("clientAccounts").doc(accountId);
    const devices = await accountRef.collection("gsmDevices").where("enabled", "==", true).get();
    const deviceId = devices.docs[0]?.id || "device_commercial_bc_01";
    const commandRef = db.collection("gsmDeviceCommands").doc();
    await commandRef.set({
        accountId,
        deviceId,
        type: "remote_gate_test",
        status: "queued",
        requestedBy: uid,
        requestedAt: admin.firestore.FieldValue.serverTimestamp(),
        expiresAt: admin.firestore.Timestamp.fromMillis(Date.now() + 5 * 60000),
    });
    await db.collection("clientActionLogs").add({
        accountId,
        userId: uid,
        userEmail: String(user.email || request.auth?.token.email || ""),
        action: "REMOTE_GATE_TEST_QUEUED",
        detail: `command=${commandRef.id} device=${deviceId}`,
        success: true,
        timestamp: admin.firestore.FieldValue.serverTimestamp(),
        source: "client_portal",
    });
    return { commandId: commandRef.id, deviceId, expiresInSeconds: 300 };
});
exports.askPortalAssistant = (0, https_1.onCall)({ secrets: [XAI_API_KEY], timeoutSeconds: 90 }, async (request) => {
    const message = String(request.data?.message || "").trim().slice(0, 2000);
    const pageContext = String(request.data?.pageContext || "").trim().slice(0, 1000);
    if (!message)
        throw new https_1.HttpsError("invalid-argument", "Enter a question.");
    const rawHistory = Array.isArray(request.data?.history) ? request.data.history : [];
    const history = rawHistory.slice(-12).flatMap((item) => {
        if (!item || typeof item !== "object")
            return [];
        const value = item;
        const role = value.role === "assistant" ? "assistant" : "user";
        const content = String(value.content || "").trim().slice(0, 2000);
        return content ? [{ role, content }] : [];
    });
    const system = [
        "You are the secure in-app assistant for the RJL Multicom SG-PRO Client Portal.",
        "Give concise, practical instructions in Australian English.",
        "Use **bold** sparingly for important buttons, requirements, and warnings.",
        "Help with login, 4-digit PIN security, GSM callers, SIM7600 SMS, GNSS location,",
        "gate opening, schedules, device status, modules, and settings.",
        "Never invent credentials, secret keys, device locations, delivery confirmations,",
        "or claim an action succeeded without reported app/device evidence.",
        request.auth
            ? "The user is authenticated; use only the safe page context supplied."
            : "The user is signed out; provide only generic login, password-reset, PIN, and support help.",
        `Safe page context: ${pageContext || "none"}`,
    ].join("\n");
    const response = await fetch("https://api.x.ai/v1/chat/completions", {
        method: "POST",
        headers: {
            Authorization: `Bearer ${XAI_API_KEY.value()}`,
            "Content-Type": "application/json",
        },
        body: JSON.stringify({
            model: process.env.XAI_MODEL || "grok-4.5",
            messages: [
                { role: "system", content: system },
                ...history,
                { role: "user", content: message },
            ],
            temperature: 0.3,
            stream: false,
        }),
    });
    if (!response.ok) {
        console.warn("xAI assistant error", response.status);
        throw new https_1.HttpsError("unavailable", "The AI assistant is temporarily unavailable.");
    }
    const body = (await response.json());
    const reply = body.choices?.[0]?.message?.content?.trim() || "";
    if (!reply)
        throw new https_1.HttpsError("unavailable", "The AI assistant returned no reply.");
    return { reply };
});
/**
 * Owner-only portal entry point for individual and bulk SMS.
 * The SIM7600 claims these queue rows and reports the actual modem result.
 */
exports.createSmsCampaign = (0, https_1.onCall)(async (request) => {
    const uid = request.auth?.uid;
    if (!uid)
        throw new https_1.HttpsError("unauthenticated", "Sign in is required.");
    const profile = await db.collection("clientUsers").doc(uid).get();
    const profileData = profile.data();
    if (!profile.exists ||
        profileData?.enabled !== true ||
        profileData?.role !== "OWNER") {
        throw new https_1.HttpsError("permission-denied", "Only the property owner can send SMS.");
    }
    const accountId = String(profileData.accountId || "");
    const message = String(request.data?.message || "").trim();
    const callerIds = Array.isArray(request.data?.callerIds)
        ? Array.from(new Set(request.data.callerIds.map((id) => String(id || "")))).filter((id) => id.length > 0)
        : [];
    if (!accountId)
        throw new https_1.HttpsError("failed-precondition", "No property is assigned.");
    if (!message || message.length > exports.SMS_MULTIPART_SAFE_CHARS) {
        throw new https_1.HttpsError("invalid-argument", `Message must be 1-${exports.SMS_MULTIPART_SAFE_CHARS} characters.`);
    }
    if (callerIds.length < 1 || callerIds.length > 200) {
        throw new https_1.HttpsError("invalid-argument", "Choose between 1 and 200 recipients.");
    }
    const accountRef = db.collection("clientAccounts").doc(accountId);
    const [account, devices, ...callerDocs] = await Promise.all([
        accountRef.get(),
        accountRef.collection("gsmDevices").where("enabled", "==", true).get(),
        ...callerIds.map((id) => accountRef.collection("gsmCallers").doc(id).get()),
    ]);
    if (!account.exists || account.data()?.enabled === false) {
        throw new https_1.HttpsError("failed-precondition", "This property is disabled.");
    }
    const deviceId = devices.docs[0]?.id || "device_commercial_bc_01";
    const now = Date.now();
    const recipients = callerDocs.flatMap((snap) => {
        if (!snap.exists)
            return [];
        const data = snap.data();
        if (!isCallerActive({ id: snap.id, ...data }, now))
            return [];
        const phone = String(data.phoneNumberE164 || "");
        return /^\+[1-9]\d{7,14}$/.test(phone)
            ? [{ callerId: snap.id, phoneNumberE164: phone }]
            : [];
    });
    if (!recipients.length) {
        throw new https_1.HttpsError("failed-precondition", "No selected recipients are currently active.");
    }
    const campaignRef = accountRef.collection("smsCampaigns").doc();
    const batch = db.batch();
    batch.set(campaignRef, {
        message,
        requestedCount: callerIds.length,
        queuedCount: recipients.length,
        createdBy: uid,
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        status: "queued",
        deviceId,
    });
    recipients.forEach((recipient) => {
        const jobRef = db.collection("gsmSmsQueue").doc();
        batch.set(jobRef, {
            accountId,
            deviceId,
            campaignId: campaignRef.id,
            callerId: recipient.callerId,
            phoneNumberE164: recipient.phoneNumberE164,
            message,
            status: "queued",
            attemptCount: 0,
            nextAttemptAt: admin.firestore.FieldValue.serverTimestamp(),
            createdBy: uid,
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
            sentAt: null,
            lastError: null,
        });
    });
    await batch.commit();
    return {
        campaignId: campaignRef.id,
        queued: recipients.length,
        skipped: callerIds.length - recipients.length,
    };
});
function timingSafeEqual(a, b) {
    const ba = Buffer.from(a);
    const bb = Buffer.from(b);
    if (ba.length !== bb.length)
        return false;
    return crypto.timingSafeEqual(ba, bb);
}
function hmacSign(secret, method, path, timestamp, nonce, body) {
    const payload = `${method.toUpperCase()}\n${path}\n${timestamp}\n${nonce}\n${body}`;
    return crypto.createHmac("sha256", secret).update(payload).digest("hex");
}
function rateLimit(deviceId) {
    const now = Date.now();
    const b = rateBuckets.get(deviceId);
    if (!b || now > b.resetAt) {
        rateBuckets.set(deviceId, { count: 1, resetAt: now + RATE_WINDOW_MS });
        return true;
    }
    if (b.count >= RATE_MAX)
        return false;
    b.count += 1;
    return true;
}
async function loadDeviceCred(deviceId) {
    const snap = await db.collection("gsmDeviceCredentials").doc(deviceId).get();
    if (!snap.exists)
        return null;
    const d = snap.data();
    return {
        accountId: String(d.accountId || ""),
        secretHash: String(d.secretHash || ""),
        enabled: d.enabled !== false,
        secretVersion: Number(d.secretVersion || 1),
    };
}
function verifySecret(deviceId, secret, expectedHash) {
    const h = crypto.createHash("sha256").update(`${secret}:${deviceId}`).digest("hex");
    return timingSafeEqual(h, expectedHash);
}
/** Reject reused nonces (Firestore-backed for multi-instance). */
async function claimNonce(deviceId, nonce) {
    if (!nonce || nonce.length < 8 || nonce.length > 128)
        return false;
    const ref = db.collection("gsmDeviceNonces").doc(`${deviceId}_${nonce}`);
    try {
        await db.runTransaction(async (tx) => {
            const snap = await tx.get(ref);
            if (snap.exists)
                throw new Error("reuse");
            tx.set(ref, {
                deviceId,
                nonce,
                createdAt: admin.firestore.FieldValue.serverTimestamp(),
                expiresAtMs: Date.now() + NONCE_TTL_MS,
            });
        });
        return true;
    }
    catch {
        return false;
    }
}
function isCallerActive(c, now) {
    if (c.enabled === false)
        return false;
    const from = c.validFrom?.toMillis?.() ?? c.validFrom ?? null;
    const until = c.validUntil?.toMillis?.() ?? c.validUntil ?? null;
    if (from != null && now < Number(from))
        return false;
    if (until != null && now > Number(until))
        return false;
    const phone = String(c.phoneNumberE164 || "");
    if (!phone.startsWith("+") || phone.length < 9)
        return false;
    return true;
}
function normalizeAustralianMobile(raw) {
    const compact = String(raw || "").trim().replace(/[\s()-]/g, "");
    let digits = compact;
    if (digits.startsWith("+"))
        digits = digits.slice(1);
    else if (digits.startsWith("00"))
        digits = digits.slice(2);
    if (/^04\d{8}$/.test(digits))
        digits = `61${digits.slice(1)}`;
    else if (/^4\d{8}$/.test(digits))
        digits = `61${digits}`;
    if (!/^614\d{8}$/.test(digits))
        return null;
    return `+${digits}`;
}
/** Build exactly one GSM-7 part, shortening a long/compound name if required. */
function buildWelcomeSmsParts(displayName) {
    const usable = displayName.trim().match(/[A-Za-z][A-Za-z'-]*/g) || [];
    if (!usable.length)
        return [];
    const candidates = [usable[0], displayName.trim()];
    for (const name of candidates) {
        const message = exports.THOMASTOWN_WELCOME_TEMPLATE.replace("{name}", name);
        if (/^[\x0A\x0D\x20-\x7E]*$/.test(message) &&
            message.length <= exports.SMS_MULTIPART_SAFE_CHARS)
            return [message];
    }
    return [];
}
function json(res, status, body) {
    res.status(status).set("Cache-Control", "no-store").json(body);
}
exports.gsmDeviceApi = (0, https_1.onRequest)({
    cors: false,
    invoker: "public",
}, async (req, res) => {
    try {
        // Cloud Functions may pass path as:
        //   /gsm/device/{id}/whitelist
        //   /gsmDeviceApi/gsm/device/{id}/whitelist
        //   or originalUrl with query string
        const rawPath = String(req.path || req.url || "").split("?")[0];
        let path = rawPath.replace(/^\/+/, "");
        if (path.startsWith("gsmDeviceApi/")) {
            path = path.slice("gsmDeviceApi/".length);
        }
        // gsm/device/{deviceId}/whitelist|events|heartbeat|sms-jobs|sms-ack
        const parts = path.split("/").filter(Boolean);
        if (parts[0] !== "gsm" || parts[1] !== "device" || parts.length < 4) {
            return json(res, 404, {
                error: "not_found",
                path: rawPath,
                hint: "Expected /gsm/device/{deviceId}/whitelist",
            });
        }
        const deviceId = parts[2];
        const action = parts[3];
        const pathForSig = `/${parts.join("/")}`;
        if (!rateLimit(deviceId)) {
            return json(res, 429, { error: "rate_limited" });
        }
        const tsHeader = String(req.get("X-Timestamp") || "");
        const nonce = String(req.get("X-Nonce") || "");
        const signature = String(req.get("X-Signature") || "");
        const authVersion = String(req.get("X-Auth-Version") || "1");
        const secret = String(req.get("X-Device-Secret") || "");
        const headerDeviceId = String(req.get("X-Device-Id") || deviceId);
        const secretVersionHdr = String(req.get("X-Secret-Version") || "1");
        const secretVersionClient = Number(secretVersionHdr);
        if (headerDeviceId !== deviceId) {
            return json(res, 403, { error: "device_id_mismatch" });
        }
        const ts = Number(tsHeader);
        if (!tsHeader || !Number.isFinite(ts) || Math.abs(Date.now() - ts) > MAX_SKEW_MS) {
            return json(res, 401, { error: "timestamp_invalid" });
        }
        if (!signature || !nonce || (authVersion === "1" && !secret)) {
            return json(res, 401, { error: "missing_credentials" });
        }
        const cred = await loadDeviceCred(deviceId);
        if (!cred || !cred.enabled || !cred.accountId) {
            return json(res, 403, { error: "device_disabled_or_unknown" });
        }
        // Reject old credential versions after rotation
        if (!Number.isFinite(secretVersionClient) ||
            secretVersionClient !== cred.secretVersion) {
            return json(res, 403, { error: "secret_version_mismatch" });
        }
        let signingKey;
        if (authVersion === "2") {
            if (!/^[0-9a-f]{64}$/i.test(cred.secretHash)) {
                return json(res, 403, { error: "credential_hash_invalid" });
            }
            signingKey = cred.secretHash.toLowerCase();
        }
        else if (authVersion === "1") {
            // Compatibility for already-flashed controllers. Auth v2 never sends the secret.
            if (!verifySecret(deviceId, secret, cred.secretHash)) {
                return json(res, 403, { error: "auth_failed" });
            }
            signingKey = secret;
        }
        else {
            return json(res, 401, { error: "auth_version_unsupported" });
        }
        const rawBody = typeof req.rawBody === "object" && Buffer.isBuffer(req.rawBody)
            ? req.rawBody.toString("utf8")
            : req.method === "GET"
                ? ""
                : JSON.stringify(req.body || {});
        const expectedSig = hmacSign(signingKey, req.method, pathForSig, tsHeader, nonce, rawBody);
        const sigOk = timingSafeEqual(expectedSig, signature.toLowerCase()) ||
            timingSafeEqual(expectedSig, signature);
        if (!sigOk) {
            return json(res, 403, { error: "bad_signature" });
        }
        // Claim only authenticated nonces; invalid signatures cannot fill the nonce store.
        if (!(await claimNonce(deviceId, nonce))) {
            return json(res, 401, { error: "nonce_reused_or_invalid" });
        }
        const accountId = cred.accountId;
        const accountRef = db.collection("clientAccounts").doc(accountId);
        // ── GET whitelist ───────────────────────────────────────────────────
        if (action === "whitelist" && req.method === "GET") {
            const accountSnap = await accountRef.get();
            if (!accountSnap.exists || accountSnap.data()?.enabled === false) {
                return json(res, 403, { error: "account_disabled" });
            }
            const version = Number(accountSnap.data()?.whitelistVersion || 0);
            const siteName = String(accountSnap.data()?.siteName || "your property");
            const gateSim = String(accountSnap.data()?.gsmNumber || accountSnap.data()?.gsmNumberE164 || "");
            const callersSnap = await accountRef.collection("gsmCallers").get();
            const now = Date.now();
            // Always emit canonical E.164 (+614…). ESP rejects non-+ numbers.
            // Bad/legacy rows are dropped here so a bad phone never bricks the list.
            const callers = callersSnap.docs
                .map((d) => {
                const data = d.data();
                return { id: d.id, data };
            })
                .filter((c) => isCallerActive({ id: c.id, ...c.data }, now))
                .map((c) => {
                const e164 = normalizeAustralianMobile(String(c.data.phoneNumberE164 || c.data.phoneNumber || ""));
                if (!e164)
                    return null;
                return {
                    id: c.id,
                    name: String(c.data.displayName || c.data.name || ""),
                    phoneNumberE164: e164,
                    enabled: true,
                    validFrom: c.data.validFrom?.toDate?.()?.toISOString?.() ?? null,
                    validUntil: c.data.validUntil?.toDate?.()?.toISOString?.() ?? null,
                };
            })
                .filter(Boolean);
            const whitelistChecksum = crypto
                .createHash("sha256")
                .update(JSON.stringify(callers))
                .digest("hex");
            // SMS jobs optional — never fail the whitelist if queue/index missing
            let smsJobs = [];
            try {
                const all = await db
                    .collection("gsmSmsQueue")
                    .where("accountId", "==", accountId)
                    .limit(50)
                    .get();
                const eligibleJobs = all.docs
                    .map((d) => {
                    const x = d.data();
                    const claimedAtMs = x.claimedAt?.toMillis?.() ?? 0;
                    const staleSending = x.status === "sending" &&
                        (claimedAtMs === 0 || now - claimedAtMs >= SMS_CLAIM_LEASE_MS);
                    const nextAttemptAtMs = x.nextAttemptAt?.toMillis?.() ?? 0;
                    if (x.deviceId !== deviceId ||
                        (x.status !== "queued" && !staleSending) ||
                        nextAttemptAtMs > now ||
                        Number(x.attemptCount || 0) >= SMS_MAX_ATTEMPTS) {
                        return null;
                    }
                    return {
                        jobId: d.id,
                        callerId: String(x.callerId || ""),
                        phoneNumberE164: String(x.phoneNumberE164 || ""),
                        message: String(x.message || ""),
                        status: String(x.status || "queued"),
                        attemptCount: Number(x.attemptCount || 0),
                        partIndex: Number(x.partIndex || 0),
                        partCount: Number(x.partCount || 1),
                    };
                })
                    .filter(Boolean);
                eligibleJobs.sort((a, b) => String(a.callerId).localeCompare(String(b.callerId)) ||
                    Number(a.partIndex) - Number(b.partIndex));
                const seenCampaigns = new Set();
                smsJobs = eligibleJobs.filter((job) => {
                    const key = String(job.callerId || job.jobId);
                    if (seenCampaigns.has(key))
                        return false;
                    seenCampaigns.add(key);
                    return true;
                });
            }
            catch (e) {
                console.warn("smsJobs skip:", e?.message || e);
            }
            let remoteCommands = [];
            try {
                const commands = await db
                    .collection("gsmDeviceCommands")
                    .where("accountId", "==", accountId)
                    .limit(20)
                    .get();
                remoteCommands = commands.docs.flatMap((doc) => {
                    const command = doc.data();
                    const expiresAt = command.expiresAt?.toMillis?.() ?? 0;
                    if (command.deviceId !== deviceId ||
                        command.status !== "queued" ||
                        command.type !== "remote_gate_test" ||
                        expiresAt <= now)
                        return [];
                    return [{ commandId: doc.id, type: command.type, expiresAt }];
                }).slice(0, 1);
            }
            catch (e) {
                console.warn("remoteCommands skip:", e?.message || e);
            }
            try {
                await accountRef.collection("gsmDevices").doc(deviceId).set({
                    lastSyncAttemptAt: admin.firestore.FieldValue.serverTimestamp(),
                    modemModel: "SIM7600G-H",
                }, { merge: true });
            }
            catch (e) {
                console.warn("gsmDevices touch skip:", e?.message || e);
            }
            try {
                await db.collection("clientActionLogs").add({
                    accountId,
                    userId: `device:${deviceId}`,
                    userEmail: "device",
                    action: "ESP32_WHITELIST_DOWNLOADED",
                    detail: `v=${version} callers=${callers.length} smsJobs=${smsJobs.length}`,
                    success: true,
                    timestamp: admin.firestore.FieldValue.serverTimestamp(),
                    source: "gsm_api",
                });
            }
            catch (e) {
                console.warn("action log skip:", e?.message || e);
            }
            return json(res, 200, {
                accountId,
                deviceId,
                version,
                callerCount: callers.length,
                whitelistChecksum,
                generatedAt: new Date().toISOString(),
                siteName,
                gateSimE164: gateSim,
                callers,
                smsJobs,
                remoteCommands,
            });
        }
        // ── POST whitelist-ack (after ESP32 validates and commits NVS) ──────
        if (action === "whitelist-ack" && req.method === "POST") {
            const body = req.body || {};
            const appliedVersion = Number(body.version);
            const callerCount = Number(body.callerCount);
            const checksum = String(body.whitelistChecksum || "").toLowerCase();
            if (!Number.isSafeInteger(appliedVersion) ||
                appliedVersion < 0 ||
                !Number.isSafeInteger(callerCount) ||
                callerCount < 0 ||
                callerCount > 10000 ||
                !/^[a-f0-9]{64}$/.test(checksum)) {
                return json(res, 400, { error: "invalid_whitelist_ack" });
            }
            const accountSnap = await accountRef.get();
            const cloudVersion = Number(accountSnap.data()?.whitelistVersion || 0);
            if (appliedVersion > cloudVersion) {
                return json(res, 409, {
                    error: "ack_version_ahead_of_cloud",
                    cloudVersion,
                });
            }
            await accountRef.collection("gsmDevices").doc(deviceId).set({
                whitelistVersion: appliedVersion,
                whitelistChecksum: checksum,
                whitelistCallerCount: callerCount,
                lastSyncAt: admin.firestore.FieldValue.serverTimestamp(),
                lastSeenAt: admin.firestore.FieldValue.serverTimestamp(),
                lastError: appliedVersion === cloudVersion ? null : "whitelist_version_behind",
            }, { merge: true });
            await db.collection("clientActionLogs").add({
                accountId,
                userId: `device:${deviceId}`,
                userEmail: "device",
                action: "ESP32_WHITELIST_APPLIED",
                detail: `v=${appliedVersion} cloud=${cloudVersion} callers=${callerCount} ` +
                    `checksum=${checksum.substring(0, 12)}`,
                success: appliedVersion === cloudVersion,
                timestamp: admin.firestore.FieldValue.serverTimestamp(),
                source: "gsm_api",
            });
            return json(res, 200, {
                ok: true,
                appliedVersion,
                cloudVersion,
                inSync: appliedVersion === cloudVersion,
            });
        }
        // ── POST sms-ack (job-level) ────────────────────────────────────────
        if (action === "sms-ack" && req.method === "POST") {
            const body = req.body || {};
            const results = Array.isArray(body.results) ? body.results : [];
            let updated = 0;
            for (const r of results) {
                const jobId = String(r.jobId || "");
                if (!jobId)
                    continue;
                const ok = Boolean(r.ok);
                const jobRef = db.collection("gsmSmsQueue").doc(jobId);
                const jobSnap = await jobRef.get();
                if (!jobSnap.exists)
                    continue;
                const job = jobSnap.data();
                if (job.accountId !== accountId || job.deviceId !== deviceId) {
                    continue; // never touch another account/device
                }
                const attempts = Number(job.attemptCount || 0) + 1;
                if (ok) {
                    await db.runTransaction(async (tx) => {
                        tx.set(jobRef, {
                            status: "sent",
                            attemptCount: attempts,
                            sentAt: admin.firestore.FieldValue.serverTimestamp(),
                            lastError: null,
                            modemMessageReference: r.cmgsReference != null ? String(r.cmgsReference) : null,
                        }, { merge: true });
                        const campaignKey = String(job.campaignKey || "");
                        if (job.callerId && Number(job.partIndex || 0) + 1 >= Number(job.partCount || 1)) {
                            tx.set(accountRef.collection("gsmCallers").doc(String(job.callerId)), {
                                welcomeSmsSentAt: admin.firestore.FieldValue.serverTimestamp(),
                                modemMessageReference: r.cmgsReference != null ? String(r.cmgsReference) : null,
                                welcomeCampaignKey: campaignKey || null,
                            }, { merge: true });
                        }
                    });
                }
                else {
                    const error = String(r.error || "send_failed");
                    const permanent = error === "invalid_number" || error === "malformed_number";
                    const status = permanent || attempts >= SMS_MAX_ATTEMPTS ? "failed" : "queued";
                    const retryDelay = SMS_RETRY_DELAYS_MS[Math.min(attempts - 1, SMS_RETRY_DELAYS_MS.length - 1)];
                    await jobRef.set({
                        status,
                        attemptCount: attempts,
                        lastError: error,
                        nextAttemptAt: status === "queued"
                            ? admin.firestore.Timestamp.fromMillis(Date.now() + retryDelay)
                            : null,
                    }, { merge: true });
                }
                updated += 1;
                await db.collection("clientActionLogs").add({
                    accountId,
                    userId: `device:${deviceId}`,
                    userEmail: "device",
                    action: ok ? "WELCOME_SMS_SENT" : "WELCOME_SMS_FAILED",
                    detail: `job=${jobId} to=${job.phoneNumberE164 || ""}`,
                    success: ok,
                    timestamp: admin.firestore.FieldValue.serverTimestamp(),
                    source: "gsm_api",
                });
            }
            return json(res, 200, { ok: true, updated });
        }
        // Atomically lease jobs before CMGS; stale SENDING leases can be reclaimed.
        if (action === "sms-claim" && req.method === "POST") {
            const body = req.body || {};
            const jobIds = Array.isArray(body.jobIds) ? body.jobIds : [];
            const claimedJobIds = [];
            for (const id of jobIds) {
                const jobId = String(id || "");
                if (!jobId)
                    continue;
                const jobRef = db.collection("gsmSmsQueue").doc(jobId);
                try {
                    const claimed = await db.runTransaction(async (tx) => {
                        const snap = await tx.get(jobRef);
                        if (!snap.exists)
                            return false;
                        const job = snap.data();
                        if (job.accountId !== accountId || job.deviceId !== deviceId)
                            return false;
                        const claimedAtMs = job.claimedAt?.toMillis?.() ?? 0;
                        const staleSending = job.status === "sending" &&
                            (claimedAtMs === 0 || Date.now() - claimedAtMs >= SMS_CLAIM_LEASE_MS);
                        const nextAttemptAtMs = job.nextAttemptAt?.toMillis?.() ?? 0;
                        if ((job.status !== "queued" && !staleSending) ||
                            nextAttemptAtMs > Date.now() ||
                            Number(job.attemptCount || 0) >= SMS_MAX_ATTEMPTS) {
                            return false;
                        }
                        tx.set(jobRef, {
                            status: "sending",
                            claimedAt: admin.firestore.FieldValue.serverTimestamp(),
                        }, { merge: true });
                        return true;
                    });
                    if (claimed)
                        claimedJobIds.push(jobId);
                }
                catch (e) {
                    console.warn("sms claim skip", jobId, e?.message || e);
                }
            }
            return json(res, 200, {
                ok: true,
                claimed: claimedJobIds.length,
                claimedJobIds,
                leaseMs: SMS_CLAIM_LEASE_MS,
            });
        }
        if (action === "command-claim" && req.method === "POST") {
            const commandId = String(req.body?.commandId || "");
            if (!commandId)
                return json(res, 400, { error: "command_id_required" });
            const ref = db.collection("gsmDeviceCommands").doc(commandId);
            const claimed = await db.runTransaction(async (tx) => {
                const snap = await tx.get(ref);
                if (!snap.exists)
                    return false;
                const command = snap.data();
                const expiresAt = command.expiresAt?.toMillis?.() ?? 0;
                if (command.accountId !== accountId ||
                    command.deviceId !== deviceId ||
                    command.type !== "remote_gate_test" ||
                    command.status !== "queued" ||
                    expiresAt <= Date.now())
                    return false;
                tx.set(ref, {
                    status: "claimed",
                    claimedAt: admin.firestore.FieldValue.serverTimestamp(),
                }, { merge: true });
                return true;
            });
            return json(res, 200, { ok: true, claimed, commandId });
        }
        if (action === "command-ack" && req.method === "POST") {
            const commandId = String(req.body?.commandId || "");
            const triggered = req.body?.triggered === true;
            const error = String(req.body?.error || "").slice(0, 200);
            if (!commandId)
                return json(res, 400, { error: "command_id_required" });
            const ref = db.collection("gsmDeviceCommands").doc(commandId);
            const snap = await ref.get();
            const command = snap.data();
            if (!snap.exists || command?.accountId !== accountId || command?.deviceId !== deviceId) {
                return json(res, 404, { error: "command_not_found" });
            }
            await ref.set({
                status: triggered ? "completed" : "failed",
                triggered,
                error: triggered ? null : (error || "controller_rejected"),
                completedAt: admin.firestore.FieldValue.serverTimestamp(),
            }, { merge: true });
            await db.collection("clientActionLogs").add({
                accountId,
                userId: `device:${deviceId}`,
                userEmail: "device",
                action: triggered ? "REMOTE_GATE_TEST_TRIGGERED" : "REMOTE_GATE_TEST_FAILED",
                detail: `command=${commandId}${error ? ` error=${error}` : ""}`,
                success: triggered,
                timestamp: admin.firestore.FieldValue.serverTimestamp(),
                source: "gsm_api",
            });
            return json(res, 200, { ok: true });
        }
        // ── POST events ─────────────────────────────────────────────────────
        if (action === "events" && req.method === "POST") {
            const body = req.body || {};
            const callerNumberE164 = String(body.callerNumberE164 || "WITHHELD");
            const authorised = Boolean(body.authorised);
            const relayTriggered = Boolean(body.relayTriggered);
            const rejectionReason = String(body.rejectionReason || "");
            const matchedCallerId = body.matchedCallerId ? String(body.matchedCallerId) : null;
            const matchedCallerName = body.matchedCallerName
                ? String(body.matchedCallerName)
                : null;
            const signalStrength = body.signalStrength != null ? Number(body.signalStrength) : null;
            const receivedAt = body.receivedAt
                ? admin.firestore.Timestamp.fromDate(new Date(body.receivedAt))
                : admin.firestore.FieldValue.serverTimestamp();
            const logRef = await accountRef.collection("gsmCallLogs").add({
                deviceId,
                callerNumberE164,
                matchedCallerId,
                matchedCallerName,
                authorised,
                relayTriggered,
                rejectionReason,
                receivedAt,
                uploadedAt: admin.firestore.FieldValue.serverTimestamp(),
                signalStrength,
            });
            const actionType = authorised
                ? body.relayFailed
                    ? "RELAY_FAILED"
                    : relayTriggered
                        ? "RELAY_TRIGGERED"
                        : "AUTHORISED_INCOMING_CALL"
                : "REJECTED_INCOMING_CALL";
            await db.collection("clientActionLogs").add({
                accountId,
                userId: `device:${deviceId}`,
                userEmail: "device",
                action: actionType,
                detail: `log=${logRef.id} authorised=${authorised} relay=${relayTriggered}`,
                success: authorised && relayTriggered && !body.relayFailed,
                timestamp: admin.firestore.FieldValue.serverTimestamp(),
                source: "gsm_api",
            });
            return json(res, 200, { ok: true, logId: logRef.id });
        }
        // ── POST heartbeat ──────────────────────────────────────────────────
        if (action === "heartbeat" && req.method === "POST") {
            const body = req.body || {};
            const rawLocation = body.location || {};
            const latitude = Number(rawLocation.latitude);
            const longitude = Number(rawLocation.longitude);
            const validLocation = Number.isFinite(latitude) &&
                Number.isFinite(longitude) &&
                latitude >= -90 &&
                latitude <= 90 &&
                longitude >= -180 &&
                longitude <= 180;
            const payload = {
                deviceName: body.deviceName || deviceId,
                enabled: true,
                firmwareVersion: String(body.firmwareVersion || ""),
                modemModel: String(body.modemModel || "SIM7600G-H"),
                signalStrength: body.signalStrength != null ? Number(body.signalStrength) : null,
                networkRegistered: Boolean(body.networkRegistered),
                operator: body.operator != null ? String(body.operator).substring(0, 80) : null,
                radioTechnology: body.radioTechnology != null
                    ? String(body.radioTechnology).substring(0, 120)
                    : null,
                whitelistVersion: Number(body.whitelistVersion || 0),
                whitelistChecksum: typeof body.whitelistChecksum === "string"
                    ? String(body.whitelistChecksum).toLowerCase()
                    : null,
                whitelistCallerCount: body.whitelistCallerCount != null
                    ? Number(body.whitelistCallerCount)
                    : null,
                lastError: body.lastError != null ? String(body.lastError) : null,
                lastSeenAt: admin.firestore.FieldValue.serverTimestamp(),
                uptimeSec: body.uptimeSec != null ? Number(body.uptimeSec) : null,
                ...(validLocation
                    ? {
                        lastLocation: new admin.firestore.GeoPoint(latitude, longitude),
                        gnssAltitudeMetres: rawLocation.altitudeMetres != null
                            ? Number(rawLocation.altitudeMetres)
                            : null,
                        gnssSpeedKnots: rawLocation.speedKnots != null
                            ? Number(rawLocation.speedKnots)
                            : null,
                        gnssHeadingDegrees: rawLocation.headingDegrees != null
                            ? Number(rawLocation.headingDegrees)
                            : null,
                        gnssCapturedAt: Number(rawLocation.capturedAtEpoch) > 0
                            ? admin.firestore.Timestamp.fromMillis(Number(rawLocation.capturedAtEpoch) * 1000)
                            : admin.firestore.FieldValue.serverTimestamp(),
                        gnssSource: String(rawLocation.source || "SIM7600_GNSS"),
                    }
                    : {}),
            };
            await accountRef.collection("gsmDevices").doc(deviceId).set(payload, {
                merge: true,
            });
            const accountSnap = await accountRef.get();
            return json(res, 200, {
                ok: true,
                serverTime: new Date().toISOString(),
                accountWhitelistVersion: Number(accountSnap.data()?.whitelistVersion || 0),
            });
        }
        return json(res, 404, { error: "not_found" });
    }
    catch (e) {
        const msg = String(e?.message || e);
        const code = e?.code;
        console.error("gsmDeviceApi error", code, msg, e?.stack || "");
        return json(res, 500, {
            error: "internal",
            code: code ?? null,
            message: msg,
            firestoreDatabase: FIRESTORE_DATABASE_ID,
            hint: code === 5 || /NOT_FOUND/i.test(msg)
                ? "Firestore database not found — create (default) DB or set FIRESTORE_DATABASE_ID"
                : "Check Cloud Function logs",
        });
    }
});
/**
 * The only welcome-SMS producer. Firestore invokes this once for a genuinely
 * new caller document; updates, whitelist polls, calls and device reconnects
 * cannot enter this path. The deterministic job document makes trigger retries
 * and repeated submissions of the same caller idempotent.
 */
exports.queueWelcomeSmsOnCallerCreated = (0, firestore_2.onDocumentCreated)({
    document: "clientAccounts/{accountId}/gsmCallers/{callerId}",
    database: FIRESTORE_DATABASE_ID,
    region: "australia-southeast2",
}, async (event) => {
    const caller = event.data?.data();
    if (!caller || caller.enabled === false)
        return;
    const accountId = String(event.params.accountId);
    const callerId = String(event.params.callerId);
    const displayName = String(caller.displayName || caller.name || "").trim();
    const phoneNumberE164 = normalizeAustralianMobile(String(caller.phoneNumberE164 || caller.phoneNumber || ""));
    if (!displayName ||
        !phoneNumberE164 ||
        phoneNumberE164 === NEVER_RESEND_NUMBER ||
        !isCallerActive({ ...caller, phoneNumberE164 }, Date.now()))
        return;
    const accountSnap = await db.collection("clientAccounts").doc(accountId).get();
    const account = accountSnap.data() || {};
    const siteText = [
        account.siteName, account.name, account.address, account.siteAddress,
        account.propertyName, account.location,
    ].join(" ").toLowerCase();
    if (!siteText.includes("thomastown"))
        return;
    const credentials = await db
        .collection("gsmDeviceCredentials")
        .where("accountId", "==", accountId)
        .get();
    const deviceId = credentials.docs
        .filter((doc) => doc.data().enabled !== false)
        .map((doc) => doc.id)
        .sort()[0];
    if (!deviceId) {
        console.error("welcome SMS not queued: no enabled device", accountId, callerId);
        return;
    }
    // Number-based deterministic IDs prevent a deleted/recreated caller
    // document from receiving the welcome campaign more than once.
    const campaignKey = crypto.createHash("sha256")
        .update(`${WELCOME_CAMPAIGN_ID}:${phoneNumberE164}`).digest("hex");
    const callerRef = db
        .collection("clientAccounts").doc(accountId)
        .collection("gsmCallers").doc(callerId);
    const parts = buildWelcomeSmsParts(displayName);
    if (parts.length !== 1)
        return;
    await db.runTransaction(async (tx) => {
        const callerSnap = await tx.get(callerRef);
        if (!callerSnap.exists)
            return;
        const dedupKey = campaignKey;
        const jobRef = db.collection("gsmSmsQueue").doc(dedupKey);
        const jobSnap = await tx.get(jobRef);
        if (!jobSnap.exists) {
            tx.set(jobRef, {
                accountId, deviceId, callerId, phoneNumberE164,
                displayName, message: parts[0],
                eventType: WELCOME_CAMPAIGN_ID, campaignId: WELCOME_CAMPAIGN_ID,
                campaignKey, dedupKey,
                partIndex: 0, partCount: 1,
                status: "queued", attemptCount: 0,
                createdAt: admin.firestore.FieldValue.serverTimestamp(),
                sentAt: null, lastError: null, nextAttemptAt: null,
            });
        }
        tx.set(callerRef, {
            phoneNumberE164,
            welcomeSmsQueuedAt: admin.firestore.FieldValue.serverTimestamp(),
            welcomeCampaignKey: campaignKey,
            welcomePartCount: parts.length,
        }, { merge: true });
    });
});
/**
 * Credential algorithm — MUST match firmware provisioning and any admin tool:
 *
 *   secretHash = SHA256( secret + ":" + deviceId )
 *
 * Do NOT use SHA256(secret), SHA256(deviceId + ":" + secret), or accountId.
 * Store secret only in ESP32 NVS; store only secretHash in Firestore.
 */
async function provisionDeviceCredential(params) {
    const secretHash = crypto
        .createHash("sha256")
        .update(`${params.secret}:${params.deviceId}`)
        .digest("hex");
    await db.collection("gsmDeviceCredentials").doc(params.deviceId).set({
        accountId: params.accountId,
        secretHash,
        enabled: true,
        secretVersion: params.secretVersion || 1,
        rotatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, { merge: true });
}
//# sourceMappingURL=index.js.map