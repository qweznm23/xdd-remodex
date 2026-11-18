#!/usr/bin/env python3
"""Validate a release; publish only with --publish. Never installs on a device."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--publish', action='store_true')
parser.add_argument('--notes', required=True)
args = parser.parse_args()
app = root/'android/app'
downloads = root/'.local/artifacts/downloads'
apk = app/'build/outputs/apk/release/app-release.apk'
meta = json.loads((apk.parent/'output-metadata.json').read_text())
assert meta['variantName'] == 'release'
version = meta['elements'][0]
name = version['versionName']
assert re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', name)
log = root/'.local/logs/android-release.log'
assert 'BUILD SUCCESSFUL' in log.read_text() and log.stat().st_mtime >= apk.stat().st_mtime, 'Run make android-release first'
reports = list((app/'build/test-results/testReleaseUnitTest').glob('TEST-*.xml'))
counts = {k: sum(int(ET.parse(p).getroot().get(k, 0)) for p in reports) for k in ('tests', 'failures', 'errors', 'skipped')}
assert counts['tests'] > 0 and counts['failures'] == counts['errors'] == counts['skipped'] == 0, counts
sdk = Path(os.environ['ANDROID_HOME'])/'build-tools'/os.environ.get('ANDROID_BUILD_TOOLS', '35.0.0')
def certificate(path):
    result = subprocess.check_output([str(sdk/'apksigner'), 'verify', '--print-certs', str(path)], text=True)
    digest = re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)', result)
    assert digest, 'Cannot verify APK signer'
    return digest.group(1)
cert = certificate(apk)
badging = subprocess.check_output([str(sdk/'aapt'), 'dump', 'badging', str(apk)], text=True)
assert 'application-debuggable' not in badging
old_raw = (downloads/'update.json').read_bytes()
old = json.loads(old_raw)
assert meta['applicationId'] == old['packageName']
assert version['versionCode'] > old['versionCode'], 'Version must increase; existing release is not overwritten'
previous_apk = downloads/Path(old['apkUrl']).name
assert certificate(previous_apk) == cert, 'Signing identity changed'
payload = apk.read_bytes()
digest = hashlib.sha256(payload).hexdigest()
filename = f'remodex-{name}.apk'
result = {'version': name, 'versionCode': version['versionCode'], 'sha256': digest, 'sizeBytes': len(payload), 'tests': counts}
if not args.publish:
    print(json.dumps({**result, 'published': False, 'next': 'Repeat with --publish to update the local download service'}))
    raise SystemExit(0)
assert (downloads/'update.json').read_bytes() == old_raw, 'Another release was published during validation'
archive = root/'.local/artifacts/releases'/name
archive.mkdir(parents=True, exist_ok=False)
(archive/'previous-update.json').write_bytes(old_raw)
def atomic(path, data):
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_bytes(data)
    temporary.replace(path)
atomic(downloads/filename, payload)
for file in ('mapping.txt', 'usage.txt'):
    shutil.copy2(app/'build/outputs/mapping/release'/file, archive/file)
(archive/'validation.json').write_text(json.dumps(result, indent=2)+'\n')
(archive/'apk-sha256.txt').write_text(digest+'  '+filename+'\n')
index = downloads/'index.html'
if index.exists():
    html = index.read_text().replace(old['versionName'], name).replace(f'（{old["versionCode"]}）', f'（{version["versionCode"]}）')
    atomic(index, html.encode())
checksums = ''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in sorted(downloads.glob('*.apk')))
atomic(downloads/'SHA256SUMS', checksums.encode())
manifest = {**old, 'versionCode': version['versionCode'], 'versionName': name, 'apkUrl': filename,
            'sha256': digest, 'sizeBytes': len(payload), 'notes': args.notes}
atomic(downloads/'update.json', (json.dumps(manifest, ensure_ascii=False, indent=2)+'\n').encode())
print(json.dumps({**result, 'published': True}))
