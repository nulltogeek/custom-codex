#!/usr/bin/env python3
"""Optional task-local software emulator for Android 11 validation."""
import hashlib
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.downloads'
SDK = CACHE / 'android-sdk'
BASE = 'https://redirector.gvt1.com/edgedl/android/repository/'

def fetch(url, dest):
    subprocess.run(['curl', '--fail', '--silent', '--show-error', '--location', '--retry', '2', '--max-time', '600', '--output', str(dest), url], check=True)

def install(package, prefix, dest):
    archive = next(a for a in package.findall('./archives/archive') if a.findtext('host-os') in ('linux', None))
    name = archive.findtext('complete/url'); path = CACHE / Path(name).name
    if not path.exists():
        print('Downloading:', name, flush=True); fetch(BASE + prefix + name, path)
    if hashlib.sha1(path.read_bytes()).hexdigest() != archive.findtext('complete/checksum'):
        raise SystemExit('Emulator archive checksum mismatch')
    unpack = CACHE / ('unpack-emulator' if not prefix else 'unpack-image')
    unpack.mkdir(exist_ok=True)
    with zipfile.ZipFile(path) as z:
        for member in z.infolist():
            target = (unpack / member.filename).resolve()
            if not target.is_relative_to(unpack.resolve()): raise SystemExit('Invalid archive path')
            z.extract(member, unpack)
            mode = member.external_attr >> 16
            if mode: target.chmod(mode & 0o777)
    children = [p for p in unpack.iterdir() if p.is_dir()]
    if len(children) != 1: raise SystemExit('Unexpected emulator package structure')
    dest.parent.mkdir(parents=True, exist_ok=True); shutil.move(str(children[0]), str(dest))

if not (SDK / 'emulator/emulator').exists():
    packages = [p for p in ET.parse(CACHE / 'sdk-repository.xml').getroot().iter('remotePackage')
                if p.attrib.get('path') == 'emulator' and p.find('channelRef').attrib.get('ref') == 'channel-0']
    install(packages[0], '', SDK / 'emulator')
index = CACHE / 'aosp-images.xml'
if not index.exists(): fetch(BASE + 'sys-img/android/sys-img2-1.xml', index)
image = SDK / 'system-images/android-30/default/x86_64'
if not (image / 'system.img').exists():
    package = next(p for p in ET.parse(index).getroot().iter('remotePackage') if p.attrib.get('path') == 'system-images;android-30;default;x86_64')
    install(package, 'sys-img/android/', image)
avds = CACHE / 'avd'; folder = avds / 'codex-android11.avd'; folder.mkdir(parents=True, exist_ok=True)
(avds / 'codex-android11.ini').write_text('avd.ini.encoding=UTF-8\npath=' + str(folder) + '\ntarget=android-30\n')
(folder / 'config.ini').write_text('''AvdId=codex-android11
avd.ini.displayname=Codex Android 11
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.ramSize=2048
hw.lcd.width=1080
hw.lcd.height=1920
hw.lcd.density=420
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
disk.dataPartition.size=2G
tag.id=default
tag.display=Default
showDeviceFrame=no
image.sysdir.1=''' + str(image) + '\n')
print('Verified emulator and Android 11 image installed. ANDROID_AVD_HOME=' + str(avds))
