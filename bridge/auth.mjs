import { randomBytes, scrypt, scryptSync, createHash, timingSafeEqual } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync, statSync, existsSync } from 'node:fs';
import { dirname } from 'node:path';
import { promisify } from 'node:util';

const derive = promisify(scrypt);
const parameters = { N: 32768, r: 8, p: 1, maxmem: 64 * 1024 * 1024 };
const digest = value => createHash('sha256').update(value).digest('hex');
const failure = (status, message) => Object.assign(Error(message), { status });

export function privateJson(path) {
  const stat = statSync(path);
  if (!stat.isFile() || (stat.mode & 0o077) !== 0 || stat.uid !== process.getuid()) {
    throw Error('Login files must be owned by this user with mode 0600');
  }
  return JSON.parse(readFileSync(path, 'utf8'));
}

export function passwordAccount(username, password) {
  if (typeof username !== 'string' || !/^[A-Za-z0-9._-]{1,64}$/.test(username)) throw Error('Use a username with 1–64 letters, numbers, dots, underscores or hyphens');
  if (typeof password !== 'string' || password.length < 12 || Buffer.byteLength(password) > 1024) throw Error('Use a password of at least 12 characters and at most 1024 bytes');
  const salt = randomBytes(16).toString('hex');
  return { version: 1, username, salt, passwordHash: scryptSync(password, salt, 64, parameters).toString('hex') };
}

export function loginAccount(accountFile, initialLoginFile, username) {
  if (!existsSync(accountFile)) {
    mkdirSync(dirname(accountFile), { recursive: true, mode: 0o700 });
    mkdirSync(dirname(initialLoginFile), { recursive: true, mode: 0o700 });
    // Preserve a partially provisioned login instead of silently changing its password.
    let initial;
    if (existsSync(initialLoginFile)) initial = privateJson(initialLoginFile);
    else {
      initial = { username, password: randomBytes(24).toString('base64url') };
      writeFileSync(initialLoginFile, JSON.stringify(initial, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    }
    const account = passwordAccount(initial.username, initial.password);
    writeFileSync(accountFile, JSON.stringify(account, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  }
  const account = privateJson(accountFile);
  if (account.version !== 1 || typeof account.username !== 'string' || !/^[A-Za-z0-9._-]{1,64}$/.test(account.username) ||
      !/^[a-f0-9]{32}$/.test(account.salt) || !/^[a-f0-9]{128}$/.test(account.passwordHash)) throw Error('Invalid login account file');
  return account;
}

export function createAuthentication(account, { now = Date.now, sessionLifetimeMs = 30 * 24 * 60 * 60 * 1000,
  failureWindowMs = 10 * 60 * 1000, maxFailures = 10, maxSessions = 32 } = {}) {
  const sessions = new Map(), failures = new Map();
  let checking = 0;
  function expire() {
    for (const [key, session] of sessions) if (session.expiresAt <= now()) revoke(key);
    for (const [source, attempt] of failures) if (attempt.until <= now()) failures.delete(source);
  }
  function revoke(key) {
    const session = sessions.get(key);
    sessions.delete(key);
    for (const close of session?.connections ?? []) close();
  }
  function authenticate(req) {
    if (req.headers.origin) return null;
    const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization ?? '');
    if (!match) return null;
    const key = digest(match[1]), session = sessions.get(key);
    if (!session) return null;
    if (session.expiresAt <= now()) { revoke(key); return null; }
    return { key, ...session };
  }
  return {
    authenticate, expire,
    async login(body, source) {
      expire();
      // A proxy address intentionally shares one limit; forwarded headers cannot bypass it.
      const attempt = failures.get(source);
      if (attempt?.count >= maxFailures || checking >= 2) throw failure(429, 'Too many login attempts. Try again later.');
      if (!body || typeof body.username !== 'string' || typeof body.password !== 'string' ||
          body.username.length > 64 || Buffer.byteLength(body.password) > 1024) throw failure(400, 'Enter a username and password');
      checking++;
      let valid;
      try {
        const candidate = await derive(body.password, account.salt, 64, parameters);
        valid = timingSafeEqual(Buffer.from(account.passwordHash, 'hex'), candidate) && body.username === account.username;
      } finally { checking--; }
      if (!valid) {
        const previous = failures.get(source);
        failures.set(source, { count: (previous?.count ?? 0) + 1, until: previous?.until ?? now() + failureWindowMs });
        throw failure(401, 'Username or password was rejected');
      }
      failures.delete(source);
      expire();
      if (sessions.size >= maxSessions) revoke(sessions.keys().next().value);
      const token = randomBytes(32).toString('base64url'), expiresAt = now() + sessionLifetimeMs;
      sessions.set(digest(token), { username: account.username, expiresAt, connections: new Set() });
      return { token, username: account.username, expiresAt };
    },
    track(req, close) {
      const session = authenticate(req);
      if (!session) { close(); return () => {}; }
      session.connections.add(close);
      return () => session.connections.delete(close);
    },
    logout(req) { const session = authenticate(req); if (session) revoke(session.key); },
    close() { for (const key of sessions.keys()) revoke(key); },
  };
}
