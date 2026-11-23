#!/usr/bin/env node
// Disposable HTTP/RPC staging check. All account data and histories use a temporary home.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { WebSocket } from 'ws';
import { startStandalone } from '../bridge/standalone.mjs';

const root = mkdtempSync(join(tmpdir(), 'codex-http-stage-'));
let providerRequests = 0, syntheticResponses = 0;
const provider = createServer((req, res) => {
  providerRequests++;
  if (req.method !== 'POST') { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{"data":[]}'); return; }
  syntheticResponses++;
  req.resume();
  req.on('end', () => {
    const id = 'resp_' + randomUUID(), itemId = 'msg_' + randomUUID(), text = 'Disposable staging reply.';
    const item = { id: itemId, type: 'message', role: 'assistant', status: 'completed', content: [{ type: 'output_text', text, annotations: [], logprobs: [] }] };
    const response = { id, object: 'response', created_at: Math.floor(Date.now() / 1000), model: 'gpt-5.4', status: 'in_progress', output: [] };
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' }); let sequence = 0;
    const send = (type, data) => res.write('event: ' + type + '\ndata: ' + JSON.stringify({ type, sequence_number: sequence++, ...data }) + '\n\n');
    send('response.created', { response });
    send('response.output_item.added', { output_index: 0, item: { ...item, status: 'in_progress', content: [] } });
    send('response.content_part.added', { item_id: itemId, output_index: 0, content_index: 0, part: { type: 'output_text', text: '', annotations: [], logprobs: [] } });
    send('response.output_text.delta', { item_id: itemId, output_index: 0, content_index: 0, delta: text });
    send('response.output_text.done', { item_id: itemId, output_index: 0, content_index: 0, text });
    send('response.content_part.done', { item_id: itemId, output_index: 0, content_index: 0, part: item.content[0] });
    send('response.output_item.done', { output_index: 0, item });
    send('response.completed', { response: { ...response, status: 'completed', output: [item], usage: { input_tokens: 5, output_tokens: 5, total_tokens: 10, input_tokens_details: { cached_tokens: 0 }, output_tokens_details: { reasoning_tokens: 0 } } } });
    res.end();
  });
});
await new Promise(resolve => provider.listen(0, '127.0.0.1', resolve));
const home = join(root, 'owner-home'); mkdirSync(home, { mode: 0o700 });
writeFileSync(join(home, 'config.toml'), `model="gpt-5.4"\nmodel_provider="staging"\n[model_providers.staging]\nname="Disposable provider"\nbase_url="http://127.0.0.1:${provider.address().port}/v1"\nrequires_openai_auth=false\nwire_api="responses"\nsupports_websockets=false\n`, { mode: 0o600 });
let runtime;
const peers = [];
async function connect(port, token) {
  const peer = new WebSocket(`ws://127.0.0.1:${port}/v1/rpc`, { headers: { Authorization: `Bearer ${token}` } }); peers.push(peer);
  const waiting = new Map(), events = []; let serial = 0;
  peer.on('message', raw => {
    const message = JSON.parse(raw);
    if (message.type === 'event') events.push(message);
    const request = waiting.get(message.id);
    if (request) { waiting.delete(message.id); message.error ? request.reject(Error(message.error.message)) : request.resolve(message.result); }
  });
  await once(peer, 'open');
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++serial; waiting.set(id, { resolve, reject }); peer.send(JSON.stringify({ id, method, params }));
  });
  await call('initialize'); await call('bridge/subscribe'); return { peer, events, call };
}
try {
  runtime = await startStandalone({ backendPath: process.env.CODEX_REAL_CLI_PATH ?? '/home/nulltogeek/.local/lib/custom-codex/runtime/bin/codex',
    codexHome: home, accountFile: join(root, 'account.json'), initialLoginFile: join(root, 'initial.json'),
    usersFile: join(root, 'users.json'), tenantDirectory: join(root, 'tenants'), username: 'nulltogeek', port: 0,
    tenantOptions: { providerSettings: { baseUrl: `http://127.0.0.1:${provider.address().port}/v1`, apiKey: 'disposable-staging-key', model: 'gpt-5.4', effort: 'medium' } } });
  const base = `http://127.0.0.1:${runtime.port}`;
  const http = async (path, session, method = 'GET', body) => {
    const response = await fetch(base + path, { method, headers: { 'Content-Type': 'application/json', ...(session ? { Authorization: `Bearer ${session.token}` } : {}) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    return { status: response.status, value: await response.json() };
  };
  const ownerCredentials = JSON.parse(readFileSync(runtime.initialLoginFile, 'utf8'));
  const login = async (username, password) => { const response = await http('/v1/login', null, 'POST', { username, password }); assert.equal(response.status, 200); return response.value; };
  const owner = await login(ownerCredentials.username, ownerCredentials.password);
  const signup = async username => {
    const response = await http('/v1/signup', null, 'POST', { username, password: 'disposable-account-password' });
    assert.equal(response.status, 200); assert.equal(response.value.user.role, 'user'); return response.value;
  };
  const a = await signup('alice'), b = await signup('bob');
  assert.equal((await http('/v1/login', null, 'POST', { username: 'alice', password: 'wrong' })).status, 401);
  assert.equal((await login('ALICE', 'disposable-account-password')).username, 'alice');
  const ah = await http('/v1/host', a), bh = await http('/v1/host', b), oh = await http('/v1/host', owner);
  assert.equal(ah.value.user.id, a.user.id); assert.equal(bh.value.user.id, b.user.id);
  assert.notEqual(ah.value.codexHome, bh.value.codexHome); assert.notEqual(ah.value.codexHome, oh.value.codexHome);
  assert.equal(ah.value.capabilities.userManagement, false); assert.equal(oh.value.capabilities.userManagement, true);
  assert.equal(oh.value.user.username, 'nulltogeek'); assert.equal(oh.value.user.role, 'admin');
  assert.equal((await http('/v1/admin/users', a)).status, 403);
  const listing = await http('/v1/admin/users', owner); assert.equal(listing.status, 200); assert.equal(listing.value.users.length, 3);
  const alice = await connect(runtime.port, a.token), bob = await connect(runtime.port, b.token);
  const at = (await alice.call('thread/start', { cwd: ah.value.capabilities.workspaceRoot, mobileMode: 'codex', accessMode: 'host' })).thread;
  const bt = (await bob.call('thread/start', { cwd: bh.value.capabilities.workspaceRoot, mobileMode: 'codex', accessMode: 'host' })).thread;
  const complete = async (client, id) => {
    await client.call('turn/start', { threadId: id, input: [{ type: 'text', text: 'Disposable staging message.' }], mobileMode: 'codex', accessMode: 'host' });
    const until = Date.now() + 30000;
    while (!client.events.some(event => event.message?.method === 'turn/completed' && event.message?.params?.threadId === id)) {
      if (Date.now() > until) throw Error('Synthetic staging turn did not complete');
      await new Promise(resolve => setTimeout(resolve, 50));
    }
    const terminal = client.events.find(event => event.message?.method === 'turn/completed' && event.message?.params?.threadId === id);
    assert.equal(terminal.message.params.turn.status, 'completed');
  };
  await complete(alice, at.id); await complete(bob, bt.id);
  await alice.call('thread/name/set', { threadId: at.id, name: 'Alice staging chat' });
  await bob.call('thread/name/set', { threadId: bt.id, name: 'Bob staging chat' });
  const created = await alice.call('project/create', { idempotencyKey: randomUUID(), name: 'Alice staging project', roots: [{ path: ah.value.capabilities.workspaceRoot }] });
  const projectId = created.project?.id ?? created.id; assert.ok(projectId);
  const files = (action, path = '', extra = {}, session = a) => http('/v1/files', session, 'POST', { action, path, projectId, rootIndex: 0, ...extra });
  await alice.call('thread/metadata/update', { threadId: at.id, projectId });
  // Internal execution prepares disposable files; phones never receive command/exec access.
  await runtime.pool.get(a.user).then(workspace => workspace.call('command/exec', { command: ['python3', '-c',
    "from pathlib import Path; Path('editor.txt').write_text('before'); Path('large.txt').write_text('あ'*80000); Path('link').symlink_to('/etc/passwd')"],
    cwd: ah.value.capabilities.workspaceRoot, sandboxPolicy: { type: 'dangerFullAccess' } }));
  assert.equal((await files('list')).value.entries.some(entry => entry.name === 'editor.txt'), true);
  const file = await files('read', 'editor.txt'); assert.equal(file.status, 200); assert.equal(file.value.content, 'before');
  assert.equal((await files('read', 'large.txt')).value.content, 'あ'.repeat(80000));
  assert.equal((await files('write', 'editor.txt', { revision: file.value.revision, content: 'Edited from the phone\nسلام' })).status, 200);
  assert.equal((await files('read', 'editor.txt')).value.content, 'Edited from the phone\nسلام');
  assert.equal((await files('write', 'editor.txt', { revision: file.value.revision, content: 'stale' })).status, 409);
  assert.equal((await files('search', '', { query: 'editor' })).value.entries[0].path, 'editor.txt');
  assert.equal((await files('read', 'editor.txt', {}, b)).status, 404);
  assert.equal((await files('read', '../account.json')).status, 400);
  assert.equal((await files('read', 'link')).status, 403);
  await alice.call('thread/metadata/update', { threadId: at.id, projectId });
  const aProjects = await http('/v1/projects', a), bProjects = await http('/v1/projects', b), aHistory = await http('/v1/history', a), bHistory = await http('/v1/history', b);
  assert.equal(aProjects.status, 200); assert.equal(bProjects.status, 200); assert.equal(aHistory.status, 200); assert.equal(bHistory.status, 200);
  assert.ok(aProjects.value.projects.some(project => project.id === projectId));
  assert.equal(bProjects.value.projects.some(project => project.id === projectId), false);
  assert.ok(aHistory.value.data.some(thread => thread.id === at.id)); assert.equal(aHistory.value.data.some(thread => thread.id === bt.id), false);
  assert.ok(bHistory.value.data.some(thread => thread.id === bt.id)); assert.equal(bHistory.value.data.some(thread => thread.id === at.id), false);
  await assert.rejects(bob.call('thread/read', { threadId: at.id, includeTurns: false }));
  await assert.rejects(bob.call('thread/name/set', { threadId: at.id, name: 'stolen' }));
  assert.equal(JSON.stringify(bob.events).includes(at.id), false);
  assert.equal((await http('/v1/admin/users/' + b.user.id, owner, 'PATCH', { disabled: true })).status, 200);
  assert.equal((await http('/v1/host', b)).status, 401);
  assert.equal((await http('/v1/login', null, 'POST', { username: 'bob', password: 'disposable-account-password' })).status, 401);
  assert.equal((await http('/v1/admin/users/' + b.user.id, owner, 'PATCH', { disabled: false })).status, 200);
  const again = await login('bob', 'disposable-account-password');
  assert.equal((await http('/v1/admin/users/' + b.user.id + '/revoke', owner, 'POST', {})).status, 200);
  assert.equal((await http('/v1/host', again)).status, 401);
  assert.equal((await http('/v1/admin/users/' + b.user.id, owner, 'PATCH', { password: 'disposable-reset-password' })).status, 200);
  assert.equal((await login('bob', 'disposable-reset-password')).username, 'bob');
  assert.equal((await http('/v1/admin/settings', owner, 'PATCH', { signupEnabled: false })).status, 200);
  assert.equal((await http('/v1/signup', null, 'POST', { username: 'closed', password: 'disposable-account-password' })).status, 403);
  assert.equal((await http('/v1/admin/settings', owner, 'PATCH', { signupEnabled: true })).status, 200);
  assert.equal((await http('/v1/admin/users', owner, 'POST', { username: 'carol', password: 'disposable-created-password' })).status, 200);
  assert.equal((await http('/v1/admin/users', owner)).value.users.length, 4);
  assert.equal((await http('/v1/admin/users/owner', owner, 'PATCH', { disabled: true })).status, 400);
  assert.equal((await http('/v1/history', owner)).value.data.length, 0);
  assert.equal(syntheticResponses, 2);
  console.log(JSON.stringify({ realStandaloneAndDocker: true, disposableAccountsOnly: true, signupAndLogin: true,
    hostIdentitiesAndRoles: true, adminListCreateDisableReenableResetRevokeAndRegistration: true, protectedAdministrator: true,
    separateHttpHistoryAndProjects: true, projectFileBrowseSearchReadWrite: true, largeUtf8File: true, staleFileSaveRejected: true,
    fileTraversalAndCrossUserIsolation: true, rejectedCrossUserReadsAndMutations: true, isolatedEvents: true, syntheticLocalProviderReplies: syntheticResponses, paidModelCalls: 0 }));
} finally {
  for (const peer of peers) peer.close();
  await runtime?.close(); provider.close(); rmSync(root, { recursive: true, force: true });
}
