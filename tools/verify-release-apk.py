#!/usr/bin/env python3
"""Check a Gradle APK's metadata, signature, CRC, alignment and required DEX contents."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
    bt = sdk / 'build-tools/35.0.0'
    def command(tool, *params):
        return subprocess.check_output([str(bt / tool)] + list(map(str, params)), text=True)
    cert = command('apksigner', 'verify', '--verbose', '--print-certs', args.apk)
    badging = command('aapt2', 'dump', 'badging', args.apk)
    command('zipalign', '-P', '16', '-c', '4', args.apk)
    config = (ROOT / 'app/build.gradle.kts').read_text()
    version_code = int(re.search(r'versionCode\s*=\s*(\d+)', config).group(1))
    version_name = re.search(r'versionName\s*=\s*"([^"]+)"', config).group(1)
    assert f"versionCode='{version_code}'" in badging and f"versionName='{version_name}'" in badging
    assert "minSdkVersion:'29'" in badging and "targetSdkVersion:'35'" in badging
    package = re.search(r"package: name='([^']+)'", badging).group(1)
    assert package in ('ru.fitprogress.ai', 'ru.fitprogress.ai.qa')
    schema = json.loads((ROOT / 'app/schemas/ru.fitprogress.ai.FitDatabase/2.json').read_text())['database']
    required = ['RunService', 'WorkoutTimerService', 'FitDatabase_Impl', 'WorkoutImport', 'SessionTimers', 'PlanBuilder']
    with zipfile.ZipFile(args.apk) as z:
        assert z.testzip() is None
        dex = [z.read(name) for name in z.namelist() if re.fullmatch(r'classes\d*\.dex', name)]
        for name in required:
            assert any(('Lru/fitprogress/ai/' + name + ';').encode() in data for data in dex), name
        assert any(schema['identityHash'].encode() in data for data in dex), 'Room v2 schema missing'
        native = [name for name in z.namelist() if name.endswith('.so')]
        assert any(name.startswith('lib/arm64-v8a/') for name in native)
        abis = sorted({name.split('/')[1] for name in native})
    result = {'package': package, 'versionName': version_name, 'versionCode': version_code,
              'apk': str(args.apk), 'bytes': args.apk.stat().st_size,
              'sha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              'signer_sha256': re.findall(r'Signer #\d+ certificate SHA-256 digest: (\w+)', cert),
              'signature_verified': True, 'zip_crc_verified': True, 'zipalign_16kb': True,
              'native_abis': abis, 'room_schema_version': schema['version'],
              'room_identity_present': True, 'required_classes_present': required,
              'signing_type': 'debug', 'real_world_gps_verified': False}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
