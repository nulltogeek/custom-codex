import { randomBytes, randomUUID, scrypt, timingSafeEqual, createHash } from 'node:crypto';
import { existsSync, mkdirSync, renameSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { promisify } from 'node:util';
import { loginAccount, privateJson, savePasswordAccount } from './auth.mjs';

const derive = promisify(scrypt);
const parameters = { N: 32768, r: 8, p: 1, maxmem: 64 * 1024 * 1024 };
const digest = value => createHash('sha256').update(value).digest('hex');
const failure = (status, message) => Object.assign(Error(message), { status });
const normalize = value => typeof value === 'string' ? value.toLowerCase() : '';
const publicUser = ({ id, username, role, disabled, createdAt, accountIdentityVersion }) => ({ id, username, role, disabled, createdAt,
  ...(accountIdentityVersion === 2 ? { accountIdentityVersion: 2 } : {}) });
const validPassword = value => typeof value === 'string' && value.length >= 12 && Buffer.byteLength(value) <= 1024;
async function passwordAccount(username, password) {
  if (!/^[a-z0-9._-]{1,64}$/.test(username)) throw failure(400, 'Use 1–64 letters, numbers, dots, underscores or hyphens for your username');
  if (!validPassword(password)) throw failure(400, 'Use a password of at least 12 characters and at most 1024 bytes');
  const salt = randomBytes(16).toString('hex');
  return { version: 1, username, salt, passwordHash: (await derive(password, salt, 64, parameters)).toString('hex') };
}

/** Password accounts are private host data. The legacy owner hash remains in account.json. */
export function createUserAuthentication({ accountFile, initialLoginFile, username, usersFile,
  now = Date.now, sessionLifetimeMs = 30 * 86400000, failureWindowMs = 600000, maxFailures = 10,
  maxSessions = 32, maxUsers = 100, onUserDisabled = () => {}, onUserDeleted = async () => {} }) {
  const ownerAccount = loginAccount(accountFile, initialLoginFile, username);
  let registry;
  if (existsSync(usersFile)) registry = privateJson(usersFile);
  else registry = { version: 1, signupEnabled: true, users: [{ id: 'owner', username: ownerAccount.username,
    role: 'admin', disabled: false, createdAt: now() }] };
  if (registry.version !== 1 || typeof registry.signupEnabled !== 'boolean' || !Array.isArray(registry.users) ||
      !registry.users.length || registry.users.length > maxUsers) throw Error('Invalid user registry');
  registry.deletedUsernames ??= [];
  if (!Array.isArray(registry.deletedUsernames) || registry.deletedUsernames.some(name => typeof name !== 'string' || !/^[a-z0-9._-]{1,64}$/.test(name)) ||
      new Set(registry.deletedUsernames).size !== registry.deletedUsernames.length) throw Error('Invalid deleted username registry');
  const users = new Map(), names = new Set(registry.deletedUsernames);
  for (const stored of registry.users) {
    const owner = stored.id === 'owner';
    if ((!owner && !/^[0-9a-f-]{36}$/.test(stored.id)) || typeof stored.username !== 'string' ||
        !/^[A-Za-z0-9._-]{1,64}$/.test(stored.username) || names.has(normalize(stored.username)) || users.has(stored.id) ||
        stored.role !== (owner ? 'admin' : 'user') || typeof stored.disabled !== 'boolean' ||
        !Number.isFinite(stored.createdAt) || (stored.accountIdentityVersion !== undefined && stored.accountIdentityVersion !== 2) ||
        (owner && (stored.username !== ownerAccount.username || stored.disabled)) ||
        (!owner && (!/^[a-f0-9]{32}$/.test(stored.account?.salt) || !/^[a-f0-9]{128}$/.test(stored.account?.passwordHash))))
      throw Error('Invalid user registry');
    const user = { ...publicUser(stored), account: owner ? ownerAccount : stored.account, revision: 0 };
    users.set(user.id, user); names.add(normalize(user.username));
  }
  if (!users.has('owner')) throw Error('Owner account missing from user registry');
  function persist(nextUsers = users, signupEnabled = registry.signupEnabled, deletedUsernames = registry.deletedUsernames) {
    const data = { version: 1, signupEnabled, deletedUsernames, users: [...nextUsers.values()].map(user => ({ ...publicUser(user),
      ...(user.id === 'owner' ? {} : { account: user.account }) })) };
    mkdirSync(dirname(usersFile), { recursive: true, mode: 0o700 });
    const temporary = usersFile + '.new-' + randomBytes(12).toString('hex');
    writeFileSync(temporary, JSON.stringify(data, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    renameSync(temporary, usersFile); registry.signupEnabled = signupEnabled; registry.deletedUsernames = deletedUsernames;
  }
  if (!existsSync(usersFile)) persist();
  const sessions = new Map(), failures = new Map(), sourceFailures = new Map(), signupAttempts = new Map(), changing = new Set();
  let checking = 0, creating = 0;
  async function expensive(task) {
    if (checking >= 2) throw failure(429, 'Too many account requests. Try again later.');
    checking++; try { return await task(); } finally { checking--; }
  }
  const newAccount = (username, password) => expensive(() => passwordAccount(username, password));
  function revoke(key) {
    const session = sessions.get(key); sessions.delete(key);
    for (const close of session?.connections ?? []) close();
  }
  function revokeUser(id, except) { for (const [key, session] of sessions) if (session.userId === id && key !== except) revoke(key); }
  function expire() {
    for (const [key, session] of sessions) if (session.expiresAt <= now()) revoke(key);
    for (const map of [failures, sourceFailures, signupAttempts]) for (const [key, attempt] of map) if (attempt.until <= now()) map.delete(key);
  }
  function authenticate(req) {
    if (req.headers.origin) return null;
    const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization ?? '');
    if (!match) return null;
    const key = digest(match[1]), session = sessions.get(key), user = users.get(session?.userId);
    if (!session || !user || user.disabled || session.expiresAt <= now()) { if (session) revoke(key); return null; }
    return { key, ...session, username: user.username, user: publicUser(user) };
  }
  function mint(user) {
    expire();
    if (user.disabled || users.get(user.id) !== user) throw failure(401, 'Username or password was rejected');
    const own = [...sessions].filter(([, session]) => session.userId === user.id);
    if (own.length >= maxSessions) revoke(own[0][0]);
    const token = randomBytes(32).toString('base64url'), expiresAt = now() + sessionLifetimeMs;
    sessions.set(digest(token), { userId: user.id, expiresAt, connections: new Set() });
    return { token, username: user.username, expiresAt, user: publicUser(user) };
  }
  async function verify(username, password, source, rejectedStatus = 401) {
    expire();
    const target = source + '\n' + normalize(username);
    if (failures.get(target)?.count >= maxFailures || sourceFailures.get(source)?.count >= maxFailures * 4 || checking >= 2) throw failure(429, 'Too many login attempts. Try again later.');
    if (typeof username !== 'string' || typeof password !== 'string' || username.length > 64 || Buffer.byteLength(password) > 1024)
      throw failure(400, 'Enter a username and password');
    const user = [...users.values()].find(u => normalize(u.username) === normalize(username));
    const account = user?.account ?? ownerAccount, revision = user?.revision;
    const valid = await expensive(async () => timingSafeEqual(Buffer.from(account.passwordHash, 'hex'), await derive(password, account.salt, 64, parameters)));
    if (user && revision !== user.revision) throw failure(409, 'Account changed. Try again.');
    if (!user || user.disabled || !valid) {
      for (const [map, key] of [[failures, target], [sourceFailures, source]]) {
        const previous = map.get(key);
        map.set(key, { count: (previous?.count ?? 0) + 1, until: previous?.until ?? now() + failureWindowMs });
      }
      throw failure(rejectedStatus, rejectedStatus === 403 ? 'Current password was rejected' : 'Username or password was rejected');
    }
    failures.delete(target); return user;
  }
  function admin(req) {
    const session = authenticate(req);
    if (!session) throw failure(401, 'Authentication required');
    if (session.user.role !== 'admin') throw failure(403, 'Administrator access required');
    return session;
  }
  async function create(body, source, isAdmin, req, clientIdentity) {
    expire();
    if (!isAdmin && !registry.signupEnabled) throw failure(403, 'Signup is currently closed. Contact the administrator.');
    const attempt = signupAttempts.get(source);
    if (attempt?.count >= 5 || creating >= 2) throw failure(429, 'Too many signup attempts. Try again later.');
    signupAttempts.set(source, { count: (attempt?.count ?? 0) + 1, until: attempt?.until ?? now() + failureWindowMs });
    if (!body || Object.keys(body).some(key => !['username', 'password'].includes(key))) throw failure(400, 'Enter a username and password');
    const username = normalize(body.username);
    const checkName = () => {
      const deleted = registry.deletedUsernames.includes(username);
      if ((names.has(username) && !deleted) || username === 'nulltogeek' || username === 'owner') throw failure(409, 'This username is unavailable');
      if (deleted && clientIdentity !== '2') throw failure(409, 'Update Codex Remote to recreate this deleted username with a fresh account.');
      return deleted;
    };
    checkName();
    if (users.size + creating >= maxUsers) throw failure(409, 'User limit reached. Contact the administrator.');
    creating++;
    try {
      const account = await newAccount(username, body.password);
      if (isAdmin) admin(req);
      else if (!registry.signupEnabled) throw failure(403, 'Signup is currently closed. Contact the administrator.');
      const reused = checkName();
      const user = { id: randomUUID(), username, role: 'user', disabled: false, createdAt: now(), account, revision: 0,
        ...(reused ? { accountIdentityVersion: 2 } : {}) };
      persist(new Map([...users, [user.id, user]]), registry.signupEnabled,
        registry.deletedUsernames.filter(name => name !== username)); users.set(user.id, user); names.add(username);
      return isAdmin ? { user: publicUser(user) } : mint(user);
    } finally { creating--; }
  }
  return {
    authenticate, expire, canChangePassword: true, get signupEnabled() { return registry.signupEnabled; },
    user: id => users.has(id) ? publicUser(users.get(id)) : null,
    login: async (body, source, clientIdentity) => {
      const user = await verify(body?.username, body?.password, source);
      if (user.accountIdentityVersion === 2 && clientIdentity !== '2') throw failure(426, 'Update Codex Remote before signing in to this recreated account.');
      return mint(user);
    },
    signup: (body, source, clientIdentity) => create(body, source, false, undefined, clientIdentity),
    listUsers(req) { admin(req); return { users: [...users.values()].map(publicUser), signupEnabled: registry.signupEnabled }; },
    async createUser(req, body, source) { admin(req); return create(body, source, true, req, req.headers['x-codex-account-identity']); },
    updateSettings(req, body) {
      admin(req);
      if (typeof body?.signupEnabled !== 'boolean' || Object.keys(body).some(key => key !== 'signupEnabled')) throw failure(400, 'Choose whether signup is enabled');
      persist(users, body.signupEnabled); return { signupEnabled: registry.signupEnabled };
    },
    async updateUser(req, id, body) {
      admin(req);
      const user = users.get(id);
      if (!user) throw failure(404, 'User not found');
      if (!body || !Object.keys(body).length || Object.keys(body).some(key => !['disabled', 'password'].includes(key)) ||
          (Object.hasOwn(body, 'disabled') && typeof body.disabled !== 'boolean')) throw failure(400, 'Choose a user status or a new password');
      if (user.id === 'owner') throw failure(400, 'Use Change password for your administrator account. The administrator cannot be disabled.');
      if (changing.has(id)) throw failure(409, 'Account is being updated. Try again.');
      changing.add(id);
      try {
        const account = Object.hasOwn(body, 'password') ? await newAccount(user.username, body.password) : user.account;
        admin(req);
        const next = { ...user, account, disabled: body.disabled ?? user.disabled, revision: user.revision + 1 };
        persist(new Map([...users, [id, next]])); Object.assign(user, next);
        if (body.disabled === true || Object.hasOwn(body, 'password')) revokeUser(id);
        if (body.disabled === true) onUserDisabled(id);
        return { user: publicUser(user) };
      } finally { changing.delete(id); }
    },
    async deleteUser(req, id) {
      admin(req);
      const user = users.get(id);
      if (!user) throw failure(404, 'User not found');
      if (id === 'owner') throw failure(400, 'The administrator account cannot be deleted.');
      if (changing.has(id)) throw failure(409, 'Account is being updated. Try again.');
      const remaining = new Map(users); remaining.delete(id);
      // Older phones cannot reuse names; identity-aware clients clear cached
      // data when a new server user ID replaces the deleted identity.
      persist(remaining, registry.signupEnabled, [...registry.deletedUsernames, normalize(user.username)]);
      user.disabled = true; user.revision++;
      users.delete(id);
      revokeUser(id);
      await onUserDeleted(id);
      return { ok: true };
    },
    revokeSessions(req, id) { admin(req); if (!users.has(id)) throw failure(404, 'User not found'); revokeUser(id); return { ok: true }; },
    async changePassword(req, body, source) {
      const session = authenticate(req);
      if (!session) throw failure(401, 'Authentication required');
      if (!validPassword(body?.newPassword)) throw failure(400, 'Use a password of at least 12 characters and at most 1024 bytes');
      if (body.newPassword === body.currentPassword) throw failure(400, 'Choose a different password');
      const id = session.userId;
      if (changing.has(id)) throw failure(409, 'A password change is already in progress. Try again.');
      changing.add(id);
      try {
        const user = await verify(session.username, body.currentPassword, source, 403);
        const account = await newAccount(normalize(user.username), body.newPassword);
        account.username = user.username;
        if (!authenticate(req)) throw failure(401, 'Authentication required');
        if (id === 'owner') savePasswordAccount(accountFile, account);
        else persist(new Map([...users, [id, { ...user, account }]]));
        user.account = account; user.revision++; revokeUser(id, session.key); return { ok: true };
      } finally { changing.delete(id); }
    },
    track(req, close) { const session = authenticate(req); if (!session) { close(); return () => {}; } session.connections.add(close); return () => session.connections.delete(close); },
    logout(req) { const session = authenticate(req); if (session) revoke(session.key); },
    close() { for (const key of sessions.keys()) revoke(key); },
  };
}
