import crypto from 'crypto';
import { supabase } from '../supabase.js'; // adjust path if needed

// 1. Create a new access key
export async function createAccessKey({ tenantId, label, scopes = [], expiresInDays = 30 }) {
  const words = ['steel', 'gate', 'bolt', 'mesh', 'post', 'clamp', 'wire', 'fence', 'panel', 'hinge', 'latch', 'rail'];
  const selected = Array.from({ length: 4 }, () => words[crypto.randomInt(0, words.length)]);
  const suffix = crypto.randomBytes(3).toString('hex').toUpperCase();
  const plainSecret = `FM-${selected.join('-')}-${suffix}`;

  const hash = crypto.createHash('sha256').update(plainSecret).digest('hex');

  const { data, error } = await supabase
    .from('access_keys')
    .insert({
      tenant_id: tenantId,
      key_hash: hash,
      label,
      scopes,
      expires_at: new Date(Date.now() + expiresInDays * 86400000).toISOString(),
      created_at: new Date().toISOString(),
      is_active: true,
    })
    .select()
    .single();

  if (error) throw error;

  return {
    id: data.id,
    plainSecret,          // ← only returned once
    expiresAt: data.expires_at,
  };
}

// 2. Verify a key that someone provides
export async function verifyAccessKey(plainSecret, requiredScope = null) {
  if (!plainSecret || typeof plainSecret !== 'string') {
    return { valid: false, reason: 'missing' };
  }

  const hash = crypto.createHash('sha256').update(plainSecret.trim()).digest('hex');

  const { data: key, error } = await supabase
    .from('access_keys')
    .select('*')
    .eq('key_hash', hash)
    .eq('is_active', true)
    .single();

  if (error || !key) {
    return { valid: false, reason: 'not_found' };
  }

  if (new Date(key.expires_at) < new Date()) {
    return { valid: false, reason: 'expired' };
  }

  if (requiredScope && !key.scopes.includes(requiredScope)) {
    return { valid: false, reason: 'insufficient_scope' };
  }

  return {
    valid: true,
    tenantId: key.tenant_id,
    scopes: key.scopes,
    label: key.label,
  };
}