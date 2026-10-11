#!/usr/bin/env python3
"""Install task-local Google SDK archives, verifying the official index checksums."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.downloads'
SDK = CACHE / 'android-sdk'
BASE = 'https://redirector.gvt1.com/edgedl/android/repository/'
CACHE.mkdir(exist_ok=True)
SDK.mkdir(exist_ok=True)

def fetch(url, dest):
    args = ['curl', '--fail', '--silent', '--show-error', '--location', '--retry', '2', '--max-time', '300']
    if os.environ.get('CODEX_DOWNLOAD_PROXY'):
        args += ['--proxy', os.environ['CODEX_DOWNLOAD_PROXY']]
    subprocess.run(args + ['--output', str(dest), url], check=True)

index = CACHE / 'sdk-repository.xml'
if not index.exists():
    fetch(BASE + 'repository2-1.xml', index)
tree = ET.parse(index).getroot()
targets = {'platforms;android-35': 'platforms/android-35', 'build-tools;35.0.0': 'build-tools/35.0.0',
           'platform-tools': 'platform-tools', 'cmdline-tools;latest': 'cmdline-tools/latest'}
for package_id, folder in targets.items():
    target = SDK / folder
    if (target / 'source.properties').exists():
        print('Already installed:', package_id, flush=True)
        continue
    candidates = [p for p in tree.iter('remotePackage') if p.attrib.get('path') == package_id]
    if package_id == 'platforms;android-35':
        candidates = [p for p in candidates if
                      any(a.findtext('complete/url') == 'platform-35_r02.zip' for a in p.findall('./archives/archive'))]
    if not candidates:
        raise SystemExit('Package missing from SDK index: ' + package_id)
    p = candidates[-1]
    archive = next(a for a in p.findall('./archives/archive') if a.findtext('host-os') in ('linux', None))
    name = archive.findtext('complete/url')
    expected = archive.findtext('complete/checksum')
    # Reuse the successfully downloaded compatible command-line tools if indexed.
    if package_id == 'cmdline-tools;latest':
        previous = CACHE / 'commandlinetools-linux-13114758_latest.zip'
        for old in tree.iter('remotePackage'):
            for a in old.findall('./archives/archive'):
                if a.findtext('complete/url') == previous.name and previous.exists():
                    name = previous.name
                    expected = a.findtext('complete/checksum')
    destination = CACHE / name
    if not destination.exists():
        print('Downloading:', name, flush=True)
        fetch(BASE + name, destination)
    actual = hashlib.sha1(destination.read_bytes()).hexdigest()
    if actual != expected:
        raise SystemExit('SDK checksum mismatch: ' + name)
    unpack = CACHE / ('unpack-' + package_id.replace(';', '-'))
    unpack.mkdir(exist_ok=True)
    with zipfile.ZipFile(destination) as z:
        for member in z.infolist():
            if not (unpack / member.filename).resolve().is_relative_to(unpack.resolve()):
                raise SystemExit('Invalid archive path')
            z.extract(member, unpack)
            mode = member.external_attr >> 16
            if mode:
                (unpack / member.filename).chmod(mode & 0o777)
    children = [f for f in unpack.iterdir() if f.is_dir()]
    if len(children) != 1:
        raise SystemExit('Unexpected SDK archive structure: ' + name)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(children[0]), str(target))
    print('Verified and installed:', package_id, flush=True)
(ROOT / 'local.properties').write_text('sdk.dir=' + str(SDK) + '\n')
print('Task-local SDK:', SDK)
