#!/usr/bin/env bash
# Publish locally built APKs to the live updater without restarting the backend.
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/local-env.sh"
cd "$task_project"
case "${1:---preview}" in
  --preview|--apply) task_mode="${1:---preview}" ;;
  *) echo 'Usage: deploy-app-updates.sh [--preview|--apply]' >&2; exit 2 ;;
esac
task_host=nulltogeek@192.168.1.7
task_remote=/home/nulltogeek/Desktop/nulltogeek/custom-codex
task_payload="$(mktemp -d "$task_project/artifacts/.update-deploy.XXXXXX")"
trap 'rm -rf -- "$task_payload"' EXIT

node --input-type=module - "$task_payload" <<'LOCAL'
import assert from 'node:assert/strict';
import { readFileSync, copyFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { updateManifest } from './bridge/app-updates.mjs';
const source = resolve(process.env.CODEX_APP_UPDATE_DIRECTORY ?? 'artifacts/updates');
const tools = process.env.CODEX_ANDROID_BUILD_TOOLS ?? join(process.env.ANDROID_HOME, 'build-tools/35.0.0');
const destination = process.argv[2], certificates = {};
for (const channel of ['release', 'debug']) {
  const manifest = updateManifest(source, channel);
  const apk = join(source, manifest.sha256 + '.apk');
  assert.equal(createHash('sha256').update(readFileSync(apk)).digest('hex'), manifest.sha256, 'APK checksum mismatch');
  const signature = execFileSync(join(tools, 'apksigner'), ['verify', '--print-certs', apk], { encoding: 'utf8' });
  const signers = [...signature.matchAll(/certificate SHA-256 digest: ([a-f0-9]{64})/g)].map(match => match[1]).sort();
  assert.ok(signers.length, 'APK signing certificate missing');
  certificates[channel] = signers;
  const badging = execFileSync(join(tools, 'aapt'), ['dump', 'badging', apk], { encoding: 'utf8' });
  const actual = badging.match(/package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/);
  assert.equal(actual?.[1], manifest.applicationId);
  assert.equal(Number(actual?.[2]), manifest.versionCode);
  assert.equal(actual?.[3], manifest.versionName);
  assert.equal(Number(badging.match(/sdkVersion:'(\d+)'/)?.[1]), manifest.minSdk);
  copyFileSync(apk, join(destination, manifest.sha256 + '.apk'));
  // Preserve the exact manifest validated above, not a second read of a mutable file.
  writeFileSync(join(destination, channel + '.json'), JSON.stringify(manifest, null, 2) + '\n');
  console.log(`Validated ${channel} ${manifest.versionName} (code ${manifest.versionCode}).`);
}
writeFileSync(join(destination, 'signing-certificates.json'), JSON.stringify(certificates, null, 2) + '\n');
LOCAL

if [[ "$task_mode" == --preview ]]; then
  rsync -azni --checksum "$task_payload/" "$task_host:$task_remote/artifacts/updates/"
  echo 'Preview only. Use --apply to publish the live updater feed.'
  exit 0
fi
task_stamp="$(date -u +%Y%m%dT%H%M%SZ)-$$"
task_stage="$task_remote/artifacts/update-publications/$task_stamp"
ssh -o BatchMode=yes -o ConnectTimeout=15 "$task_host" "umask 077; mkdir -p '$task_stage'"
rsync -az --checksum "$task_payload/" "$task_host:$task_stage/"
ssh -o BatchMode=yes -o ConnectTimeout=15 "$task_host" python3 - "$task_stage" <<'REMOTE'
import fcntl, hashlib, json, os, re, shutil, stat, sys
from pathlib import Path

root = Path('/home/nulltogeek/Desktop/nulltogeek/custom-codex')
stage = Path(sys.argv[1])
assert stage.parent == root / 'artifacts/update-publications'
directory = root / 'artifacts/updates'
directory.mkdir(parents=True, exist_ok=True)
lock = (stage.parent / 'publish.lock').open('a')
fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
certificates = json.loads((stage / 'signing-certificates.json').read_text())
marker = directory / 'signing-certificates.json'
if marker.exists():
    assert json.loads(marker.read_text()) == certificates, 'Signing key changed; rebuild with the published key'

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

manifests = {}
previous = {}
for channel in ['release', 'debug']:
    name = channel + '.json'
    update = json.loads((stage / name).read_text())
    expected_id = 'dev.nulltogeek.codexremote' + ('.debug' if channel == 'debug' else '')
    assert update['applicationId'] == expected_id
    assert type(update['versionCode']) is int and update['versionCode'] > 0
    assert re.fullmatch('[a-f0-9]{64}', update['sha256'])
    assert certificates[channel] and all(re.fullmatch('[a-f0-9]{64}', value) for value in certificates[channel])
    apk = stage / (update['sha256'] + '.apk')
    assert stat.S_ISREG(apk.lstat().st_mode)
    assert apk.stat().st_size == update['size'] and digest(apk) == update['sha256']
    current = directory / name
    previous[name] = current.read_bytes() if current.exists() else None
    if previous[name] is not None:
        old = json.loads(previous[name])
        assert old['applicationId'] == expected_id
        assert update['versionCode'] > old['versionCode'] or (
            update['versionCode'] == old['versionCode'] and update['sha256'] == old['sha256']
            and update['versionName'] == old['versionName']), 'Refusing a changed or older version'
    manifests[channel] = update
previous[marker.name] = marker.read_bytes() if marker.exists() else None
backup = stage / 'previous'
backup.mkdir()
for name, data in previous.items():
    if data is not None:
        (backup / name).write_bytes(data)

# All APKs must be present and checked before either discovery manifest changes.
for update in manifests.values():
    name = update['sha256'] + '.apk'
    target = directory / name
    if target.exists() or target.is_symlink():
        assert stat.S_ISREG(target.lstat().st_mode) and digest(target) == update['sha256'], 'Published APK changed'
        (stage / name).unlink()
    else:
        os.replace(stage / name, target)

try:
    for name in previous:
        pending = directory / (name + '.pending-' + stage.name)
        shutil.copyfile(stage / name, pending)
        os.replace(pending, directory / name)
    for channel, update in manifests.items():
        assert json.loads((directory / (channel + '.json')).read_text()) == update
        assert digest(directory / (update['sha256'] + '.apk')) == update['sha256']
except BaseException:
    for name, data in previous.items():
        target = directory / name
        if data is None:
            target.unlink(missing_ok=True)
        else:
            pending = directory / (name + '.rollback-' + stage.name)
            pending.write_bytes(data)
            os.replace(pending, target)
    raise
report = {'published': True, 'backup': str(backup), 'backendRestarted': False,
          'channels': {channel: {'versionName': update['versionName'], 'versionCode': update['versionCode'],
                                'sha256': update['sha256'], 'size': update['size']}
                       for channel, update in manifests.items()}, 'signingCertificates': certificates}
(stage / 'receipt.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
REMOTE
