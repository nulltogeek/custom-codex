import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, symlinkSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { chatFile, chatFileReferences, localChatPath } from '../chat-files.mjs';

test('chat file links support images, spaces, balanced parentheses, sandbox URLs and line suffixes', () => {
  assert.deepEqual(chatFileReferences('![Image](sandbox:/tmp/a.png) [PDF](</tmp/My report.pdf>) [Code](src/a(b).kt:12)'),
    ['sandbox:/tmp/a.png', '/tmp/My report.pdf', 'src/a(b).kt:12']);
  assert.equal(localChatPath('sandbox:/tmp/My%20image.png', '/workspace'), '/tmp/My image.png');
  assert.equal(localChatPath('/workspace/main.py:12', '/workspace'), '/workspace/main.py');
  assert.equal(localChatPath('main.kt:12', '/workspace'), '/workspace/main.kt');
  assert.equal(localChatPath('output/report.pdf', '/workspace'), '/workspace/output/report.pdf');
  assert.throws(() => localChatPath('https://example.com/a', '/workspace'));
});
test('only files referenced by the requested assistant reply can be downloaded', async t => {
  const root = mkdtempSync(join(tmpdir(), 'chat-artifact-')); t.after(() => rmSync(root, { recursive: true, force: true }));
  const bytes = Buffer.from([137,80,78,71,0,1,2,3]); writeFileSync(join(root, 'image.png'), bytes);
  writeFileSync(join(root, 'report.pdf'), '%PDF-fixture'); symlinkSync('/etc/passwd', join(root, 'link'));
  const thread = { cwd: root, turns: [{ id: 'turn', items: [{ id: 'reply', type: 'agentMessage', text: '![Generated](image.png) [Report](report.pdf) [Link](link)' }] }] };
  const context = { call: async (_method, params) => { if (params.threadId !== 'mine') throw Error('Unknown thread'); return { thread }; } };
  const input = { threadId: 'mine', turnId: 'turn', itemId: 'reply', source: 'image.png' };
  assert.deepEqual((await chatFile(context, input)).bytes, bytes);
  assert.equal((await chatFile(context, { ...input, source: 'report.pdf' })).bytes.toString(), '%PDF-fixture');
  await assert.rejects(chatFile(context, { ...input, source: '/etc/passwd' }), { status: 403 });
  await assert.rejects(chatFile(context, { ...input, threadId: 'other-user' }), { status: 404 });
  await assert.rejects(chatFile(context, { ...input, itemId: 'other' }), { status: 403 });
  await assert.rejects(chatFile(context, { ...input, source: 'link' }), { status: 403 });
});
test('native generated images and paginated replies are supported; oversized outputs are rejected', async t => {
  const root = mkdtempSync(join(tmpdir(), 'chat-image-')); t.after(() => rmSync(root, { recursive: true, force: true }));
  const path = join(root, 'generated.png'); writeFileSync(path, Buffer.alloc(8 * 1024 * 1024 + 1));
  const context = { call: async method => method === 'thread/read' ? { thread: { cwd: root, historyMode: 'paginated' } } :
    { data: [{ turnId: 'turn', item: { id: 'image', type: 'imageGeneration', savedPath: path } }] } };
  const input = { threadId: 'thread', turnId: 'turn', itemId: 'image', source: path };
  await assert.rejects(chatFile(context, input), { status: 413 });
  writeFileSync(path, 'image bytes'); assert.equal((await chatFile(context, input)).name, 'generated.png');
});
