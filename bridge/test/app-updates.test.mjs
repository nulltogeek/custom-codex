import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { assertPublishableUpdate, updateManifest } from '../app-updates.mjs';
import { createHost } from '../host.mjs';
import { Broker } from '../broker.mjs';

function fixture() {
  const directory = mkdtempSync(join(tmpdir(), 'codex-app-update-')), bytes = Buffer.from('APK transport fixture');
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  const manifest = { applicationId: 'dev.nulltogeek.codexremote', versionCode: 4, versionName: '0.4.0', sha256,
    size: bytes.length, minSdk: 30, notes: 'In-app updates' };
  writeFileSync(join(directory, sha256 + '.apk'), bytes);
  writeFileSync(join(directory, 'release.json'), JSON.stringify(manifest));
  return { directory, bytes, manifest };
}
test('publication requires a higher build number for changed APKs and prevents downgrades', () => {
  const current = { applicationId: 'dev.nulltogeek.codexremote', versionCode: 12, versionName: '0.7.4', sha256: 'a'.repeat(64) };
  assert.doesNotThrow(() => assertPublishableUpdate(current, { ...current }));
  assert.doesNotThrow(() => assertPublishableUpdate(current, { ...current, versionCode: 13, versionName: '0.7.5', sha256: 'b'.repeat(64) }));
  for (const change of [{ sha256: 'b'.repeat(64) }, { versionCode: 11 }, { versionName: '0.7.5' }]) {
    assert.throws(() => assertPublishableUpdate(current, { ...current, ...change }), /Increase versionCode above 12/);
  }
  assert.throws(() => assertPublishableUpdate(current, { ...current, applicationId: 'different.app', versionCode: 13 }), /application ID/);
});
test('update metadata and immutable downloads use normal host authentication', async () => {
  const { directory, bytes, manifest } = fixture();
  const host = createHost({ broker: new Broker({ upstream() {}, desktop() {} }), token: 'test-update-token', port: 0, updateDirectory: directory });
  const port = await host.listen(), base = `http://127.0.0.1:${port}`;
  const headers = { Authorization: 'Bearer test-update-token' };
  try {
    for (const path of ['/v1/app-update', `/v1/app-update/apk/${manifest.sha256}.apk`]) {
      assert.equal((await fetch(base + path)).status, 401);
      assert.equal((await fetch(base + path, { headers: { ...headers, Origin: 'https://example.com' } })).status, 401);
    }
    const response = await fetch(base + '/v1/app-update?channel=release', { headers });
    assert.equal(response.status, 200); assert.deepEqual(await response.json(), manifest);
    const download = await fetch(base + `/v1/app-update/apk/${manifest.sha256}.apk`, { headers });
    assert.equal(download.headers.get('content-type'), 'application/vnd.android.package-archive');
    assert.equal(download.headers.get('cache-control'), 'no-store');
    assert.equal(Number(download.headers.get('content-length')), bytes.length);
    assert.deepEqual(Buffer.from(await download.arrayBuffer()), bytes);
    assert.equal((await fetch(base + '/v1/app-update?channel=../release', { headers })).status, 400);
    assert.equal((await fetch(base + '/v1/app-update?channel=debug', { headers })).status, 503);
    assert.equal((await fetch(base + '/v1/app-update/apk/not-a-digest.apk', { headers })).status, 404);
    assert.equal((await fetch(base + '/v1/app-update', { method: 'POST', headers })).status, 405);
  } finally { host.close(); }
});
test('published metadata rejects package mixups, invalid sizes and APK symlinks', () => {
  const { directory, bytes, manifest } = fixture();
  for (const replacement of [{ applicationId: 'other.app' }, { size: bytes.length + 1 }, { minSdk: 0 }, { sha256: '../file' }, { versionCode: 0 }]) {
    writeFileSync(join(directory, 'release.json'), JSON.stringify({ ...manifest, ...replacement }));
    assert.throws(() => updateManifest(directory, 'release'));
  }
  const another = 'b'.repeat(64);
  symlinkSync(join(directory, manifest.sha256 + '.apk'), join(directory, another + '.apk'));
  writeFileSync(join(directory, 'release.json'), JSON.stringify({ ...manifest, sha256: another }));
  assert.throws(() => updateManifest(directory, 'release'));
});
