/**
 * Regression: unlock must default requiredScope to open_gate when body sends
 * null/""/whitespace (destructuring defaults do not cover null).
 * Run: node "app/src/MAGIC KEYS/backend/test-unlock-scope.mjs"
 */

function resolveUnlockScope(body) {
  const { requiredScope } = body || {};
  return typeof requiredScope === 'string' && requiredScope.trim()
    ? requiredScope.trim()
    : 'open_gate';
}

const cases = [
  [{}, 'open_gate'],
  [{ requiredScope: undefined }, 'open_gate'],
  [{ requiredScope: null }, 'open_gate'],
  [{ requiredScope: '' }, 'open_gate'],
  [{ requiredScope: '   ' }, 'open_gate'],
  [{ requiredScope: 'open_gate' }, 'open_gate'],
  [{ requiredScope: 'admin' }, 'admin'],
];

let failed = 0;
for (const [body, expected] of cases) {
  const got = resolveUnlockScope(body);
  if (got !== expected) {
    console.error('FAIL:', body, '→', got, 'expected', expected);
    failed += 1;
  } else {
    console.log('OK:', JSON.stringify(body), '→', got);
  }
}

if (failed) process.exit(1);
console.log('\nAll unlock scope checks passed');
