#!/usr/bin/env python3
"""Install and test only the separate .qa package; never modify the user's normal install."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parent.parent
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
BT = SDK / 'build-tools/35.0.0'
PACKAGE = 'ru.fitprogress.ai.qa'

def command(args, timeout=90):
    result = subprocess.run(list(map(str, args)), capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout + result.stderr

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--test-apk', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    adb = ['adb', '-s', args.serial]
    app = args.apk or ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    tests = args.test_apk or ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    def metadata(path):
        return command([BT / 'aapt2', 'dump', 'badging', path])
    def signer(path):
        output = command([BT / 'apksigner', 'verify', '--print-certs', path])
        return re.findall(r'Signer #\d+ certificate SHA-256 digest: (\w+)', output)
    def install(path, package):
        info = metadata(path)
        assert f"package: name='{package}'" in info, 'Refusing a production or wrong package'
        local = signer(path)
        assert local
        lookup = subprocess.run(adb + ['shell', 'pm', 'path', package], capture_output=True, text=True, timeout=90)
        assert lookup.returncode == 0 or (lookup.returncode == 1 and not (lookup.stdout + lookup.stderr).strip()), 'Package lookup failed'
        installed = lookup.stdout.strip()
        if installed:
            paths = installed.splitlines()
            assert len(paths) == 1, 'Split install is not supported'
            with tempfile.TemporaryDirectory(prefix='fit-qa-signer-') as folder:
                old = Path(folder) / 'installed.apk'
                command(adb + ['pull', paths[0].removeprefix('package:'), old])
                assert signer(old) == local, 'QA signer mismatch; no uninstall or replacement'
        command([BT / 'zipalign', '-P', '16', '-c', '4', path])
        result = command(adb + ['install', '-r', '-t', path], 180)
        assert 'Success' in result
    def instrument(name, extra=None):
        result = command(adb + ['shell', 'am', 'instrument', '-w', '-r'] + (extra or []) + [PACKAGE + '.test/ru.fitprogress.ai.FitTestRunner'], 600)
        (args.output / (name + '.txt')).write_text(result)
        assert re.search(r'OK \(\d+ tests?\)', result), f'{name} did not pass; see output'
        assert 'FAILURES!!!' not in result and 'shortMsg=' not in result
        return result
    install(app, PACKAGE)
    install(tests, PACKAGE + '.test')
    # Only the separate QA package: avoid a system permission dialog during stopwatch tests.
    sdk = int(command(adb + ['shell', 'getprop', 'ro.build.version.sdk']).strip())
    if sdk >= 33:
        command(adb + ['shell', 'pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS'])
    command(adb + ['shell', 'pm', 'grant', PACKAGE, 'android.permission.ACCESS_COARSE_LOCATION'])
    command(adb + ['shell', 'pm', 'grant', PACKAGE, 'android.permission.ACCESS_FINE_LOCATION'])
    result = instrument('instrumentation')
    marker = 'qa-' + uuid.uuid4().hex
    phase = ['-e', 'class', 'ru.fitprogress.ai.AndroidQaPersistenceTest', '-e', 'qaMarker', marker]
    instrument('update-seed', phase + ['-e', 'qaPhase', 'seed'])
    install(app, PACKAGE)
    instrument('update-verify', phase + ['-e', 'qaPhase', 'verify'])
    summary = {'package': PACKAGE, 'sha256': hashlib.sha256(app.read_bytes()).hexdigest(),
               'suite_passed': True, 'update_marker_preserved': True,
               'production_package_modified': False, 'qa_permissions_granted': ['notifications (API33+)', 'coarse location', 'fine location'], 'real_gps_route_tested': False,
               'junit_summary': re.findall(r'OK \(\d+ tests?\)', result)}
    (args.output / 'device-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))

if __name__ == '__main__':
    main()
