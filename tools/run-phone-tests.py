#!/usr/bin/env python3
"""Validate and install test APKs without uninstalling/clearing application history. Requires an idle test phone."""
import argparse
from pathlib import Path
import os
import re
import subprocess

root = Path(__file__).resolve().parent.parent
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True, help='ADB device serial')
p.add_argument('--run', action='store_true', help='install -r and execute tests; stops the application process')
a = p.parse_args()
bt = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk'))) / 'build-tools/35.0.0'
app = root / 'app/build/outputs/apk/debug/app-debug.apk'
test = root / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
def output(*cmd): return subprocess.check_output([str(x) for x in cmd], text=True)
for apk in [app, test]:
    assert apk.is_file(), f'Missing APK: {apk}'
    subprocess.run([str(bt / 'apksigner'), 'verify', str(apk)], check=True)
manifest = output(bt / 'aapt2', 'dump', 'xmltree', test, '--file', 'AndroidManifest.xml')
assert 'ru.fitprogress.ai.FitTestRunner' in manifest, 'Unsafe or stale runner: refusing tests'
pattern = r'Signer #1 certificate SHA-256 digest: (\w+)'
assert re.search(pattern, output(bt / 'apksigner', 'verify', '--print-certs', app)).group(1) == re.search(pattern, output(bt / 'apksigner', 'verify', '--print-certs', test)).group(1), 'Signing identities differ'
cmds = [['adb', '-s', a.serial, 'install', '-r', str(app)], ['adb', '-s', a.serial, 'install', '-r', '-t', str(test)]]
print('Tests use isolated Room/DataStore. Instrumentation stops the app: finish/export any active workout before running.')
if not a.run:
    print('Validation passed. Add --run to install and execute on the selected idle test phone. No pm clear/uninstall is used.')
    raise SystemExit(0)
for cmd in cmds: subprocess.run(cmd, check=True)
api = int(output('adb', '-s', a.serial, 'shell', 'getprop', 'ro.build.version.sdk').strip())
if api >= 33: subprocess.run(['adb', '-s', a.serial, 'shell', 'pm', 'grant', 'ru.fitprogress.ai', 'android.permission.POST_NOTIFICATIONS'], check=True)
result = output('adb', '-s', a.serial, 'shell', 'am', 'instrument', '-w', 'ru.fitprogress.ai.test/ru.fitprogress.ai.FitTestRunner')
print(result)
assert re.search(r'OK \(\d+ tests?\)', result) and 'FAILURES' not in result and 'INSTRUMENTATION_FAILED' not in result, 'Instrumentation did not report success'
