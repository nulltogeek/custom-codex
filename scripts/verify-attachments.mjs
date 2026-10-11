#!/usr/bin/env node
// Upload synthetic fixtures; never sends a chat prompt or prints credentials.
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { privateJson } from '../bridge/auth.mjs';

const base = new URL(process.argv[2] ?? 'https://codex.geeksun.ir');
const report = { endpoint: base.origin, passed: false, uploads: [] }; let token, stage = 'login';
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4//8/AAX+Av4N70a4AAAAAElFTkSuQmCC', 'base64');
const fixtures = [
  ['picker-regression.txt', 'text/plain', Buffer.from('Picker lifecycle upload regression fixture.'), 'text'],
  ['picker-regression.png', 'image/png', png, 'localImage'],
  ['picker-regression.bin', 'application/octet-stream', Buffer.alloc(2 * 1024 * 1024, 65), 'text'],
];
try {
  const credentials = privateJson(process.env.CODEX_INITIAL_LOGIN_FILE ?? join(homedir(), '.local/share/custom-codex/initial-login.json'));
  const login = await fetch(new URL('/v1/login', base), { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(credentials), signal: AbortSignal.timeout(15000) });
  assert.equal(login.status, 200); token = (await login.json()).token;
  for (const [name, mimeType, bytes, inputType] of fixtures) {
    stage = `upload ${name}`;
    const response = await fetch(new URL('/v1/attachments', base), { method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': mimeType, 'X-File-Name': encodeURIComponent(name) },
      body: bytes, signal: AbortSignal.timeout(90000) });
    assert.equal(response.status, 201);
    const file = await response.json();
    assert.ok(file.id); assert.equal(file.name, name); assert.equal(file.size, bytes.length);
    assert.equal(file.mimeType, mimeType); assert.equal(file.input.type, inputType);
    if (inputType === 'localImage') assert.ok(file.input.path);
    else if (mimeType === 'text/plain') assert.ok(file.input.text.includes(bytes.toString()));
    report.uploads.push({ name, bytes: bytes.length, status: response.status, inputType });
  }
  stage = 'unauthorized upload';
  const denied = await fetch(new URL('/v1/attachments', base), { method: 'POST', body: 'fixture', signal: AbortSignal.timeout(15000) });
  assert.equal(denied.status, 401);
  stage = 'browser Origin upload';
  const origin = await fetch(new URL('/v1/attachments', base), { method: 'POST', body: 'fixture',
    headers: { Authorization: `Bearer ${token}`, Origin: 'https://example.com' }, signal: AbortSignal.timeout(15000) });
  assert.equal(origin.status, 401);
  report.authenticationEnforced = true; report.browserOriginRejected = true;
  report.noPromptsSent = true; report.passed = true;
} catch (error) {
  report.failure = error.code ?? error.name; report.failureStage = stage;
  if (error.code === 'ERR_ASSERTION') { report.expected = error.expected; report.actual = error.actual; }
  process.exitCode = 1;
}
finally {
  if (token) await fetch(new URL('/v1/logout', base), { method: 'POST', headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(15000) }).catch(() => {});
  console.log(JSON.stringify(report, null, 2));
}
