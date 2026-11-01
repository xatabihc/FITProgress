#!/usr/bin/env python3
"""Compile/package from existing Gradle intermediates when the Gradle daemon is blocked.

Not a clean build. Requires a previously resolved SDK 35 / Kotlin 2.1.20 project,
unchanged Room v1 schema and existing merged resources/dependency DEX. No network.
The normal supported clean build remains ./gradlew assembleDebug.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
os.chdir(ROOT)
CACHE = Path.home() / '.gradle/caches/modules-2/files-2.1'
BUILD = ROOT / 'app/build'
INTER = BUILD / 'intermediates'
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
BT = SDK / 'build-tools/35.0.0'
ANDROID = SDK / 'platforms/android-35/android.jar'


def artifact(group, name, version=None):
    jars = sorted(p for p in (CACHE / group / name).rglob('*.jar')
                  if not p.name.endswith(('-sources.jar', '-javadoc.jar')) and (not version or version in p.parts))
    if not jars:
        raise RuntimeError(f'Missing cached artifact: {group}:{name}:{version}')
    return jars[-1]


def run(cmd):
    subprocess.run([str(x) for x in cmd], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package', action='store_true', help='also create and sign an APK')
    parser.add_argument('--android-tests', action='store_true', help='compile Android tests (does not execute them)')
    args = parser.parse_args()
    output = BUILD / 'offline-verification'
    output.mkdir(parents=True, exist_ok=True)
    classes = output / 'classes'
    shutil.rmtree(classes, ignore_errors=True)
    rjar = INTER / 'compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar'
    assert ANDROID.is_file() and rjar.is_file(), 'A prior Gradle resource build and SDK 35 are required'
    cp = [ANDROID, rjar]
    inputs = []
    for group in sorted(CACHE.iterdir()):
        if not group.name.startswith(('androidx.', 'com.google.android', 'com.google.code.gson', 'com.google.guava', 'org.maplibre', 'org.jetbrains.kotlin', 'org.jetbrains.annotations')):
            continue
        for name in sorted(group.iterdir()):
            if group.name == 'org.jetbrains.kotlin' and not name.name.startswith(('kotlin-stdlib', 'kotlin-reflect')):
                continue
            versions = sorted(name.iterdir(), key=lambda p: (tuple(map(int, re.findall(r"\d+", p.name))), p.name))
            exact = ('2.1.20' if group.name == 'org.jetbrains.kotlin' and name.name == 'kotlin-stdlib' else
                     '2.1.0' if group.name == 'org.jetbrains.kotlin' and name.name == 'kotlin-reflect' else
                     '1.9.0' if group.name == 'org.jetbrains.kotlinx' and name.name == 'kotlinx-coroutines-core-jvm' else None)
            if exact:
                versions = [v for v in versions if v.name == exact]
            if not versions:
                continue
            version = versions[-1]
            for path in sorted(version.rglob('*')):
                if path.suffix == '.jar' and not path.name.endswith(('-sources.jar', '-javadoc.jar')):
                    cp.append(path)
                    inputs.append(path)
                if path.suffix == '.aar':
                    target = output / 'aar' / group.name / name.name / version.name
                    target.mkdir(parents=True, exist_ok=True)
                    with zipfile.ZipFile(path) as z:
                        for entry in z.namelist():
                            if entry == 'classes.jar' or entry.startswith('libs/') and entry.endswith('.jar'):
                                dest = target / Path(entry).name
                                dest.write_bytes(z.read(entry))
                                cp.append(dest)
                    inputs.append(path)
    compiler = [artifact('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.1.20'),
                artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '2.1.20'),
                artifact('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.1.20'),
                artifact('org.jetbrains.kotlin', 'kotlin-reflect', '2.1.0'),
                artifact('org.jetbrains.intellij.deps', 'trove4j'),
                artifact('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'),
                artifact('org.jetbrains', 'annotations')]
    compiler_cmd = ['java', '-Xmx2g', '-cp', os.pathsep.join(map(str, compiler)),
                    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
                    '-Xplugin=' + str(artifact('org.jetbrains.kotlin', 'kotlin-compose-compiler-plugin-embeddable', '2.1.20'))]
    sources = sorted((ROOT / 'app/src/main/java').rglob('*.kt'))
    before = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
    run(compiler_cmd + ['-cp', os.pathsep.join(map(str, cp)), '-d', classes] + sources)
    print('PASS: Android Kotlin + Compose compilation', flush=True)
    generated = sorted((BUILD / 'generated/source/kapt/debug').rglob('*.java'))
    assert generated, 'Previously generated Room v1 Java sources are required'
    schema = ROOT / 'app/schemas/ru.fitprogress.ai.FitDatabase/1.json'
    assert json.loads(schema.read_text())['database']['identityHash'] == '63d071eabbf9c1eb45970e5d513cfeca', 'Room schema changed; use Gradle/KAPT'
    run(['javac', '-source', '17', '-target', '17', '-cp', os.pathsep.join(map(str, cp + [classes])), '-d', classes] + generated)
    print('PASS: existing Room v1 generated Java compilation', flush=True)
    if args.android_tests:
        testcp = cp + [classes, artifact('junit', 'junit', '4.13.2')]
        run(compiler_cmd + ['-cp', os.pathsep.join(map(str, testcp)), '-d', output / 'android-test-classes'] + sorted((ROOT / 'app/src/androidTest').rglob('*.kt')))
        print('PASS: Android test compilation (NOT execution)', flush=True)
    assert before == {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}, 'Sources changed during compilation; rerun verification'
    fingerprint = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
    fingerprint['app/src/main/AndroidManifest.xml'] = hashlib.sha256((ROOT / 'app/src/main/AndroidManifest.xml').read_bytes()).hexdigest()
    (output / 'source-fingerprints.json').write_text(json.dumps(fingerprint, indent=2))
    (output / 'classpath.txt').write_text(os.pathsep.join(map(str, cp)))
    if not args.package:
        return
    baseline = BUILD / 'outputs/apk/debug/app-debug.apk'
    assert baseline.is_file(), 'A prior APK is required for unchanged native libraries and assets'
    with zipfile.ZipFile(output / 'app-classes.jar', 'w') as z:
        for path in classes.rglob('*'):
            if path.is_file():
                z.write(path, path.relative_to(classes))
    dex = output / 'dex'
    shutil.rmtree(dex, ignore_errors=True)
    dex.mkdir()
    cmd = [BT / 'd8', '--debug', '--min-api', '29', '--lib', ANDROID, '--output', dex]
    for path in cp[1:]:
        cmd += ['--classpath', path]
    cmd += [output / 'app-classes.jar', rjar] + sorted((INTER / 'dex/debug/mergeExtDexDebug').glob('*.dex'))
    run(cmd)
    ns = 'http://schemas.android.com/apk/res/android'
    ET.register_namespace('android', ns)
    tree = ET.parse(INTER / 'merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml')
    manifest = tree.getroot()
    manifest.set('{' + ns + '}versionCode', '2')
    manifest.set('{' + ns + '}versionName', '2.0-dev')
    # Preserve dependency providers/receivers from the Gradle-merged manifest, replace app declarations from source.
    source = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
    existing = {p.get('{' + ns + '}name') for p in manifest.findall('uses-permission')}
    for permission in source.findall('uses-permission'):
        if permission.get('{' + ns + '}name') not in existing:
            manifest.append(permission)
    application = manifest.find('application')
    for service in source.find('application').findall('service'):
        name = service.get('{' + ns + '}name')
        if name.startswith('.'):
            name = 'ru.fitprogress.ai' + name
            service.set('{' + ns + '}name', name)
        for old in list(application.findall('service')):
            if old.get('{' + ns + '}name') == name:
                application.remove(old)
        application.append(service)
    tree.write(output / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
    flats = sorted((Path.home() / '.gradle/caches/8.11.1/transforms').rglob('*.flat'))
    flats += sorted((INTER / 'merged_res/debug/mergeDebugResources').glob('*.flat'))
    cmd = [BT / 'aapt2', 'link', '-o', output / 'resources.ap_', '--manifest', output / 'AndroidManifest.xml', '-I', ANDROID,
           '--auto-add-overlay', '--stable-ids', INTER / 'stable_resource_ids_file/debug/processDebugResources/stableIds.txt']
    for path in flats:
        cmd += ['-R', path]
    run(cmd)
    with zipfile.ZipFile(output / 'unsigned.apk', 'w') as z:
        with zipfile.ZipFile(output / 'resources.ap_') as resources:
            for info in resources.infolist():
                z.writestr(info, resources.read(info.filename))
        names = set(z.namelist())
        with zipfile.ZipFile(baseline) as old:
            for info in old.infolist():
                name = info.filename
                if name in names or name.startswith('res/') or (name.startswith('classes') and name.endswith('.dex')):
                    continue
                if name.startswith('META-INF/') and name.upper().endswith(('.SF', '.RSA', '.DSA', '.EC', 'MANIFEST.MF')):
                    continue
                z.writestr(info, old.read(name))
        for path in sorted(dex.glob('*.dex')):
            z.write(path, path.name, compress_type=zipfile.ZIP_DEFLATED)
    run([BT / 'zipalign', '-P', '16', '-f', '4', output / 'unsigned.apk', output / 'aligned.apk'])
    key = Path.home() / '.android/debug.keystore'
    assert key.is_file(), 'Standard local debug keystore is required; no release key is used'
    run([BT / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', output / 'app-debug.apk', output / 'aligned.apk'])
    run([BT / 'apksigner', 'verify', output / 'app-debug.apk'])
    run([BT / 'zipalign', '-P', '16', '-c', '4', output / 'app-debug.apk'])
    shutil.copyfile(output / 'app-debug.apk', baseline)
    metadata = baseline.with_name('output-metadata.json')
    if metadata.is_file():
        data = json.loads(metadata.read_text())
        for element in data.get('elements', []):
            if element.get('outputFile') == baseline.name:
                element['versionCode'] = 2
                element['versionName'] = '2.0-dev'
        metadata.write_text(json.dumps(data, indent=2))
    sha = hashlib.sha256(baseline.read_bytes()).hexdigest()
    print(f'APK: {baseline}\nBytes: {baseline.stat().st_size}\nSHA256: {sha}\nGradle build and device execution were NOT verified.')


if __name__ == '__main__':
    main()
