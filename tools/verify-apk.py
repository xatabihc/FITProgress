#!/usr/bin/env python3
"""Verify the built development APK without claiming device execution."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def descriptors(data):
    def u32(offset):
        return struct.unpack_from('<I', data, offset)[0]
    strings = []
    for index in range(u32(56)):
        offset = u32(u32(60) + 4 * index)
        while data[offset] & 128:
            offset += 1
        offset += 1
        strings.append(data[offset:data.index(b'\0', offset)].decode('utf-8', errors='replace'))
    types = [strings[u32(u32(68) + 4 * i)] for i in range(u32(64))]
    return {types[u32(u32(100) + 32 * i)] for i in range(u32(96))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path)
    args = parser.parse_args()
    apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    config = (ROOT / 'app/build.gradle.kts').read_text()
    version_code = int(re.search(r'versionCode\s*=\s*(\d+)', config).group(1))
    version_name = re.search(r'versionName\s*=\s*"([^"]+)"', config).group(1)
    output = ROOT / 'docs/verification' / ('v' + version_name.replace('-dev', ''))
    output.mkdir(parents=True, exist_ok=True)
    tools = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk'))) / 'build-tools/35.0.0'
    def command(tool, *params):
        return subprocess.check_output([str(tools / tool), *map(str, params)], text=True)
    cert = command('apksigner', 'verify', '--verbose', '--print-certs', apk)
    badging = command('aapt2', 'dump', 'badging', apk)
    manifest = command('aapt2', 'dump', 'xmltree', apk, '--file', 'AndroidManifest.xml')
    command('zipalign', '-P', '16', '-c', '4', apk)
    assert f"versionCode='{version_code}'" in badging and f"versionName='{version_name}'" in badging
    assert 'WorkoutTimerService' in manifest and 'FOREGROUND_SERVICE_SPECIAL_USE' in manifest
    fingerprints = json.loads((ROOT / 'app/build/offline-verification/source-fingerprints.json').read_text())
    assert all(hashlib.sha256((ROOT / p).read_bytes()).hexdigest() == sha for p, sha in fingerprints.items()), 'APK/source fingerprint mismatch'
    schema = json.loads((ROOT / 'app/schemas/ru.fitprogress.ai.FitDatabase/2.json').read_text())['database']
    schema_hash = schema['identityHash']; assert schema['version'] == 2
    schema_in_dex = False
    classes = set()
    required = {'Lru/fitprogress/ai/WorkoutTimerService;', 'Lru/fitprogress/ai/RunService;', 'Lru/fitprogress/ai/SessionTimers;', 'Lru/fitprogress/ai/PlanBuilder;', 'Lru/fitprogress/ai/FitDatabase_Impl;', 'Lru/fitprogress/ai/ManualRuns;', 'Lru/fitprogress/ai/GoalTracker;', 'Lru/fitprogress/ai/AdaptiveCoach;', 'Lru/fitprogress/ai/PersonalRow;', 'Lru/fitprogress/ai/ReminderWorker;'}
    native_count = None
    same_signer = None
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None
        dex = [name for name in archive.namelist() if re.fullmatch(r'classes\d*\.dex', name)]
        for name in dex:
            data = archive.read(name)
            classes.update(descriptors(data))
            schema_in_dex = schema_in_dex or schema_hash.encode() in data
        assert required <= classes, required - classes
        assert schema_in_dex, "New Room schema identity is absent from compiled DEX"
        if args.baseline:
            with zipfile.ZipFile(args.baseline) as baseline:
                native = [name for name in baseline.namelist() if name.startswith('lib/') and name.endswith('.so')]
                assert all(archive.read(name) == baseline.read(name) for name in native)
                native_count = len(native)
            old_cert = command('apksigner', 'verify', '--print-certs', args.baseline)
            pattern = r'Signer #1 certificate SHA-256 digest: (\w+)'
            same_signer = re.search(pattern, cert).group(1) == re.search(pattern, old_cert).group(1)
            assert same_signer, 'Debug signing identity changed'
    for name, text in [('apksigner.txt', cert), ('apk-badging.txt', badging), ('apk-manifest.txt', manifest)]:
        (output / name).write_text(text)
    (output / 'source-fingerprints.json').write_text(json.dumps(fingerprints, indent=2) + '\n')
    info = {'applicationId': 'ru.fitprogress.ai', 'versionName': version_name, 'versionCode': version_code, 'path': str(apk), 'bytes': apk.stat().st_size,
            'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'dex_files': len(dex), 'declared_classes': len(classes),
            'required_classes_present': True, 'room_schema_version': 2, 'room_schema_identity': schema_hash, 'room_schema_identity_in_dex': schema_in_dex, 'native_libraries_preserved': native_count, 'same_debug_signer_as_baseline': same_signer,
            'source_fingerprints_match': True, 'zipalign_16kb': True, 'gradle_build_verified': False, 'device_install_verified': False,
            'build_method': 'Kotlin 2.1.20 Compose compiler / javac17 / D8 / AAPT2 / zipalign / apksigner, existing Gradle dependency intermediates'}
    (output / 'apk.json').write_text(json.dumps(info, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(info, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
