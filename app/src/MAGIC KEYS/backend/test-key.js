import {
  createAccessKey,
  verifyAccessKey,
} from './services/accessKeys.js';

try {
  console.log('Creating test key...');

  const created = await createAccessKey({
    tenantId: 'rjl',
    label: 'Test Key - Jarrod',
    scopes: ['open_gate'],
    expiresInDays: 30,
  });

  console.log('\nKEY CREATED SUCCESSFULLY');
  console.log('ID:', created.id);
  console.log('Magic key:', created.plainSecret);
  console.log('Expires:', created.expiresAt);

  const verified = await verifyAccessKey(
    created.plainSecret,
    'open_gate'
  );

  console.log('\nVERIFY RESULT');
  console.log(verified);

  if (!verified.valid) {
    process.exitCode = 1;
  }
} catch (error) {
  console.error('\nTEST FAILED');
  console.error(error);
  process.exitCode = 1;
}
