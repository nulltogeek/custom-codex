// Real standalone app-server and phone transport; never prints configuration or credentials.
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';
import { once } from 'node:events';
import { createRequire } from 'node:module';
import { WebSocket } from 'ws';
import { startStandalone } from '../standalone.mjs';
const require = createRequire(import.meta.url);
const { DatabaseSync } = require('node:sqlite');
const temp = mkdtempSync(join(tmpdir(), 'codex-standalone-live-'));
const runtime = await startStandalone({ port: 0, accountFile: join(temp, 'account.json'), initialLoginFile: join(temp, 'initial-login.json') });
const configFile = join(runtime.codexHome, 'config.toml');
const fingerprint = () => createHash('sha256').update(readFileSync(configFile)).digest('hex');
const before = fingerprint(); const clients = [];
const timeout = setTimeout(() => { console.error('Standalone live check timed out'); void runtime.close(); process.exitCode = 1; }, 90000);
async function connect() {
  const response = await fetch(`http://127.0.0.1:${runtime.port}/v1/login`, { method: 'POST',
    headers: { 'Content-Type': 'application/json' }, body: readFileSync(runtime.initialLoginFile, 'utf8') });
  assert.equal(response.status, 200);
  const { token } = await response.json();
  const peer = new WebSocket(`ws://127.0.0.1:${runtime.port}/v1/rpc`, { headers: { Authorization: `Bearer ${token}` } });
  const pending = new Map(), events = []; let serial = 0;
  peer.on('message', data => {
    const m = JSON.parse(data.toString());
    if (m.type === 'event') events.push(m.message);
    else if (m.id !== undefined) {
      const waiting = pending.get(m.id); if (!waiting) return;
      pending.delete(m.id); m.error ? waiting.reject(Error(m.error.message)) : waiting.resolve(m.result);
    }
  });
  await once(peer, 'open');
  const call = (method, params = {}) => new Promise((resolveCall, reject) => {
    const id = ++serial; pending.set(id, { resolve: resolveCall, reject }); peer.send(JSON.stringify({ id, method, params }));
  });
  await call('initialize'); await call('bridge/subscribe');
  const get = async path => {
    const reply = await fetch(`http://127.0.0.1:${runtime.port}${path}`, { headers: { Authorization: `Bearer ${token}` } });
    assert.equal(reply.status, 200); return reply.json();
  };
  const client = { peer, events, call, get }; clients.push(client); return client;
}
try {
  const phone = await connect(), other = await connect();
  const host = await phone.get('/v1/host'); assert.equal(host.runtimeMode, 'standalone'); assert.equal(host.ready, true);
  assert.equal(host.backendPid, runtime.backend.pid); assert.equal(host.configFile, configFile);
  const originalOwner = runtime.broker.desktop;
  // Privileged local introspection is deliberately absent from the phone method allowlist.
  const config = await new Promise((resolveConfig, reject) => {
    runtime.broker.desktop = message => {
      if (message.id === 'verification-config') message.error ? reject(Error('config/read failed')) : resolveConfig(message.result);
      else originalOwner(message);
    };
    runtime.broker.accept('desktop', { id: 'verification-config', method: 'config/read', params: { includeLayers: true } });
  });
  runtime.broker.desktop = originalOwner;
  const configLayerVerified = JSON.stringify(config.layers ?? []).includes(configFile);
  assert.equal(configLayerVerified, true, 'Effective configuration must include the requested user config file');
  const projects = await phone.get('/v1/projects'); const models = await phone.call('model/list');
  assert.ok(Array.isArray(models.data));
  const db = new DatabaseSync(join(runtime.codexHome, 'state_5.sqlite'), { readOnly: true });
  let persisted;
  try { persisted = db.prepare('SELECT id,archived FROM threads').all(); } finally { db.close(); }
  const counts = {};
  for (const archived of [false, true]) {
    const ids = []; let cursor = null;
    do {
      const page = await phone.get(`/v1/history?archived=${archived}` + (cursor ? `&cursor=${cursor}` : ''));
      ids.push(...page.data.map(t => t.id)); cursor = page.nextCursor;
    } while (cursor);
    assert.deepEqual([...new Set(ids)].sort(), persisted.filter(t => Boolean(t.archived) === archived).map(t => t.id).sort());
    counts[archived ? 'archived' : 'active'] = ids.length;
  }
  const stored = persisted.find(t => !t.archived);
  if (stored) { const read = await phone.call('thread/read', { threadId: stored.id, includeTurns: false }); assert.equal(read.thread.id, stored.id); }
  const thread = await phone.call('thread/start', { ephemeral: true });
  const same = await other.call('thread/read', { threadId: thread.thread.id, includeTurns: false });
  assert.equal(same.thread.id, thread.thread.id);
  await new Promise(r => setTimeout(r, 100));
  for (const c of clients) assert.ok(c.events.some(m => m.method === 'thread/started' && m.params.thread.id === thread.thread.id));
  phone.peer.close(); await new Promise(r => setTimeout(r, 50));
  assert.equal(runtime.backend.exitCode, null);
  await other.call('thread/read', { threadId: thread.thread.id, includeTurns: false });
  assert.equal(fingerprint(), before, 'Existing config.toml must not change');
  console.log(JSON.stringify({ passed: true, runtimeMode: host.runtimeMode, backendVersion: host.backendVersion,
    codexHome: runtime.codexHome, configFile, effectiveConfigLayerVerified: configLayerVerified, configUnchanged: true,
    desktopDriverRequired: false, projects: projects.projects.length, modelsDiscovered: models.data.length,
    completePersistedCatalogue: counts, sameRuntimeAcrossPhoneClients: true, mirroredEvents: true,
    phoneDisconnectLeavesServerRunning: true, modelGeneration: false }, null, 2));
} finally {
  clearTimeout(timeout); for (const client of clients) client.peer.close(); await runtime.close();
}
