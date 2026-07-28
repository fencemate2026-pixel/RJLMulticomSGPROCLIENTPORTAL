import crypto from 'node:crypto';
import { supabase } from '../supabase.js';

const WORDS = [
  'GATE', 'STEEL', 'BOLT', 'FENCE', 'HINGE', 'LATCH', 'RAIL', 'POST',
  'SHIELD', 'FALCON', 'TITAN', 'RAPID', 'SECURE', 'VAULT', 'BEACON', 'GUARD'
];

const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

function randomCode(length = 16) {
  let result = '';

  for (let index = 0; index < length; index += 1) {
    result += CODE_ALPHABET[
      crypto.randomInt(0, CODE_ALPHABET.length)
    ];
  }

  return result.match(/.{1,4}/g).join('-');
}

function normalizeSecret(value) {
  return value
    .trim()
    .toUpperCase()
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-');
}

function hashSecret(secret) {
  return crypto
    .createHash('sha256')
    .update(normalizeSecret(secret), 'utf8')
    .digest('hex');
}

function generateSecret() {
  const firstWord = WORDS[crypto.randomInt(0, WORDS.length)];
  const secondWord = WORDS[crypto.randomInt(0, WORDS.length)];

  return `RJL-${firstWord}-${secondWord}-${randomCode(16)}`;
}

export async function createAccessKey({
  tenantId,
  label,
  scopes = [],
  expiresInDays = 30,
}) {
  if (!tenantId || typeof tenantId !== 'string') {
    throw new Error('tenantId is required');
  }

  const days = Number(expiresInDays);

  if (!Number.isFinite(days) || days <= 0 || days > 3650) {
    throw new Error('expiresInDays must be between 1 and 3650');
  }

  const cleanScopes = Array.isArray(scopes)
    ? scopes.filter(scope => typeof scope === 'string' && scope.trim())
    : [];

  const plainSecret = generateSecret();
  const keyHash = hashSecret(plainSecret);
  const expiresAt = new Date(
    Date.now() + days * 24 * 60 * 60 * 1000
  ).toISOString();

  const { data, error } = await supabase
    .from('access_keys')
    .insert({
      tenant_id: tenantId.trim(),
      key_hash: keyHash,
      label: typeof label === 'string' ? label.trim() : null,
      scopes: cleanScopes,
      expires_at: expiresAt,
      is_active: true,
    })
    .select('id, expires_at')
    .single();

  if (error) {
    throw new Error(`Supabase insert failed: ${error.message}`);
  }

  return {
    id: data.id,
    plainSecret,
    expiresAt: data.expires_at,
  };
}

export async function verifyAccessKey(
  plainSecret,
  requiredScope = null
) {
  if (!plainSecret || typeof plainSecret !== 'string') {
    return { valid: false, reason: 'missing' };
  }

  const keyHash = hashSecret(plainSecret);

  const { data: key, error } = await supabase
    .from('access_keys')
    .select('tenant_id, label, scopes, expires_at, is_active')
    .eq('key_hash', keyHash)
    .maybeSingle();

  if (error) {
    throw new Error(`Supabase lookup failed: ${error.message}`);
  }

  if (!key || !key.is_active) {
    return { valid: false, reason: 'not_found_or_revoked' };
  }

  if (new Date(key.expires_at).getTime() <= Date.now()) {
    return { valid: false, reason: 'expired' };
  }

  const scopes = Array.isArray(key.scopes) ? key.scopes : [];

  if (requiredScope && !scopes.includes(requiredScope)) {
    return { valid: false, reason: 'insufficient_scope' };
  }

  return {
    valid: true,
    tenantId: key.tenant_id,
    label: key.label,
    scopes,
  };
}
