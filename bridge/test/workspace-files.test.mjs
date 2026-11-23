import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, symlinkSync, linkSync, chmodSync, statSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { workspaceFiles } from '../workspace-files.mjs';
import { createHost } from '../host.mjs';
import { Broker } from '../broker.mjs';

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'workspace-files-')); t.after(() => rmSync(root, { recursive: true, force: true }));
  const projectRoot = join(root, 'project'); mkdirSync(projectRoot);
  const catalogue = { projects: [{ id: 'project', rootPaths: [projectRoot] }] };
  const request = (action, path = '', extra = {}) => workspaceFiles({}, catalogue, { action, projectId: 'project', rootIndex: 0, path, ...extra });
  return { root, projectRoot, catalogue, request };
}
test('project files browse nested directories, search names and save exact UTF-8 content with revision checks', async t => {
  const f = fixture(t); mkdirSync(join(f.projectRoot, 'src')); writeFileSync(join(f.projectRoot, 'src', 'hello.kt'), 'old\r\n', { mode: 0o755 });
  assert.equal((await f.request('list')).entries[0].kind, 'directory');
  assert.equal((await f.request('search', '', { query: 'HELLO' })).entries[0].path, 'src/hello.kt');
  const before = await f.request('read', 'src/hello.kt'); assert.equal(before.content, 'old\r\n');
  const content = 'println("سلام 🌍")\r\n\t// `shell` $(no execution)\n';
  const saved = await f.request('write', 'src/hello.kt', { revision: before.revision, content });
  assert.notEqual(saved.revision, before.revision); assert.equal(readFileSync(join(f.projectRoot, 'src/hello.kt'), 'utf8'), content);
  assert.equal(statSync(join(f.projectRoot, 'src/hello.kt')).mode & 0o777, 0o755);
  await assert.rejects(f.request('write', 'src/hello.kt', { revision: before.revision, content: 'stale edit' }), { status: 409 });
  assert.equal((await f.request('read', 'src/hello.kt')).content, content);
});
test('project paths reject traversal, symlinks, other project IDs and caller-selected roots', async t => {
  const f = fixture(t); writeFileSync(join(f.root, 'private'), 'secret');
  symlinkSync(join(f.root, 'private'), join(f.projectRoot, 'link')); symlinkSync(f.root, join(f.projectRoot, 'outside'));
  for (const path of ['../private', '/etc/passwd', 'outside/private', 'link', 'src/../../private'])
    await assert.rejects(f.request('read', path));
  await assert.rejects(f.request('read', 'anything', { projectId: 'other' }), { status: 404 });
  await assert.rejects(f.request('read', 'anything', { root: f.root }), { status: 400 });
  assert.equal((await f.request('list')).entries.find(row => row.name === 'link').kind, 'link');
  assert.deepEqual((await f.request('search', '', { query: 'private' })).entries, []);
});
test('file writes replace hard links without modifying their other targets', async t => {
  const f = fixture(t); const original = join(f.root, 'outside'); writeFileSync(original, 'outside content'); linkSync(original, join(f.projectRoot, 'linked'));
  const before = await f.request('read', 'linked');
  await f.request('write', 'linked', { revision: before.revision, content: 'project content' });
  assert.equal(readFileSync(original, 'utf8'), 'outside content'); assert.equal(readFileSync(join(f.projectRoot, 'linked'), 'utf8'), 'project content');
});
test('simultaneous saves from two phones cannot overwrite each other with the same revision', async t => {
  const f = fixture(t); writeFileSync(join(f.projectRoot, 'shared.txt'), 'original');
  const before = await f.request('read', 'shared.txt');
  const results = await Promise.allSettled(['phone one', 'phone two'].map(content => f.request('write', 'shared.txt', { revision: before.revision, content })));
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 1);
  assert.equal(results.find(result => result.status === 'rejected').reason.status, 409);
});
test('editor rejects binary, invalid UTF-8, oversized and read-only files; bounded large text survives', async t => {
  const f = fixture(t);
  for (const [name, data, status] of [['binary', Buffer.from([0, 1, 2]), 415], ['encoding', Buffer.from([255]), 415], ['large', Buffer.alloc(262145, 97), 413]]) {
    writeFileSync(join(f.projectRoot, name), data); await assert.rejects(f.request('read', name), { status });
  }
  const content = 'あ'.repeat(80000); writeFileSync(join(f.projectRoot, 'text'), content);
  assert.equal((await f.request('read', 'text')).content, content);
  chmodSync(join(f.projectRoot, 'text'), 0o444); const file = await f.request('read', 'text'); assert.equal(file.writable, false);
  await assert.rejects(f.request('write', 'text', { revision: file.revision, content: 'changed' }), { status: 403 });
});
test('folder listings page consistently and private roots must remain within workspace', async t => {
  const f = fixture(t); for (let i = 0; i < 205; i++) writeFileSync(join(f.projectRoot, `file-${String(i).padStart(3, '0')}`), '');
  const page = await f.request('list'); assert.equal(page.entries.length, 200); assert.equal(page.nextOffset, 200);
  const next = await f.request('list', '', { offset: page.nextOffset }); assert.equal(next.entries.length, 5); assert.equal(next.nextOffset, null);
  await assert.rejects(workspaceFiles({ workspaceRoot: join(f.root, 'other') }, f.catalogue,
    { action: 'list', path: '', rootIndex: 0, projectId: 'project' }), { status: 403 });
});
test('file HTTP requests require authentication, advertise capability and validate project-bound writes', async t => {
  const f = fixture(t); writeFileSync(join(f.projectRoot, 'file.txt'), 'before');
  const broker = new Broker({ upstream: () => {}, desktop: () => {} });
  const host = createHost({ broker, token: 'fixture-token', port: 0, runtimeMode: 'standalone', codexHome: f.root,
    liveProjects: () => f.catalogue.projects }); await host.listen(); t.after(() => host.close());
  const base = `http://127.0.0.1:${host.server.address().port}`;
  const headers = { Authorization: 'Bearer fixture-token', 'Content-Type': 'application/json' };
  const input = { action: 'read', projectId: 'project', rootIndex: 0, path: 'file.txt' };
  assert.equal((await fetch(base + '/v1/files', { method: 'POST', body: JSON.stringify(input) })).status, 401);
  assert.equal((await (await fetch(base + '/v1/host', { headers })).json()).capabilities.projectFiles, true);
  const response = await fetch(base + '/v1/files', { method: 'POST', headers, body: JSON.stringify(input) });
  assert.equal(response.status, 200); assert.equal((await response.json()).content, 'before');
  assert.equal((await fetch(base + '/v1/files', { method: 'POST', headers: { ...headers, Origin: 'https://foreign.example' }, body: JSON.stringify(input) })).status, 401);
  assert.equal((await fetch(base + '/v1/files', { method: 'POST', headers, body: '{}' })).status, 400);
});
