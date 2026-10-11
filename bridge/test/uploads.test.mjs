import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, statSync, readFileSync, readdirSync, writeFileSync, symlinkSync, openSync, closeSync, ftruncateSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
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

test('image previews authenticate and return existing uploaded image bytes with private response headers', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'codex-image-preview-'));
  const fixture = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4//8/AAX+Av4N70a4AAAAAElFTkSuQmCC', 'base64');
  const image = saveUpload(dir, fixture, 'photo.png', 'image/png');
  const host = createHost({ broker: new Broker({ upstream() {}, desktop() {} }), token: 'test-image-token', port: 0, uploadDirectory: dir });
  const port = await host.listen(), base = `http://127.0.0.1:${port}/v1/attachments/image`;
  const url = `${base}?path=${encodeURIComponent(image.input.path)}`;
  const headers = { Authorization: 'Bearer test-image-token' };
  try {
    assert.equal((await fetch(url)).status, 401);
    assert.equal((await fetch(url, { headers: { ...headers, Origin: 'https://example.com' } })).status, 401);
    const response = await fetch(url, { headers });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('content-type'), 'image/png');
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), fixture);
    assert.equal((await fetch(url, { method: 'POST', headers })).status, 405);
    // Files from before previews existed need no new metadata or filename extension.
    const oldPath = join(dir, randomUUID());
    writeFileSync(oldPath, fixture, { mode: 0o600 });
    const old = await fetch(`${base}?path=${encodeURIComponent(oldPath)}`, { headers });
    assert.equal(old.status, 200);
    assert.deepEqual(Buffer.from(await old.arrayBuffer()), fixture);
  } finally { host.close(); }
});

test('image previews reject traversal, symlinks, unknown names, nonimages and oversized files', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'codex-image-confined-'));
  const outsideDir = mkdtempSync(join(tmpdir(), 'codex-image-outside-'));
  const fixture = Buffer.from([255, 216, 255, 224, 0, 0]);
  const outside = join(outsideDir, randomUUID() + '.jpg');
  writeFileSync(outside, fixture, { mode: 0o600 });
  const linked = join(dir, randomUUID() + '.jpg');
  symlinkSync(outside, linked);
  const unknown = join(dir, 'private.jpg');
  writeFileSync(unknown, fixture, { mode: 0o600 });
  const document = saveUpload(dir, Buffer.from('<svg>private document</svg>'), 'fake.png', 'image/png');
  const oversized = join(dir, randomUUID() + '.jpg');
  const fd = openSync(oversized, 'wx', 0o600);
  try { ftruncateSync(fd, MAX_UPLOAD + 1); } finally { closeSync(fd); }
  const host = createHost({ broker: new Broker({ upstream() {}, desktop() {} }), token: 'confined-token', port: 0, uploadDirectory: dir });
  const port = await host.listen(), base = `http://127.0.0.1:${port}/v1/attachments/image`;
  const headers = { Authorization: 'Bearer confined-token' };
  try {
    for (const path of [outside, linked, unknown, document.input.path, oversized,
      join(dir, randomUUID() + '.jpg'), `${dir}/../${outsideDir.split('/').at(-1)}/${outside.split('/').at(-1)}`,
      `${dir}/nested/../${document.id}.png`, '../private.jpg', '']) {
      const response = await fetch(`${base}?path=${encodeURIComponent(path)}`, { headers });
      assert.equal(response.status, 404, `must reject ${path}`);
      assert.deepEqual(await response.json(), { error: 'Image attachment unavailable' });
    }
    assert.equal((await fetch(base, { headers })).status, 404);
  } finally { host.close(); }
});

test('image preview media types come from supported signatures rather than names or upload headers', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'codex-image-types-'));
  const host = createHost({ broker: new Broker({ upstream() {}, desktop() {} }), token: 'types-token', port: 0, uploadDirectory: dir });
  const port = await host.listen(), base = `http://127.0.0.1:${port}/v1/attachments/image`;
  try {
    for (const [bytes, mime] of [[Buffer.from([255, 216, 255]), 'image/jpeg'],
      [Buffer.from('GIF89a'), 'image/gif'], [Buffer.from('GIF87a'), 'image/gif'],
      [Buffer.from('RIFF0000WEBP'), 'image/webp']]) {
      const file = saveUpload(dir, bytes, 'wrong.txt', 'text/plain');
      const path = join(dir, file.id + '.txt');
      const response = await fetch(`${base}?path=${encodeURIComponent(path)}`, { headers: { Authorization: 'Bearer types-token' } });
      assert.equal(response.status, 200);
      assert.equal(response.headers.get('content-type'), mime);
      assert.deepEqual(Buffer.from(await response.arrayBuffer()), bytes);
    }
  } finally { host.close(); }
});
