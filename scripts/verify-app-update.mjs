#!/usr/bin/env node
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { createHash } from 'node:crypto';
import { privateJson } from '../bridge/auth.mjs';
import { updateManifest, defaultUpdateDirectory } from '../bridge/app-updates.mjs';

const base = new URL(process.argv[2] ?? 'https://codex.geeksun.ir');
const login = privateJson(process.env.CODEX_INITIAL_LOGIN_FILE ?? join(homedir(), '.local/share/custom-codex/initial-login.json'));
const report = { endpoint: base.origin, passed: false, channels: [] }; let token;
const headers = () => ({ Authorization: `Bearer ${token}` });
try {
  const response = await fetch(new URL('/v1/login', base), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(login), signal: AbortSignal.timeout(15000) });
  assert.equal(response.status, 200); token = (await response.json()).token;
  assert.equal((await fetch(new URL('/v1/app-update', base), { signal: AbortSignal.timeout(15000) })).status, 401);
  assert.equal((await fetch(new URL('/v1/app-update', base), { headers: { ...headers(), Origin: 'https://example.com' }, signal: AbortSignal.timeout(15000) })).status, 401);
  for (const channel of ['release', 'debug']) {
    const metadata = await fetch(new URL('/v1/app-update?channel=' + channel, base), { headers: headers(), signal: AbortSignal.timeout(15000) });
    assert.equal(metadata.status, 200); const update = await metadata.json();
    assert.match(update.sha256, /^[a-f0-9]{64}$/);
    assert.equal(update.applicationId, 'dev.nulltogeek.codexremote' + (channel === 'debug' ? '.debug' : ''));
    if (process.argv.includes('--local')) assert.deepEqual(update, updateManifest(process.env.CODEX_APP_UPDATE_DIRECTORY ?? defaultUpdateDirectory, channel));
    const path = '/v1/app-update/apk/' + update.sha256 + '.apk';
    assert.equal((await fetch(new URL(path, base), { signal: AbortSignal.timeout(15000) })).status, 401);
    const download = await fetch(new URL(path, base), { headers: headers(), redirect: 'error', signal: AbortSignal.timeout(900000) });
    assert.equal(download.status, 200); assert.equal(Number(download.headers.get('content-length')), update.size);
    assert.equal(download.headers.get('content-type'), 'application/vnd.android.package-archive');
    let size = 0, nextProgress = 10; const digest = createHash('sha256');
    report.downloadProgress = { channel, bytes: 0, total: update.size };
    for await (const part of download.body) {
      size += part.length; assert.ok(size <= update.size); digest.update(part); report.downloadProgress.bytes = size;
      if (100 * size / update.size >= nextProgress) { console.error(`${channel} APK download: ${Math.floor(100 * size / update.size)}%`); nextProgress += 10; }
    }
    assert.equal(size, update.size); assert.equal(digest.digest('hex'), update.sha256);
    report.channels.push({ channel, versionCode: update.versionCode, versionName: update.versionName, size, checksumVerified: true });
    delete report.downloadProgress;
  }
  report.unauthorizedRequestsRejected = true; report.browserOriginRejected = true; report.passed = true;
} catch (error) { report.failure = error.code ?? error.name; process.exitCode = 1; }
finally {
  if (token) await fetch(new URL('/v1/logout', base), { method: 'POST', headers: headers(), signal: AbortSignal.timeout(15000) }).catch(() => {});
  console.log(JSON.stringify(report, null, 2));
}
