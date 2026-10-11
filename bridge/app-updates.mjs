import { readFileSync, lstatSync, createReadStream } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

export const defaultUpdateDirectory = join(dirname(fileURLToPath(import.meta.url)), '../artifacts/updates');
export const MAX_APK_BYTES = 128 * 1024 * 1024;
const applications = { release: 'dev.nulltogeek.codexremote', debug: 'dev.nulltogeek.codexremote.debug' };

export function updateManifest(directory, channel) {
  if (!Object.hasOwn(applications, channel)) throw Error('Invalid channel');
  const path = join(directory, channel + '.json'), stat = lstatSync(path);
  if (!stat.isFile() || stat.size > 65536) throw Error('Invalid manifest');
  const raw = JSON.parse(readFileSync(path, 'utf8'));
  if (raw.applicationId !== applications[channel] || !Number.isSafeInteger(raw.versionCode) || raw.versionCode < 1 ||
      typeof raw.versionName !== 'string' || !raw.versionName.length || raw.versionName.length > 80 ||
      !/^[a-f0-9]{64}$/.test(raw.sha256) || !Number.isSafeInteger(raw.size) || raw.size < 1 || raw.size > MAX_APK_BYTES ||
      !Number.isSafeInteger(raw.minSdk) || raw.minSdk < 30 || typeof raw.notes !== 'string' || raw.notes.length > 8000)
    throw Error('Invalid manifest');
  const apk = lstatSync(join(directory, raw.sha256 + '.apk'));
  if (!apk.isFile() || apk.size !== raw.size) throw Error('Release unavailable');
  return { applicationId: raw.applicationId, versionCode: raw.versionCode, versionName: raw.versionName,
    sha256: raw.sha256, size: raw.size, minSdk: raw.minSdk, notes: raw.notes };
}

// Called only after the host's normal session authentication.
export function serveAppUpdate(url, res, directory, reply) {
  if (url.pathname === '/v1/app-update') {
    const channel = url.searchParams.get('channel') ?? 'release';
    if (!Object.hasOwn(applications, channel)) return reply(400, { error: 'Invalid update channel' });
    try { return reply(200, updateManifest(directory, channel)); }
    catch { return reply(503, { error: 'No app update has been published yet' }); }
  }
  const match = /^\/v1\/app-update\/apk\/([a-f0-9]{64})\.apk$/.exec(url.pathname);
  if (!match) return reply(404, { error: 'Update not found' });
  try {
    const path = join(directory, match[1] + '.apk'), stat = lstatSync(path);
    if (!stat.isFile() || stat.size < 1 || stat.size > MAX_APK_BYTES) throw Error('Invalid APK');
    res.setHeader('Content-Type', 'application/vnd.android.package-archive');
    res.setHeader('Content-Length', stat.size);
    res.setHeader('Content-Disposition', 'attachment; filename="codex-remote.apk"');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    const stream = createReadStream(path);
    stream.on('error', () => res.destroy()); res.on('close', () => stream.destroy());
    stream.pipe(res);
  } catch { reply(404, { error: 'Update not found' }); }
}
