import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import { DatabaseSync } from 'node:sqlite';
import { WebSocket } from 'ws';
import { Broker } from '../broker.mjs';
import { pairingToken, readProjects, readHistory, createHost } from '../host.mjs';

test('pairing token is strong, owner-only and stable across launches', () => {
  const path = join(mkdtempSync(join(tmpdir(), 'codex-token-')), 'token');
  const token = pairingToken(path); assert.equal(token.length, 43);
  assert.equal(statSync(path).mode & 0o777, 0o600); assert.equal(pairingToken(path), token);
});
test('corrupted pairing tokens fail before becoming unusable HTTP credentials', () => {
  const path = join(mkdtempSync(join(tmpdir(), 'codex-corrupt-token-')), 'token');
  writeFileSync(path, Buffer.alloc(44), { mode: 0o600 });
  assert.throws(() => pairingToken(path), /base64url/);
  assert.deepEqual(readFileSync(path), Buffer.alloc(44));
});
test('project adapter includes empty projects, migrated IDs and legacy assignments without writing metadata', () => {
  const root = mkdtempSync(join(tmpdir(), 'codex-projects-')); const path = join(root, '.codex-global-state.json');
  const input = JSON.stringify({ 'local-projects': { a: { id: 'a', name: 'Empty', rootPaths: ['/empty'] }, b: { id: 'b', name: 'Full', rootPaths: ['/full'] } },
    'project-order': ['b', 'a'], 'thread-project-assignments': { chat: 'b' },
    'app-server-project-id-by-legacy-project-id-by-host': { local: { b: 'new-b' } }, unrelatedSecret: 'never-return-this' });
  writeFileSync(path, input);
  const result = readProjects(root); assert.equal(result.projects.length, 2); assert.equal(result.projects[0].id, 'new-b');
  assert.equal(result.assignments.chat, 'new-b'); assert.equal(JSON.stringify(result).includes('never-return-this'), false);
  assert.equal(readFileSync(path, 'utf8'), input);
});
test('HTTP and WebSocket require bearer auth and reject browser Origins', async () => {
  const broker = new Broker({ upstream: () => {}, desktop: () => {} });
  const host = createHost({ broker, token: 'test-token', port: 0, codexHome: mkdtempSync(join(tmpdir(), 'codex-host-')), backendPid: 42 });
  const port = await host.listen(); const base = `http://127.0.0.1:${port}`;
  try {
    assert.equal((await fetch(`${base}/v1/host`)).status, 401);
    const headers = { Authorization: 'Bearer test-token' };
    assert.equal((await fetch(`${base}/v1/host`, { headers })).status, 200);
    assert.equal((await fetch(`${base}/v1/host`, { headers: { ...headers, Origin: 'https://example.com' } })).status, 401);
    assert.equal((await fetch(`${base}/healthz`)).status, 200);
    const peer = new WebSocket(`ws://127.0.0.1:${port}/v1/rpc`, { headers });
    const first = once(peer, 'message'); await once(peer, 'open'); assert.equal(JSON.parse((await first)[0]).type, 'hello');
    peer.close(); await once(peer, 'close');
    const unauthorized = new WebSocket(`ws://127.0.0.1:${port}/v1/rpc`);
    const error = await once(unauthorized, 'error'); assert.match(error[0].message, /401/);
  } finally { host.close(); }
});
test('history catalogue pages all providers and child records without changing the database', () => {
  const root = mkdtempSync(join(tmpdir(), 'codex-history-')); const file = join(root, 'state_5.sqlite');
  const db = new DatabaseSync(file);
  db.exec('CREATE TABLE threads(id TEXT,name TEXT,title TEXT,preview TEXT,cwd TEXT,source TEXT,updated_at INTEGER,archived INTEGER,is_pinned INTEGER,project_id TEXT)');
  const insert = db.prepare('INSERT INTO threads VALUES(?,?,?,?,?,?,?,?,?,?)');
  for (let i = 0; i < 65; i++) insert.run(`t${i}`, '', `Chat ${i}`, '', '/project', i === 0 ? '{"subagent":{"thread_spawn":{"parent_thread_id":"parent"}}}' : 'vscode', i, 0, 0, 'p');
  db.close(); const before = readFileSync(file);
  const first = readHistory(root); assert.equal(first.data.length, 50); assert.ok(first.nextCursor);
  const second = readHistory(root, false, first.nextCursor); assert.equal(second.data.length, 15); assert.equal(second.nextCursor, null);
  assert.ok(second.data.some(r => typeof r.source === 'object')); assert.deepEqual(readFileSync(file), before);
  assert.equal(readHistory(root, false, null, 'Chat 0').data.length, 1);
  assert.equal(readHistory(root, false, null, '%').data.length, 0);
});
