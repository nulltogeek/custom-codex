import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { once } from 'node:events';
import { WebSocket } from 'ws';
import { startStandalone } from '../standalone.mjs';
import { Broker } from '../broker.mjs';
import { tenantPolicy, createTenantPool } from '../tenants.mjs';

const fixture = fileURLToPath(new URL('./fixtures/standalone-backend.mjs', import.meta.url));
function fakeRuntime(user, root) {
  const home = join(root, user.id), uploads = join(home, 'uploads'), workspace = join(home, 'workspace');
  for (const path of [home, uploads, workspace]) mkdirSync(path, { recursive: true, mode: 0o700 });
  const threads = new Map(); let serial = 0;
  const broker = new Broker({ desktop() {}, authorize: tenantPolicy(workspace), upstream(message) {
    queueMicrotask(() => {
      if (!message.method) return;
      let result;
      if (message.method === 'thread/start') {
        const thread = { id: user.id + '-' + ++serial, name: 'Private ' + user.username, cwd: workspace, status: { type: 'idle' } };
        threads.set(thread.id, thread); result = { thread };
        broker.receive({ method: 'thread/started', params: { thread } });
      } else if (message.params.threadId) {
        if (!threads.has(message.params.threadId)) { broker.receive({ id: message.id, error: { code: -32004, message: 'Thread not found' } }); return; }
        result = { thread: threads.get(message.params.threadId) };
      } else result = {};
      broker.receive({ id: message.id, result });
    });
  } });
  broker.ready = true; broker.initResult = {};
  return { broker, codexHome: home, workspaceRoot: workspace, uploadDirectory: uploads, backendVersion: 'fixture',
    readHistory: async () => ({ data: [...threads.values()], nextCursor: null }),
    readProjects: async () => ({ projects: [{ id: 'p-' + user.id, name: user.username, rootPaths: [workspace] }], assignments: {} }),
    close: async () => {},
  };
}
async function connect(port, token) {
  const peer = new WebSocket(`ws://127.0.0.1:${port}/v1/rpc`, { headers: { Authorization: `Bearer ${token}` } });
  const waiting = new Map(), events = []; let serial = 0;
  peer.on('message', raw => {
    const message = JSON.parse(raw);
    if (message.type === 'event') events.push(message);
    if (message.id !== undefined && waiting.has(message.id)) {
      const { resolve, reject } = waiting.get(message.id); waiting.delete(message.id);
      message.error ? reject(Error(message.error.message)) : resolve(message.result);
    }
  });
  await once(peer, 'open');
  const call = (method, params = {}) => new Promise((resolve, reject) => { const id = ++serial; waiting.set(id, { resolve, reject }); peer.send(JSON.stringify({ id, method, params })); });
  await call('initialize'); await call('bridge/subscribe');
  return { peer, call, events };
}

test('two users cannot read or mutate each other IDs, see events/approvals/replay, projects or attachments', { timeout: 20000 }, async () => {
  const root = mkdtempSync(join(tmpdir(), 'codex-multiuser-host-')), runtimes = new Map();
  const runtime = await startStandalone({ backendPath: process.execPath, backendArgs: [fixture], backendVersion: 'fixture',
    codexHome: root, accountFile: join(root, 'account.json'), initialLoginFile: join(root, 'initial.json'), username: 'nulltogeek', port: 0,
    tenantOptions: { runtimeFactory(user, root) { const value = fakeRuntime(user, root); runtimes.set(user.id, value); return value; } } });
  const base = `http://127.0.0.1:${runtime.port}`;
  const request = (path, session, method = 'GET', body, identity) => fetch(base + path, { method,
    headers: { ...(session ? { Authorization: `Bearer ${session.token}` } : {}), 'Content-Type': 'application/json',
      ...(identity ? { 'X-Codex-Account-Identity': identity } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  let alice, bob;
  try {
    const signup = async username => { const response = await request('/v1/signup', null, 'POST', { username, password: 'a-test-signup-password' }); assert.equal(response.status, 200); return response.json(); };
    const a = await signup('alice'), b = await signup('bob');
    const owner = await (await request('/v1/login', null, 'POST', JSON.parse(readFileSync(runtime.initialLoginFile)))).json();
    assert.equal((await request('/v1/admin/users', a)).status, 403);
    assert.equal((await request('/v1/admin/users')).status, 401);
    assert.equal((await (await request('/v1/admin/users', owner)).json()).users.length, 3);
    assert.equal((await (await request('/v1/host', owner)).json()).capabilities.userManagement, true);
    assert.equal((await (await request('/v1/host', owner)).json()).capabilities.userDeletion, true);
    assert.equal((await (await request('/v1/host', a)).json()).capabilities.userManagement, false);
    assert.equal((await (await request('/v1/host', a)).json()).capabilities.userDeletion, false);
    alice = await connect(runtime.port, a.token); bob = await connect(runtime.port, b.token);
    const at = (await alice.call('thread/start')).thread, bt = (await bob.call('thread/start')).thread;
    await assert.rejects(alice.call('thread/read', { threadId: bt.id }), /not found/);
    await assert.rejects(alice.call('thread/delete', { threadId: bt.id }), /not found/);
    await assert.rejects(bob.call('thread/name/set', { threadId: at.id, name: 'stolen' }), /not found/);
    assert.equal(JSON.stringify(alice.events).includes(bt.id), false);
    assert.equal(JSON.stringify(bob.events).includes(at.id), false);
    const aHistory = await (await request('/v1/history', a)).json(); assert.deepEqual(aHistory.data.map(t => t.id), [at.id]);
    const bProjects = await (await request('/v1/projects', b)).json(); assert.equal(bProjects.projects[0].name, 'bob');
    const broker = runtimes.get(b.user.id).broker;
    broker.receive({ id: 'approval-b', method: 'item/commandExecution/requestApproval', params: { threadId: bt.id, turnId: 'turn-b', availableDecisions: ['decline'] } });
    const pending = broker.snapshot().pendingRequests[0];
    assert.equal((await (await request('/v1/snapshot', a)).json()).pendingRequests.length, 0);
    await assert.rejects(alice.call('bridge/respond', { requestId: pending.requestId, result: { decision: 'decline' } }), /resolved|previous/);
    const replay = await alice.call('bridge/subscribe', { epoch: broker.epoch, sequence: 0 });
    assert.equal(replay.replayGap, true); assert.equal(replay.pendingRequests.length, 0);
    await bob.call('bridge/respond', { requestId: pending.requestId, result: { decision: 'decline' } });
    const image = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2SAAAAABJRU5ErkJggg==', 'base64');
    const uploaded = await (await fetch(base + '/v1/attachments', { method: 'POST', headers: { Authorization: `Bearer ${b.token}`, 'Content-Type': 'image/png', 'X-File-Name': 'private.png' }, body: image })).json();
    const path = '/v1/attachments/image?path=' + encodeURIComponent(uploaded.input.path);
    assert.equal((await request(path, a)).status, 404); assert.equal((await request(path, b)).status, 200);
    await assert.rejects(alice.call('project/create', { roots: [{ path: '/home/nulltogeek' }] }), /private workspace/);
    await assert.rejects(alice.call('thread/start', { accessMode: 'full' }), /administrator/);
    const response = await request('/v1/admin/users/' + b.user.id, owner, 'PATCH', { disabled: true }); assert.equal(response.status, 200);
    assert.equal((await request('/v1/host', b)).status, 401);
    assert.equal((await request('/v1/host', a)).status, 200);
    assert.equal((await request('/v1/admin/users/' + a.user.id, null, 'DELETE', {})).status, 401);
    assert.equal((await request('/v1/admin/users/' + a.user.id, a, 'DELETE', {})).status, 403);
    assert.equal((await request('/v1/admin/users/owner', owner, 'DELETE', {})).status, 400);
    const closed = once(alice.peer, 'close');
    assert.equal((await request('/v1/admin/users/' + a.user.id, owner, 'DELETE', {})).status, 200);
    await closed;
    assert.equal(runtime.pool.activeCount, 0);
    assert.equal((await request('/v1/host', a)).status, 401);
    assert.equal((await request('/v1/admin/users/' + a.user.id, owner, 'PATCH', { disabled: false })).status, 404);
    assert.equal((await request('/v1/signup', null, 'POST', { username: 'ALICE', password: 'a-new-password-long' })).status, 409);
    const created = await request('/v1/admin/users', owner, 'POST', { username: 'alice', password: 'a-new-password-long' }, '2');
    assert.equal(created.status, 200); const fresh = (await created.json()).user;
    assert.notEqual(fresh.id, a.user.id); assert.equal(fresh.accountIdentityVersion, 2);
    assert.ok((await (await request('/v1/admin/users', owner)).json()).users.some(user => user.id === fresh.id));
    assert.equal((await request('/v1/login', null, 'POST', { username: 'alice', password: 'a-new-password-long' })).status, 426);
    const freshLogin = await request('/v1/login', null, 'POST', { username: 'alice', password: 'a-new-password-long' }, '2');
    assert.equal(freshLogin.status, 200); const newSession = await freshLogin.json();
    assert.deepEqual((await (await request('/v1/history', newSession)).json()).data, []);
    assert.equal((await request(path, newSession)).status, 404);
    assert.equal((await request('/v1/host', owner)).status, 200);
  } finally { alice?.peer.close(); bob?.peer.close(); await runtime.close(); }
});

test('tenant stop/start serialize and idle reclamation never stops a running turn', async () => {
  let starts = 0, closes = 0, releaseStart;
  const startGate = new Promise(resolve => { releaseStart = resolve; });
  const roots = mkdtempSync(join(tmpdir(), 'codex-pool-'));
  const pool = createTenantPool({ directory: roots, maxRuntimes: 1, runtimeFactory: async () => {
    starts++; if (starts === 1) await startGate;
    return { broker: new Broker({ desktop() {}, upstream() {} }), close: async () => { closes++; } };
  } });
  const a = { id: '11111111-1111-1111-1111-111111111111' }, b = { id: '22222222-2222-2222-2222-222222222222' };
  try {
    const first = pool.get(a); const stopping = pool.stop(a.id); const replacement = pool.get(a);
    releaseStart(); await first; await stopping; const active = await replacement;
    assert.equal(starts, 2); assert.equal(closes, 1); assert.equal(pool.activeCount, 1);
    active.broker.activeTurns.add('turn');
    assert.throws(() => pool.get(b), /busy/); assert.equal(closes, 1);
    active.broker.activeTurns.clear(); await pool.get(b);
    assert.equal(starts, 3); assert.equal(closes, 2); assert.equal(pool.activeCount, 1);
    await pool.retire(b.id);
    assert.throws(() => pool.get(b), /unavailable/);
    assert.equal(starts, 3); assert.equal(closes, 3);
  } finally { await pool.close(); }
});

test('mobile RPC floods are bounded and rejected upstream writes leave no pending entry', () => {
  const sent = [], output = [];
  const broker = new Broker({ desktop() {}, upstream: message => sent.push(message) });
  broker.ready = true; broker.addClient('phone', message => output.push(message));
  for (let id = 1; id <= 65; id++) broker.accept('phone', { id, method: 'model/list', params: {} });
  assert.equal(sent.length, 64); assert.equal(broker.requests.size, 64);
  assert.equal(output.at(-1).error.code, -32029);
  broker.accept('phone', { method: 'thread/start', params: {} });
  assert.equal(output.at(-1).error.code, -32600); assert.equal(sent.length, 64);
  const full = new Broker({ desktop() {}, upstream: () => false });
  full.ready = true; full.addClient('phone', message => output.push(message));
  full.accept('phone', { id: 1, method: 'thread/start', params: {} });
  assert.equal(full.requests.size, 0); assert.equal(output.at(-1).error.code, -32029);
});
