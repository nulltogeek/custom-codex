import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, statSync, chmodSync, writeFileSync, readdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import { WebSocket } from 'ws';
import { passwordAccount, loginAccount, privateJson, createAuthentication, savePasswordAccount } from '../auth.mjs';
import { createHost } from '../host.mjs';
import { Broker } from '../broker.mjs';

const credentials = { username: 'owner', password: 'a-test-password-with-spaces ' };
const account = passwordAccount(credentials.username, credentials.password);
const requestFor = token => ({ headers: { authorization: `Bearer ${token}` } });

test('in-app password changes persist hashes and revoke other sessions without revoking this phone', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'codex-app-password-'));
  const path = join(dir, 'account.json');
  writeFileSync(path, JSON.stringify(account), { mode: 0o600 });
  const auth = createAuthentication(account, { persistAccount: next => savePasswordAccount(path, next) });
  const first = await auth.login(credentials, 'ip'), second = await auth.login(credentials, 'ip');
  let otherClosed = 0, thisClosed = 0;
  auth.track(requestFor(first.token), () => thisClosed++);
  auth.track(requestFor(second.token), () => otherClosed++);
  const nextPassword = 'new-password-with-spaces  ';
  assert.deepEqual(await auth.changePassword(requestFor(first.token), { currentPassword: credentials.password, newPassword: nextPassword }, 'ip'), { ok: true });
  assert.equal(otherClosed, 1); assert.equal(thisClosed, 0);
  assert.equal(auth.authenticate(requestFor(second.token)), null);
  assert.equal(auth.authenticate(requestFor(first.token)).username, 'owner');
  const saved = privateJson(path);
  assert.equal(saved.username, 'owner'); assert.notEqual(saved.passwordHash, account.passwordHash);
  assert.equal(statSync(path).mode & 0o777, 0o600);
  assert.equal(readFileSync(path, 'utf8').includes(nextPassword), false);
  const backups = readdirSync(dir).filter(name => name.startsWith('account.json.backup-'));
  assert.equal(backups.length, 1); assert.deepEqual(privateJson(join(dir, backups[0])), account);
  const restarted = createAuthentication(saved);
  await assert.rejects(restarted.login(credentials, 'ip'), { status: 401 });
  await assert.rejects(restarted.login({ ...credentials, password: nextPassword.trim() }, 'ip'), { status: 401 });
  assert.equal((await restarted.login({ ...credentials, password: nextPassword }, 'ip')).username, 'owner');
});

test('password validation, throttling, concurrent changes and persistence failures preserve the account', async () => {
  const auth = createAuthentication(account, { maxFailures: 2, persistAccount() { throw Error('Disk full'); } });
  const session = await auth.login(credentials, 'ip'), req = requestFor(session.token);
  const body = { currentPassword: credentials.password, newPassword: 'a-different-test-password' };
  await assert.rejects(auth.changePassword(requestFor('missing'), body, 'ip'), { status: 401 });
  await assert.rejects(auth.changePassword(req, { ...body, newPassword: 'short' }, 'ip'), { status: 400 });
  await assert.rejects(auth.changePassword(req, { ...body, newPassword: 'é'.repeat(600) }, 'ip'), { status: 400 });
  await assert.rejects(auth.changePassword(req, { ...body, newPassword: credentials.password }, 'ip'), { status: 400 });
  await assert.rejects(auth.changePassword(req, { ...body, currentPassword: 'wrong' }, 'ip'), { status: 403 });
  await assert.rejects(auth.changePassword(req, { ...body, currentPassword: 'wrong' }, 'ip'), { status: 403 });
  await assert.rejects(auth.changePassword(req, body, 'ip'), { status: 429 });
  await assert.rejects(auth.login(credentials, 'ip'), { status: 429 });
  const pendingChange = auth.changePassword(req, body, 'different-ip');
  await assert.rejects(auth.changePassword(req, body, 'different-ip'), { status: 409 });
  await assert.rejects(pendingChange, /Disk full/);
  assert.ok(auth.authenticate(req));
  assert.equal((await auth.login(credentials, 'different-ip')).username, 'owner');
});

test('password HTTP endpoint requires a session, rejects Origins, bounds bodies and closes other sockets', async () => {
  let saved;
  const auth = createAuthentication(account, { persistAccount: next => { saved = next; } });
  const host = createHost({ broker: new Broker({ upstream() {}, desktop() {} }), authentication: auth, port: 0 });
  const port = await host.listen(), base = `http://127.0.0.1:${port}`;
  const first = await auth.login(credentials, 'ip'), second = await auth.login(credentials, 'ip');
  const headers = { Authorization: `Bearer ${first.token}`, 'Content-Type': 'application/json' };
  const body = { currentPassword: credentials.password, newPassword: 'a-new-http-test-password' };
  const change = (extra = {}, payload = JSON.stringify(body)) => fetch(base + '/v1/password', { method: 'POST', headers: { ...headers, ...extra }, body: payload });
  let peer;
  try {
    assert.equal((await fetch(base + '/v1/password', { method: 'POST' })).status, 401);
    assert.equal((await change({ Origin: 'https://example.com' })).status, 401);
    assert.equal((await change({ 'Content-Type': 'text/plain' })).status, 415);
    assert.equal((await change({}, '{')).status, 400);
    assert.equal((await change({}, JSON.stringify({ ...body, newPassword: 'x'.repeat(17000) }))).status, 413);
    assert.equal((await change({}, JSON.stringify({ ...body, currentPassword: 'wrong' }))).status, 403);
    peer = new WebSocket(`ws://127.0.0.1:${port}/v1/rpc`, { headers: { Authorization: `Bearer ${second.token}` } });
    await once(peer, 'open'); const closed = once(peer, 'close');
    const result = await change(); assert.equal(result.status, 200); assert.deepEqual(await result.json(), { ok: true });
    assert.equal((await closed)[0], 1008); assert.ok(saved);
    assert.equal((await fetch(base + '/v1/host', { headers })).status, 200);
    assert.equal((await fetch(base + '/v1/host', { headers: { Authorization: `Bearer ${second.token}` } })).status, 401);
  } finally { peer?.close(); host.close(); }
});

test('session expiry during password verification leaves the previous password unchanged', async () => {
  let now = 1000;
  const auth = createAuthentication(account, { now: () => now, sessionLifetimeMs: 100, persistAccount() {} });
  const session = await auth.login(credentials, 'ip');
  const pending = auth.changePassword(requestFor(session.token), { currentPassword: credentials.password, newPassword: 'expiry-test-password-new' }, 'ip');
  now += 101;
  await assert.rejects(pending, { status: 401 });
  assert.equal((await auth.login(credentials, 'ip')).username, 'owner');
});

test('a login racing a password change cannot keep an old-password session afterward', async () => {
  const auth = createAuthentication(account, { persistAccount() {} });
  const session = await auth.login(credentials, 'ip');
  const [change, login] = await Promise.allSettled([
    auth.changePassword(requestFor(session.token), { currentPassword: credentials.password, newPassword: 'race-test-password-new' }, 'ip'),
    auth.login(credentials, 'another-ip'),
  ]);
  assert.equal(change.status, 'fulfilled');
  if (login.status === 'fulfilled') assert.equal(auth.authenticate(requestFor(login.value.token)), null);
  else assert.equal(login.reason.status, 409);
  await assert.rejects(auth.login(credentials, 'ip'), { status: 401 });
});

test('local password changes preserve the previous hash and reject the previous password', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'codex-change-login-'));
  const accountFile = join(dir, 'account.json'), initial = join(dir, 'initial.json');
  const before = loginAccount(accountFile, initial, 'owner');
  const previous = privateJson(initial), next = { username: 'owner', password: 'a-new-disposable-password' };
  writeFileSync(initial, JSON.stringify(next));
  const output = execFileSync(process.execPath, [fileURLToPath(new URL('../../scripts/set-login.mjs', import.meta.url)), initial],
    { env: { ...process.env, CODEX_LOGIN_ACCOUNT_FILE: accountFile }, encoding: 'utf8' });
  assert.equal(output.includes(next.password), false);
  const after = loginAccount(accountFile, initial, 'owner');
  const backups = readdirSync(dir).filter(path => path.startsWith('account.json.backup-'));
  assert.equal(backups.length, 1); assert.deepEqual(privateJson(join(dir, backups[0])), before);
  assert.equal(statSync(accountFile).mode & 0o777, 0o600);
  const auth = createAuthentication(after);
  await assert.rejects(auth.login(previous, 'ip'), { status: 401 });
  assert.equal((await auth.login(next, 'ip')).username, 'owner');
});

test('initial login is private, stable, hashed at rest and corruption fails closed', () => {
  const dir = mkdtempSync(join(tmpdir(), 'codex-login-'));
  const accountFile = join(dir, 'account.json'), initial = join(dir, 'initial.json');
  const stored = loginAccount(accountFile, initial, 'owner');
  assert.equal(stored.username, 'owner');
  assert.equal(statSync(initial).mode & 0o777, 0o600);
  assert.equal(statSync(accountFile).mode & 0o777, 0o600);
  assert.ok(!readFileSync(accountFile, 'utf8').includes(privateJson(initial).password));
  assert.deepEqual(loginAccount(accountFile, initial, 'different-name'), stored);
  chmodSync(accountFile, 0o644);
  assert.throws(() => loginAccount(accountFile, initial, 'owner'), /0600/);
  chmodSync(accountFile, 0o600); writeFileSync(accountFile, '{}');
  assert.throws(() => loginAccount(accountFile, initial, 'owner'), /Invalid login account/);
  assert.equal(readFileSync(accountFile, 'utf8'), '{}');
});

test('wrong username and password fail identically; whitespace is significant; failures are throttled', async () => {
  let now = 1000;
  const auth = createAuthentication(account, { now: () => now, maxFailures: 2, failureWindowMs: 100 });
  await assert.rejects(auth.login({ ...credentials, username: 'nobody' }, 'ip'), { status: 401, message: 'Username or password was rejected' });
  await assert.rejects(auth.login({ ...credentials, password: credentials.password.trim() }, 'ip'), { status: 401, message: 'Username or password was rejected' });
  await assert.rejects(auth.login(credentials, 'ip'), { status: 429 });
  now += 101;
  const login = await auth.login(credentials, 'ip');
  assert.equal(auth.authenticate(requestFor(login.token)).username, 'owner');
  assert.equal(auth.authenticate(requestFor('old-pairing-token'.repeat(3))), null);
  assert.equal(auth.authenticate({ headers: { authorization: `Bearer ${login.token}`, origin: 'https://example.com' } }), null);
});

test('session expiry, logout and session limits revoke active connections', async () => {
  let now = 1000, closed = 0;
  const auth = createAuthentication(account, { now: () => now, sessionLifetimeMs: 100, maxSessions: 1 });
  const first = await auth.login(credentials, 'ip');
  auth.track(requestFor(first.token), () => closed++);
  const second = await auth.login(credentials, 'ip');
  assert.equal(closed, 1); assert.equal(auth.authenticate(requestFor(first.token)), null);
  auth.track(requestFor(second.token), () => closed++);
  now += 100; auth.expire();
  assert.equal(closed, 2); assert.equal(auth.authenticate(requestFor(second.token)), null);
  const third = await auth.login(credentials, 'ip');
  auth.track(requestFor(third.token), () => closed++);
  auth.logout(requestFor(third.token));
  assert.equal(closed, 3); assert.equal(auth.authenticate(requestFor(third.token)), null);
});

test('HTTP adapter login contract, body limits and WebSocket logout are enforced', async () => {
  const host = createHost({ broker: new Broker({ upstream() {}, desktop() {} }),
    authentication: createAuthentication(account), token: 'legacy-token', port: 0 });
  const port = await host.listen(), base = `http://127.0.0.1:${port}`;
  const login = (body, extra = {}) => fetch(base + '/v1/login', { method: 'POST',
    headers: { 'Content-Type': 'application/json', ...extra }, body: JSON.stringify(body) });
  let peer;
  try {
    assert.equal((await fetch(base + '/v1/login')).status, 405);
    assert.equal((await login(credentials, { Origin: 'https://example.com' })).status, 401);
    assert.equal((await login(credentials, { 'Content-Type': 'text/plain' })).status, 415);
    assert.equal((await login({ ...credentials, password: 'x'.repeat(5000) })).status, 413);
    assert.equal((await login({ username: ['owner'], password: credentials.password })).status, 400);
    assert.equal((await login({ ...credentials, password: 'wrong' })).status, 401);
    const reply = await login(credentials); assert.equal(reply.status, 200);
    assert.equal(reply.headers.get('cache-control'), 'no-store');
    const { token } = await reply.json(), headers = { Authorization: `Bearer ${token}` };
    assert.equal((await fetch(base + '/v1/host', { headers })).status, 200);
    assert.equal((await fetch(base + '/v1/host', { headers: { Authorization: 'Bearer legacy-token' } })).status, 401);
    peer = new WebSocket(`ws://127.0.0.1:${port}/v1/rpc`, { headers });
    await once(peer, 'open');
    const closed = once(peer, 'close');
    assert.equal((await fetch(base + '/v1/logout', { method: 'POST', headers })).status, 200);
    assert.equal((await closed)[0], 1008);
    assert.equal((await fetch(base + '/v1/host', { headers })).status, 401);
    const rejected = new WebSocket(`ws://127.0.0.1:${port}/v1/rpc`, { headers });
    assert.match((await once(rejected, 'error'))[0].message, /401/);
  } finally { peer?.close(); host.close(); }
});
