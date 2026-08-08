/**
 * Host-side regression for reject/failsafe call-FSM unlock.
 * Models the flags the ESP32 sketch uses after a rejected call.
 * Run: node firmware/sgpro_gsm_controller/test_call_fsm_unlock.mjs
 */

function simulateReject({ withLockout }) {
  let ringPending = true;
  let hasHandledCall = false;
  let callLockout = false;
  let hangupPending = false;

  // Reject path (unknown / hidden / malformed / failsafe)
  hasHandledCall = true;
  if (withLockout) callLockout = true;
  hangupPending = true;

  // Hangup OK
  hangupPending = false;

  const blocksNextCaller = () =>
    hasHandledCall || hangupPending || callLockout;

  // Next authorised RING arrives before lockout ends
  const blockedDuringLockout = blocksNextCaller();

  // Lockout ends → resetIncomingCallState()
  if (callLockout) {
    callLockout = false;
    ringPending = false;
    hasHandledCall = false;
  }

  const blockedAfterLockout = blocksNextCaller();
  return { blockedDuringLockout, blockedAfterLockout, ringPending, hasHandledCall };
}

const buggy = simulateReject({ withLockout: false });
const fixed = simulateReject({ withLockout: true });

let failed = 0;
function assert(cond, msg) {
  if (!cond) {
    console.error('FAIL:', msg);
    failed += 1;
  } else {
    console.log('OK:', msg);
  }
}

assert(buggy.blockedAfterLockout === true, 'bug: reject without lockout leaves hasHandledCall stuck');
assert(fixed.blockedDuringLockout === true, 'fixed: still blocks during lockout window');
assert(fixed.blockedAfterLockout === false, 'fixed: unlocks after lockout so next caller works');
assert(fixed.hasHandledCall === false && fixed.ringPending === false, 'fixed: ring/handled flags cleared');

if (failed) {
  console.error(`\n${failed} assertion(s) failed`);
  process.exit(1);
}
console.log('\nAll call-FSM unlock checks passed');
