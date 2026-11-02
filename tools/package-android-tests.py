#!/usr/bin/env python3
"""Supplemental packaging of freshly compiled tests; requires prior Gradle test resources/dependency DEX."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
os.chdir(ROOT)
BUILD = ROOT / 'app/build'
INTER = BUILD / 'intermediates'
OUT = BUILD / 'offline-verification'
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
BT = SDK / 'build-tools/35.0.0'
ANDROID = SDK / 'platforms/android-35/android.jar'
def run(*args): subprocess.run([str(a) for a in args], check=True)
classes = OUT / 'android-test-classes'
assert (classes / 'ru/fitprogress/ai/FitTestRunner.class').is_file(), 'Compile with offline-android-build.py --android-tests first'
ns = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ns)
tree = ET.parse(INTER / 'packaged_manifests/debugAndroidTest/processDebugAndroidTestManifest/AndroidManifest.xml')
tree.getroot().find('instrumentation').set('{' + ns + '}name', 'ru.fitprogress.ai.FitTestRunner')
tree.write(OUT / 'TestManifest.xml', encoding='utf-8', xml_declaration=True)
resources = INTER / 'linked_resources_binary_format/debugAndroidTest/processDebugAndroidTestResources/linked-resources-binary-format.ap_'
run(BT / 'aapt2', 'link', '--manifest', OUT / 'TestManifest.xml', '-I', ANDROID, '-I', resources, '-o', OUT / 'test-manifest.ap_')
jar = OUT / 'android-test-classes.jar'
with zipfile.ZipFile(jar, 'w') as z:
    for p in sorted(classes.rglob('*.class')): z.write(p, p.relative_to(classes))
dex = OUT / 'test-dex'
shutil.rmtree(dex, ignore_errors=True)
dex.mkdir()
cp = (OUT / 'classpath.txt').read_text().split(os.pathsep)
cmd = [BT / 'd8', '--debug', '--min-api', '29', '--lib', ANDROID, '--output', dex, '--classpath', OUT / 'app-classes.jar']
for p in cp[1:]: cmd += ['--classpath', p]
cmd += [jar, INTER / 'compile_and_runtime_not_namespaced_r_class_jar/debugAndroidTest/processDebugAndroidTestResources/R.jar']
cmd += sorted((INTER / 'dex/debugAndroidTest/mergeExtDexDebugAndroidTest').glob('*.dex'))
run(*cmd)
with zipfile.ZipFile(OUT / 'test-unsigned.apk', 'w') as z:
    with zipfile.ZipFile(resources) as src:
        for info in src.infolist():
            if info.filename != 'AndroidManifest.xml': z.writestr(info, src.read(info.filename))
    with zipfile.ZipFile(OUT / 'test-manifest.ap_') as src: z.writestr('AndroidManifest.xml', src.read('AndroidManifest.xml'))
    for p in sorted(dex.glob('*.dex')): z.write(p, p.name, compress_type=zipfile.ZIP_DEFLATED)
run(BT / 'zipalign', '-P', '16', '-f', '4', OUT / 'test-unsigned.apk', OUT / 'test-aligned.apk')
target = BUILD / 'outputs/apk/androidTest/debug/app-debug-androidTest.apk'
target.parent.mkdir(parents=True, exist_ok=True)
run(BT / 'apksigner', 'sign', '--ks', Path.home() / '.android/debug.keystore', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', target, OUT / 'test-aligned.apk')
run(BT / 'apksigner', 'verify', target)
run(BT / 'zipalign', '-P', '16', '-c', '4', target)
print(f'Test APK: {target}\nSHA256: {hashlib.sha256(target.read_bytes()).hexdigest()}\nCompiled and packaged only; NOT executed.')
