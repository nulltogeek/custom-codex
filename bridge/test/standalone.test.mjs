import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { once } from 'node:events';
import { createServer } from 'node:net';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';
import { startStandalone } from '../standalone.mjs';

const fixture = fileURLToPath(new URL('./fixtures/standalone-backend.mjs', import.meta.url));
function settings() {
  const home = mkdtempSync(join(tmpdir(), 'codex-standalone-'));
  writeFileSync(join(home, 'config.toml'), 'model = "fixture"\n', { mode: 0o600 });
  return { backendPath: process.execPath, backendArgs: [fixture], backendVersion: 'fixture',
    codexHome: home, accountFile: join(home, 'account.json'), initialLoginFile: join(home, 'initial-login.json'), port: 0 };
}
async function login(runtime, base = `http://127.0.0.1:${runtime.port}`) {
  const response = await fetch(base + '/v1/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: readFileSync(runtime.initialLoginFile, 'utf8') });
  assert.equal(response.status, 200);
  return (await response.json()).token;
}
async function phone(runtime) {
  const token = await login(runtime);
  const peer = new WebSocket(`ws://127.0.0.1:${runtime.port}/v1/rpc`, { headers: { Authorization: `Bearer ${token}` } });
  let serial = 0; const waiting = new Map(), events = [];
  peer.on('message', data => {
    const message = JSON.parse(data.toString());
    if (message.type === 'event') events.push(message.message);
    else if (message.id !== undefined) {
      const request = waiting.get(message.id); if (!request) return;
      waiting.delete(message.id); message.error ? request.reject(Error(message.error.message)) : request.resolve(message.result);
    }
  });
  await once(peer, 'open');
  const call = (method, params = {}) => new Promise((resolveCall, reject) => {
    const id = ++serial; waiting.set(id, { resolve: resolveCall, reject }); peer.send(JSON.stringify({ id, method, params }));
  });
  await call('initialize'); await call('bridge/subscribe');
  return { peer, events, call, token };
}
async function until(predicate) {
  const limit = Date.now() + 3000;
  while (!predicate()) { if (Date.now() > limit) throw Error('Fixture notification did not arrive'); await new Promise(r => setTimeout(r, 10)); }
}

test('standalone initializes a genuine custom client and the selected home without desktop input', { timeout: 8000 }, async () => {
  const options = settings(); const runtime = await startStandalone(options); let client;
  try {
    client = await phone(runtime);
    const init = await client.call('initialize');
    assert.equal(init.client.name, 'custom_codex_android'); assert.equal(init.client.title, 'Codex Remote');
    assert.equal(init.attestation, false); assert.equal(init.codexHome, options.codexHome);
    const host = await (await fetch(`http://127.0.0.1:${runtime.port}/v1/host`, {
      headers: { Authorization: `Bearer ${client.token}` } })).json();
    assert.equal(host.runtimeMode, 'standalone'); assert.equal(host.configFile, join(options.codexHome, 'config.toml'));
    assert.equal(host.ready, true); assert.equal(host.backendPid, runtime.backend.pid);
    assert.equal(readFileSync(host.configFile, 'utf8'), 'model = "fixture"\n');
    client.peer.close(); await until(() => runtime.broker.clients.size === 0);
    assert.equal(runtime.backend.exitCode, null); assert.equal(runtime.broker.ready, true);
  } finally { client?.peer.close(); await runtime.close(); }
});

test('standalone waits for an explicit valid phone approval and resolves it once', { timeout: 8000 }, async () => {
  const runtime = await startStandalone(settings()); const client = await phone(runtime);
  try {
    await client.call('thread/start', { simulate: 'item/commandExecution/requestApproval' });
    await until(() => runtime.broker.snapshot().pendingRequests.length === 1);
    const request = runtime.broker.snapshot().pendingRequests[0];
    assert.equal(client.events.filter(m => m.method === 'fixture/answered').length, 0);
    await assert.rejects(client.call('bridge/respond', { requestId: request.requestId, result: { decision: 'invalid' } }), /Invalid answer/);
    assert.equal(runtime.broker.snapshot().pendingRequests.length, 1);
    await client.call('bridge/respond', { requestId: request.requestId, result: { decision: 'decline' } });
    await until(() => client.events.some(m => m.method === 'fixture/answered'));
    assert.deepEqual(client.events.find(m => m.method === 'fixture/answered').params.answer, { decision: 'decline' });
    assert.equal(runtime.broker.snapshot().pendingRequests.length, 0);
    await assert.rejects(client.call('bridge/respond', { requestId: request.requestId, result: { decision: 'accept' } }), /resolved/);
  } finally { client.peer.close(); await runtime.close(); }
});

test('standalone rejects unavailable desktop client tools without hanging or approving them', { timeout: 8000 }, async () => {
  const runtime = await startStandalone(settings()); const client = await phone(runtime);
  try {
    for (const simulate of ['item/tool/call', 'attestation/generate']) {
      const previous = client.events.filter(m => m.method === 'fixture/answered').length;
      await client.call('thread/start', { simulate });
      await until(() => client.events.filter(m => m.method === 'fixture/answered').length > previous);
      assert.equal(client.events.filter(m => m.method === 'fixture/answered').at(-1).params.errorCode, -32601);
      assert.equal(runtime.broker.serverRequests.size, 0);
    }
  } finally { client.peer.close(); await runtime.close(); }
});

test('standalone port conflict leaves the existing listener running', { timeout: 8000 }, async () => {
  const listener = createServer(); listener.listen(0, '127.0.0.1'); await once(listener, 'listening');
  try {
    await assert.rejects(startStandalone({ ...settings(), port: listener.address().port }), /already in use/);
    assert.equal(listener.listening, true);
  } finally { await new Promise(r => listener.close(r)); }
});

test('explicit interface binding restricts the listener and keeps snapshots authenticated', { timeout: 8000 }, async () => {
  const runtime = await startStandalone({ ...settings(), bindAddress: '127.0.0.2' });
  try {
    assert.equal(runtime.host.server.address().address, '127.0.0.2');
    const base = `http://127.0.0.2:${runtime.port}`;
    assert.equal((await fetch(`${base}/healthz`)).status, 200);
    assert.equal((await fetch(`${base}/v1/host`)).status, 401);
    const token = await login(runtime, base);
    assert.equal((await fetch(`${base}/v1/host`, { headers: { Authorization: `Bearer ${token}` } })).status, 200);
    assert.equal((await fetch(`${base}/v1/host`, { headers: { Authorization: `Bearer ${token}`, Origin: 'https://example.com' } })).status, 401);
    await assert.rejects(fetch(`http://127.0.0.1:${runtime.port}/healthz`));
  } finally { await runtime.close(); }
  await assert.rejects(startStandalone({ ...settings(), bindAddress: '0.0.0.0.example' }), /literal interface IP/);
});

test('standalone advertises password changes and reloads the changed account without rewriting the initial reference', { timeout: 8000 }, async () => {
  const options = settings(); let runtime = await startStandalone(options);
  const initial = readFileSync(runtime.initialLoginFile, 'utf8');
  const current = JSON.parse(initial), nextPassword = 'standalone-new-test-password';
  try {
    const token = await login(runtime), base = `http://127.0.0.1:${runtime.port}`;
    const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
    const host = await (await fetch(base + '/v1/host', { headers })).json();
    assert.equal(host.capabilities.passwordChange, true);
    const response = await fetch(base + '/v1/password', { method: 'POST', headers,
      body: JSON.stringify({ currentPassword: current.password, newPassword: nextPassword }) });
    assert.equal(response.status, 200);
    assert.equal(runtime.backend.exitCode, null);
    assert.equal(readFileSync(runtime.initialLoginFile, 'utf8'), initial);
    assert.equal((await fetch(base + '/v1/host', { headers })).status, 200);
    await runtime.close(); runtime = await startStandalone(options);
    const newBase = `http://127.0.0.1:${runtime.port}`;
    const attempt = password => fetch(newBase + '/v1/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: current.username, password }) });
    assert.equal((await attempt(current.password)).status, 401);
    assert.equal((await attempt(nextPassword)).status, 200);
  } finally { await runtime.close(); }
});
