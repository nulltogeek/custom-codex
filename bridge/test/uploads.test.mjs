import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, statSync, readFileSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { saveUpload, MAX_UPLOAD } from '../uploads.mjs';
import { createHost } from '../host.mjs';
import { Broker } from '../broker.mjs';

test('file uploads cannot select filesystem paths and text reaches model input', () => {
  const dir = mkdtempSync(join(tmpdir(), 'codex-upload-'));
  const text = Buffer.from('first line\nsecond line');
  const file = saveUpload(dir, text, '../../project/config.md', 'text/markdown');
  assert.equal(file.name, 'config.md'); assert.equal(file.input.type, 'text');
  assert.ok(file.input.text.includes(text.toString()));
  const stored = join(dir, file.id + '.md'); assert.deepEqual(readFileSync(stored), text);
  assert.equal(statSync(stored).mode & 0o777, 0o600); assert.equal(dirname(stored), dir);
  assert.throws(() => saveUpload(dir, Buffer.alloc(0), 'empty', 'text/plain'), /non-empty/);
  assert.throws(() => saveUpload(dir, Buffer.alloc(MAX_UPLOAD + 1), 'large', 'text/plain'), /20 MB/);
});

test('images use native localImage input and binary documents retain their file reference', () => {
  const dir = mkdtempSync(join(tmpdir(), 'codex-upload-image-'));
  const fixture = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4//8/AAX+Av4N70a4AAAAAElFTkSuQmCC', 'base64');
  const image = saveUpload(dir, fixture, 'photo.png', 'image/png');
  assert.equal(image.input.type, 'localImage'); assert.equal(dirname(image.input.path), dir);
  assert.deepEqual(readFileSync(image.input.path), fixture);
  const document = saveUpload(dir, Buffer.from([0, 255, 1]), 'document.pdf', 'application/pdf');
  assert.equal(document.input.type, 'text'); assert.match(document.input.text, /Read this attached file/);
  assert.equal(document.input.text.includes('\ufffd'), false);
});

test('attachment endpoint requires login and enforces file limits before storing bytes', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'codex-upload-http-'));
  const host = createHost({ broker: new Broker({ upstream() {}, desktop() {} }), token: 'test-upload-token', port: 0, uploadDirectory: dir });
  const port = await host.listen(), url = `http://127.0.0.1:${port}/v1/attachments`;
  const headers = { Authorization: 'Bearer test-upload-token', 'Content-Type': 'text/plain', 'X-File-Name': 'notes%20file.txt' };
  try {
    assert.equal((await fetch(url, { method: 'POST', body: 'private text' })).status, 401);
    assert.equal((await fetch(url, { method: 'POST', headers: { ...headers, Origin: 'https://example.com' }, body: 'private text' })).status, 401);
    assert.equal(readdirSync(dir).length, 0);
    const response = await fetch(url, { method: 'POST', headers, body: 'test attachment content' });
    assert.equal(response.status, 201); assert.equal(response.headers.get('cache-control'), 'no-store');
    const attachment = await response.json(); assert.equal(attachment.name, 'notes file.txt'); assert.match(attachment.input.text, /test attachment content/);
    assert.equal((await fetch(url, { method: 'POST', headers, body: '' })).status, 400);
    assert.equal((await fetch(url, { method: 'POST', headers, body: Buffer.alloc(MAX_UPLOAD + 1) })).status, 413);
    assert.equal(readdirSync(dir).length, 1);
  } finally { host.close(); }
});
