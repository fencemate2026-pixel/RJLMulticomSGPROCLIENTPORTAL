import 'dotenv/config';
import crypto from 'node:crypto';
import express from 'express';
import {
  createAccessKey,
  verifyAccessKey,
} from './services/accessKeys.js';

const app = express();
const port = Number(process.env.PORT || 3000);
const adminApiKey = process.env.ADMIN_API_KEY || '';

app.use(express.json({ limit: '20kb' }));

function secureEqual(left, right) {
  const leftBuffer = Buffer.from(String(left));
  const rightBuffer = Buffer.from(String(right));

  if (
    leftBuffer.length === 0 ||
    leftBuffer.length !== rightBuffer.length
  ) {
    return false;
  }

  return crypto.timingSafeEqual(leftBuffer, rightBuffer);
}

function requireAdmin(req, res, next) {
  const providedKey = req.get('x-admin-key') || '';

  if (!secureEqual(providedKey, adminApiKey)) {
    return res.status(401).json({
      success: false,
      error: 'admin_authorisation_required',
    });
  }

  next();
}

const attempts = new Map();
const windowMs = 15 * 60 * 1000;
const maximumAttempts = 10;

function unlockRateLimit(req, res, next) {
  const identifier = req.ip || req.socket.remoteAddress || 'unknown';
  const now = Date.now();
  const existing = attempts.get(identifier);

  if (!existing || now >= existing.resetAt) {
    attempts.set(identifier, {
      count: 1,
      resetAt: now + windowMs,
    });

    return next();
  }

  existing.count += 1;

  if (existing.count > maximumAttempts) {
    return res.status(429).json({
      success: false,
      error: 'too_many_attempts',
    });
  }

  next();
}

app.get('/health', (req, res) => {
  res.json({
    success: true,
    service: 'RJL Magic Keys',
  });
});

app.post(
  '/api/access-keys/create',
  requireAdmin,
  async (req, res) => {
    try {
      const result = await createAccessKey(req.body);
      return res.status(201).json({
        success: true,
        ...result,
      });
    } catch (error) {
      console.error(error);

      return res.status(500).json({
        success: false,
        error: error.message,
      });
    }
  }
);

app.post(
  '/api/access-keys/unlock',
  unlockRateLimit,
  async (req, res) => {
    try {
      const { secret, requiredScope = 'open_gate' } = req.body;
      const result = await verifyAccessKey(
        secret,
        requiredScope
      );

      if (!result.valid) {
        return res.status(401).json({
          success: false,
          error: result.reason,
        });
      }

      return res.json({
        success: true,
        message: 'Access granted',
        tenantId: result.tenantId,
        label: result.label,
        scopes: result.scopes,
      });
    } catch (error) {
      console.error(error);

      return res.status(500).json({
        success: false,
        error: 'server_error',
      });
    }
  }
);

app.listen(port, () => {
  console.log(`RJL Magic Keys running at http://localhost:${port}`);
});
