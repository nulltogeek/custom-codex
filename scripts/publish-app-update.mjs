#!/usr/bin/env node
// Run after building and verifying the APKs. Content-addressed APKs remain immutable.
import { readFileSync, writeFileSync, mkdirSync, renameSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { updateManifest, MAX_APK_BYTES } from '../bridge/app-updates.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const directory = process.env.CODEX_APP_UPDATE_DIRECTORY ?? join(root, 'artifacts/updates');
const sdk = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT ?? join(root, '.downloads/android-sdk');
const buildTools = process.env.CODEX_ANDROID_BUILD_TOOLS ?? join(sdk, 'build-tools/35.0.0');
const notesFile = process.argv[2];
if (!notesFile) throw Error('Usage: node scripts/publish-app-update.mjs <release-notes.txt>');
const notes = readFileSync(notesFile, 'utf8').trim();
if (!notes.length || notes.length > 8000) throw Error('Supply release notes up to 8000 characters');
mkdirSync(directory, { recursive: true });
const releases = [];
for (const channel of ['release', 'debug']) {
  const output = join(root, 'app/build/outputs/apk', channel);
  const metadata = JSON.parse(readFileSync(join(output, 'output-metadata.json'), 'utf8'));
  const expectedId = 'dev.nulltogeek.codexremote' + (channel === 'debug' ? '.debug' : '');
  const apk = metadata.elements?.[0];
  if (metadata.applicationId !== expectedId || metadata.elements.length !== 1 ||
      apk?.outputFile !== `app-${channel}.apk` || !Number.isSafeInteger(apk.versionCode) || apk.versionCode < 1)
    throw Error('Unexpected build metadata');
  const bytes = readFileSync(join(output, apk.outputFile));
  if (!bytes.length || bytes.length > MAX_APK_BYTES) throw Error('Invalid APK size');
  const verified = spawnSync(join(buildTools, 'apksigner'), ['verify', join(output, apk.outputFile)], { encoding: 'utf8' });
  if (verified.error || verified.status !== 0) throw Error('APK signature verification failed; configure Android build-tools before publishing');
  const badging = spawnSync(join(buildTools, 'aapt'), ['dump', 'badging', join(output, apk.outputFile)], { encoding: 'utf8' });
  const actual = badging.stdout?.match(/package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/);
  const minSdk = Number(badging.stdout?.match(/sdkVersion:'(\d+)'/)?.[1]);
  if (badging.error || badging.status !== 0 || actual?.[1] !== expectedId || Number(actual?.[2]) !== apk.versionCode ||
      actual?.[3] !== apk.versionName || minSdk !== metadata.minSdkVersionForDexing || minSdk < 30)
    throw Error('APK contents do not match build metadata');
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  const path = join(directory, sha256 + '.apk');
  if (existsSync(path)) {
    if (createHash('sha256').update(readFileSync(path)).digest('hex') !== sha256) throw Error('Published APK changed');
  } else writeFileSync(path, bytes, { flag: 'wx', mode: 0o644 });
  const manifest = { applicationId: expectedId, versionCode: apk.versionCode, versionName: apk.versionName,
    minSdk: metadata.minSdkVersionForDexing, sha256, size: bytes.length, notes };
  releases.push({ channel, manifest });
}
for (const { channel, manifest } of releases) {
  const temp = join(directory, channel + '.json.pending');
  writeFileSync(temp, JSON.stringify(manifest, null, 2) + '\n', { mode: 0o644 });
  renameSync(temp, join(directory, channel + '.json'));
  updateManifest(directory, channel);
  console.log(`Published ${channel} ${manifest.versionName} (code ${manifest.versionCode}, ${manifest.size} bytes).`);
}
