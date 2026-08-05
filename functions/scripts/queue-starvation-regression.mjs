/**
 * Regression: whitelist poll must not starve queued SMS / remote commands
 * behind terminal history when using accountId + limit(N).
 *
 * Run: node functions/scripts/queue-starvation-regression.mjs
 */

function oldLimitThenFilter(docs, limit, predicate) {
  return docs.slice(0, limit).filter(predicate);
}

function newStatusScoped(docs, limit, predicate) {
  return docs.filter(predicate).slice(0, limit);
}

function assert(cond, msg) {
  if (!cond) {
    console.error("FAIL:", msg);
    process.exitCode = 1;
  } else {
    console.log("OK:", msg);
  }
}

// Simulate Firestore doc-id order: 50 sent (lexicographically first) then 5 queued.
const smsDocs = [];
for (let i = 0; i < 50; i++) {
  smsDocs.push({ id: `a-sent-${String(i).padStart(3, "0")}`, status: "sent", deviceId: "dev1" });
}
for (let i = 0; i < 5; i++) {
  smsDocs.push({ id: `z-queued-${String(i).padStart(3, "0")}`, status: "queued", deviceId: "dev1" });
}
smsDocs.sort((a, b) => a.id.localeCompare(b.id));

const isEligibleSms = (d) => d.status === "queued" && d.deviceId === "dev1";
const oldSms = oldLimitThenFilter(smsDocs, 50, isEligibleSms);
const newSms = newStatusScoped(smsDocs, 50, isEligibleSms);

assert(oldSms.length === 0, "old SMS path starves: 50 sent history hides 5 queued");
assert(newSms.length === 5, "status-scoped SMS path returns all 5 queued jobs");

// Remote gate test: 20 completed then 1 new queued.
const cmdDocs = [];
for (let i = 0; i < 20; i++) {
  cmdDocs.push({
    id: `a-cmd-${String(i).padStart(3, "0")}`,
    status: "succeeded",
    deviceId: "dev1",
    type: "remote_gate_test",
    expiresAt: Date.now() + 60_000,
  });
}
cmdDocs.push({
  id: "z-cmd-new",
  status: "queued",
  deviceId: "dev1",
  type: "remote_gate_test",
  expiresAt: Date.now() + 60_000,
});
cmdDocs.sort((a, b) => a.id.localeCompare(b.id));

const isEligibleCmd = (d) =>
  d.status === "queued" &&
  d.deviceId === "dev1" &&
  d.type === "remote_gate_test" &&
  d.expiresAt > Date.now();
const oldCmd = oldLimitThenFilter(cmdDocs, 20, isEligibleCmd);
const newCmd = newStatusScoped(cmdDocs, 20, isEligibleCmd);

assert(oldCmd.length === 0, "old command path starves after 20 history docs");
assert(newCmd.length === 1, "status-scoped command path returns the new queued test");

if (process.exitCode) {
  console.error("\nRegression failed.");
  process.exit(1);
}
console.log("\nAll queue-starvation regressions passed.");
