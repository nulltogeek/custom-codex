import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, statSync, chmodSync, writeFileSync, readdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import { WebSocket } from 'ws';
import { passwordAccount, loginAccount, privateJson, createAuthentication } from '../auth.mjs';
import { createHost } from '../host.mjs';
import { Broker } from '../broker.mjs';

const credentials = { username: 'owner', password: 'a-test-password-with-spaces ' };
const account = passwordAccount(credentials.username, credentials.password);
const requestFor = token => ({ headers: { authorization: `Bearer ${token}` } });

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
