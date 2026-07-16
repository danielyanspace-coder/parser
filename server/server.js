'use strict';

/*
 * Zero-dependency license server for the Message Sender Android app.
 *
 * - Stores tokens (with comments, enabled flag) and per-device usage in a JSON
 *   file (data/db.json).
 * - POST /api/validate: if the token exists and is enabled, returns a short-lived
 *   lease signed with the server's ECDSA P-256 private key. The app verifies the
 *   signature with the embedded public key, so leases cannot be forged, and it
 *   stops working when the lease expires and cannot be refreshed (revocation).
 * - /admin: password-protected panel to create tokens, see all tokens with
 *   comments and device counts, and enable/disable (revoke) them.
 *
 * Run:  ADMIN_PASSWORD=secret node server.js
 */

const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const PORT = parseInt(process.env.PORT || '8080', 10);
const ADMIN_USER = process.env.ADMIN_USER || 'admin';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || '';
const LEASE_TTL_MS = parseInt(process.env.LEASE_TTL_MS || String(24 * 60 * 60 * 1000), 10);

const DATA_DIR = path.join(__dirname, 'data');
const DB_FILE = path.join(DATA_DIR, 'db.json');
const KEYS_FILE = path.join(DATA_DIR, 'keys.json');
const APK_FILE = path.join(DATA_DIR, 'alfa-sms.apk');
const UPDATE_FILE = path.join(DATA_DIR, 'update.json');
const MAX_APK_BYTES = 150 * 1024 * 1024; // 150 MB upload cap

if (!ADMIN_PASSWORD) {
  console.error('FATAL: set ADMIN_PASSWORD environment variable before starting.');
  process.exit(1);
}

// ---------------------------------------------------------------------------
// Storage (simple JSON file; low volume, single process).
// ---------------------------------------------------------------------------

function ensureDataDir() {
  if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
}

function loadDb() {
  ensureDataDir();
  if (!fs.existsSync(DB_FILE)) return { tokens: [], devices: [] };
  try {
    return JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
  } catch (e) {
    console.error('Failed to read db.json, starting empty:', e.message);
    return { tokens: [], devices: [] };
  }
}

function saveDb(db) {
  ensureDataDir();
  const tmp = DB_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(db, null, 2));
  fs.renameSync(tmp, DB_FILE); // atomic replace
}

let db = loadDb();

// ---------------------------------------------------------------------------
// Signing keys (generated once, reused across restarts).
// ---------------------------------------------------------------------------

function keysFromPrivatePem(privateKeyPem) {
  const publicKey = crypto.createPublicKey(crypto.createPrivateKey(privateKeyPem));
  const publicKeyDerB64 = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  return { privateKeyPem, publicKeyDerB64 };
}

function loadOrCreateKeys() {
  // Preferred on hosts with an ephemeral filesystem (Render/Railway/etc.):
  // provide the signing key via an env var so it stays stable across restarts
  // and the public key embedded in the app never changes.
  if (process.env.SIGNING_PRIVATE_KEY_B64) {
    const pem = Buffer.from(process.env.SIGNING_PRIVATE_KEY_B64, 'base64').toString('utf8');
    console.log('Using signing key from SIGNING_PRIVATE_KEY_B64');
    return keysFromPrivatePem(pem);
  }

  ensureDataDir();
  if (fs.existsSync(KEYS_FILE)) {
    return JSON.parse(fs.readFileSync(KEYS_FILE, 'utf8'));
  }
  const { privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const privateKeyPem = privateKey.export({ type: 'pkcs8', format: 'pem' });
  const keys = keysFromPrivatePem(privateKeyPem);
  fs.writeFileSync(KEYS_FILE, JSON.stringify(keys, null, 2));
  console.log('Generated new signing key pair in data/keys.json');
  console.log('To keep this key stable on an ephemeral host, set this env var:');
  console.log('SIGNING_PRIVATE_KEY_B64=' + Buffer.from(privateKeyPem).toString('base64'));
  return keys;
}

const keys = loadOrCreateKeys();

function b64url(buf) {
  return Buffer.from(buf).toString('base64')
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function signLease(token, deviceId) {
  const now = Date.now();
  const payload = { token, deviceId, iat: now, exp: now + LEASE_TTL_MS };
  const payloadStr = b64url(JSON.stringify(payload));
  const signature = crypto.createSign('SHA256').update(payloadStr).sign(keys.privateKeyPem);
  return { payload: payloadStr, sig: b64url(signature), exp: payload.exp };
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function newTokenValue() {
  // 20 hex chars, grouped for readability: XXXXX-XXXXX-XXXXX-XXXXX
  const hex = crypto.randomBytes(10).toString('hex').toUpperCase();
  return hex.match(/.{1,5}/g).join('-');
}

function findToken(value) {
  return db.tokens.find((t) => t.value === value);
}

function recordDevice(tokenId, deviceId) {
  if (!deviceId) return;
  const now = Date.now();
  let d = db.devices.find((x) => x.tokenId === tokenId && x.deviceId === deviceId);
  if (d) {
    d.lastSeen = now;
    d.count = (d.count || 0) + 1;
  } else {
    db.devices.push({ tokenId, deviceId, firstSeen: now, lastSeen: now, count: 1 });
  }
  saveDb(db);
}

function deviceStats(tokenId) {
  const list = db.devices.filter((d) => d.tokenId === tokenId);
  const lastSeen = list.reduce((m, d) => Math.max(m, d.lastSeen || 0), 0);
  return { count: list.length, lastSeen };
}

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (c) => {
      data += c;
      if (data.length > 1e6) req.destroy(); // guard against oversized bodies
    });
    req.on('end', () => resolve(data));
  });
}

function readRawBody(req, maxBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > maxBytes) {
        req.destroy();
        reject(new Error('too_large'));
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function checkAdminAuth(req) {
  const header = req.headers['authorization'] || '';
  if (!header.startsWith('Basic ')) return false;
  let decoded;
  try {
    decoded = Buffer.from(header.slice(6), 'base64').toString('utf8');
  } catch (e) {
    return false;
  }
  const idx = decoded.indexOf(':');
  const user = decoded.slice(0, idx);
  const pass = decoded.slice(idx + 1);
  // Constant-time compare to avoid timing leaks.
  const ok = safeEqual(user, ADMIN_USER) & safeEqual(pass, ADMIN_PASSWORD);
  return Boolean(ok);
}

function safeEqual(a, b) {
  const ba = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  if (ba.length !== bb.length) return 0;
  return crypto.timingSafeEqual(ba, bb) ? 1 : 0;
}

function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function fmtTime(ms) {
  return ms ? new Date(ms).toISOString().replace('T', ' ').slice(0, 19) + ' UTC' : '—';
}

// ---------------------------------------------------------------------------
// Admin panel HTML
// ---------------------------------------------------------------------------

function renderAdmin() {
  const rows = db.tokens.map((t) => {
    const s = deviceStats(t.id);
    const status = t.enabled
      ? '<span style="color:#137333;font-weight:600">включён</span>'
      : '<span style="color:#c5221f;font-weight:600">выключен</span>';
    return `<tr>
      <td><code>${esc(t.value)}</code></td>
      <td>${esc(t.comment || '')}</td>
      <td>${status}</td>
      <td>${s.count}</td>
      <td>${fmtTime(s.lastSeen)}</td>
      <td>${fmtTime(t.createdAt)}</td>
      <td>
        <form method="POST" action="/admin/toggle" style="display:inline">
          <input type="hidden" name="id" value="${esc(t.id)}">
          <button type="submit">${t.enabled ? 'Выключить' : 'Включить'}</button>
        </form>
        <form method="POST" action="/admin/delete" style="display:inline"
              onsubmit="return confirm('Удалить токен безвозвратно?')">
          <input type="hidden" name="id" value="${esc(t.id)}">
          <button type="submit" style="color:#c5221f">Удалить</button>
        </form>
      </td>
    </tr>`;
  }).join('');

  return `<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Админка токенов</title>
<style>
  body { font-family: system-ui, sans-serif; margin: 24px; color: #202124; }
  h1 { font-size: 22px; }
  table { border-collapse: collapse; width: 100%; margin-top: 16px; }
  th, td { border: 1px solid #dadce0; padding: 8px 10px; text-align: left; font-size: 14px; }
  th { background: #f1f3f4; }
  code { background: #f1f3f4; padding: 2px 4px; border-radius: 4px; }
  button { cursor: pointer; padding: 4px 10px; }
  form.create { margin-top: 8px; display: flex; gap: 8px; }
  input[type=text] { padding: 6px 8px; min-width: 280px; }
  .hint { color: #5f6368; font-size: 13px; margin-top: 24px; line-height: 1.5; }
</style>
</head>
<body>
  <h1>Токены доступа</h1>
  <form class="create" method="POST" action="/admin/create">
    <input type="text" name="comment" placeholder="Комментарий (кому выдан токен)" required>
    <button type="submit">Создать токен</button>
  </form>
  <table>
    <thead>
      <tr><th>Токен</th><th>Комментарий</th><th>Статус</th><th>Устройств</th>
      <th>Последняя активность</th><th>Создан</th><th>Действия</th></tr>
    </thead>
    <tbody>${rows || '<tr><td colspan="7">Токенов пока нет</td></tr>'}</tbody>
  </table>
  <p class="hint">
    «Выключить» отзывает токен: сервер перестаёт выдавать лицензии, и все устройства
    с этим токеном отключатся в течение срока лицензии (по умолчанию до 24 часов).<br>
    Открытый ключ для встраивания в приложение: <a href="/admin/pubkey">/admin/pubkey</a>
  </p>
</body>
</html>`;
}

// ---------------------------------------------------------------------------
// HTTP routing
// ---------------------------------------------------------------------------

function requireAdmin(req, res) {
  if (checkAdminAuth(req)) return true;
  res.writeHead(401, {
    'WWW-Authenticate': 'Basic realm="admin", charset="UTF-8"',
    'Content-Type': 'text/plain; charset=utf-8',
  });
  res.end('Требуется авторизация');
  return false;
}

function parseForm(body) {
  const params = new URLSearchParams(body);
  const obj = {};
  for (const [k, v] of params) obj[k] = v;
  return obj;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathName = url.pathname;

  try {
    // --- App API ---
    if (req.method === 'POST' && pathName === '/api/validate') {
      const body = await readBody(req);
      let parsed;
      try {
        parsed = JSON.parse(body || '{}');
      } catch (e) {
        return sendJson(res, 400, { error: 'bad_json' });
      }
      const token = String(parsed.token || '').trim();
      const deviceId = String(parsed.deviceId || '').trim();
      const t = findToken(token);
      if (!t || !t.enabled) {
        return sendJson(res, 403, { error: 'invalid_or_disabled' });
      }
      recordDevice(t.id, deviceId);
      const lease = signLease(token, deviceId);
      return sendJson(res, 200, lease);
    }

    // --- Admin ---
    if (pathName === '/admin' && req.method === 'GET') {
      if (!requireAdmin(req, res)) return;
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      return res.end(renderAdmin());
    }

    if (pathName === '/admin/pubkey' && req.method === 'GET') {
      if (!requireAdmin(req, res)) return;
      res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
      return res.end(keys.publicKeyDerB64);
    }

    if (pathName === '/admin/create' && req.method === 'POST') {
      if (!requireAdmin(req, res)) return;
      const form = parseForm(await readBody(req));
      db.tokens.push({
        id: crypto.randomUUID(),
        value: newTokenValue(),
        comment: String(form.comment || '').slice(0, 200),
        enabled: true,
        createdAt: Date.now(),
      });
      saveDb(db);
      res.writeHead(302, { Location: '/admin' });
      return res.end();
    }

    if (pathName === '/admin/toggle' && req.method === 'POST') {
      if (!requireAdmin(req, res)) return;
      const form = parseForm(await readBody(req));
      const t = db.tokens.find((x) => x.id === form.id);
      if (t) { t.enabled = !t.enabled; saveDb(db); }
      res.writeHead(302, { Location: '/admin' });
      return res.end();
    }

    if (pathName === '/admin/delete' && req.method === 'POST') {
      if (!requireAdmin(req, res)) return;
      const form = parseForm(await readBody(req));
      db.tokens = db.tokens.filter((x) => x.id !== form.id);
      db.devices = db.devices.filter((x) => x.tokenId !== form.id);
      saveDb(db);
      res.writeHead(302, { Location: '/admin' });
      return res.end();
    }

    // --- App updates (public) ---
    if (pathName === '/app/version.json' && req.method === 'GET') {
      let info = { versionCode: 0, versionName: '', notes: '' };
      if (fs.existsSync(UPDATE_FILE)) {
        try { info = JSON.parse(fs.readFileSync(UPDATE_FILE, 'utf8')); } catch (e) {}
      }
      return sendJson(res, 200, info);
    }

    if (pathName === '/app/alfa-sms.apk' && req.method === 'GET') {
      if (!fs.existsSync(APK_FILE)) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        return res.end('No APK published');
      }
      const stat = fs.statSync(APK_FILE);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': stat.size,
        'Content-Disposition': 'attachment; filename="alfa-sms.apk"',
      });
      return fs.createReadStream(APK_FILE).pipe(res);
    }

    // --- Publish an update (admin) ---
    if (pathName === '/admin/apk' && req.method === 'PUT') {
      if (!requireAdmin(req, res)) return;
      let buf;
      try {
        buf = await readRawBody(req, MAX_APK_BYTES);
      } catch (e) {
        return sendJson(res, 413, { error: 'too_large' });
      }
      ensureDataDir();
      fs.writeFileSync(APK_FILE, buf);
      return sendJson(res, 200, { ok: true, bytes: buf.length });
    }

    if (pathName === '/admin/release' && req.method === 'POST') {
      if (!requireAdmin(req, res)) return;
      let parsed;
      try {
        parsed = JSON.parse((await readBody(req)) || '{}');
      } catch (e) {
        return sendJson(res, 400, { error: 'bad_json' });
      }
      const info = {
        versionCode: parseInt(parsed.versionCode, 10) || 0,
        versionName: String(parsed.versionName || ''),
        notes: String(parsed.notes || ''),
      };
      ensureDataDir();
      fs.writeFileSync(UPDATE_FILE, JSON.stringify(info, null, 2));
      return sendJson(res, 200, { ok: true, published: info });
    }

    if (pathName === '/' && req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
      return res.end('Message Sender license server. Admin panel at /admin');
    }

    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Not found');
  } catch (e) {
    console.error('Request error:', e);
    sendJson(res, 500, { error: 'server_error' });
  }
});

server.listen(PORT, () => {
  console.log(`License server listening on port ${PORT}`);
  console.log(`Admin panel: http://localhost:${PORT}/admin  (user: ${ADMIN_USER})`);
  console.log(`Embed this public key in the app (LICENSE_PUBLIC_KEY):`);
  console.log(keys.publicKeyDerB64);
});
