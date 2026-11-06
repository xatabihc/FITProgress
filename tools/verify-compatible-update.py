#!/usr/bin/env python3
"""Verify signer, retain a private snapshot, update in place, and compare all user rows.

No permission/settings changes, root, uninstall, database writes or synthetic history.
Only the new APK is installed. UI smoke checks browse the five main tabs without edits.
Private SQLite snapshots are confined to ignored qa-reports with owner-only permissions.
"""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'ru.fitprogress.ai'
TABLES = ('exercises', 'workouts', 'runs', 'personal_records')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
    bt = sdk / 'build-tools/35.0.0'
    adb = ['adb', '-s', args.serial]
    private = ROOT / 'qa-reports' / ('compatible-update-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
    private.mkdir(parents=True, exist_ok=False); private.chmod(0o700)
    def run(parts, content=None, required=True, timeout=90):
        result = subprocess.run(list(map(str, parts)), input=content, capture_output=True, timeout=timeout)
        if required and result.returncode:
            raise RuntimeError('Verification command failed; private data omitted from diagnostics')
        return result
    def text(parts):
        return run(parts).stdout.decode('utf-8', errors='replace')
    def signer(apk):
        result = text([bt / 'apksigner', 'verify', '--print-certs', apk])
        fingerprints = re.findall(r'Signer #\d+ certificate SHA-256 digest: (\w+)', result)
        assert fingerprints, 'No signing certificate'
        return fingerprints
    local_meta = text([bt / 'aapt2', 'dump', 'badging', args.apk])
    assert f"package: name='{PACKAGE}'" in local_meta, 'Wrong applicationId'
    installed_path = text(adb + ['shell', 'pm', 'path', PACKAGE]).strip().splitlines()
    assert len(installed_path) == 1, 'Split APK updates require separate review'
    old_apk = private / 'before.apk'
    run(adb + ['pull', installed_path[0].removeprefix('package:'), old_apk])
    old_meta = text([bt / 'aapt2', 'dump', 'badging', old_apk])
    version = lambda data: int(re.search(r"versionCode='(\d+)'", data).group(1))
    assert version(local_meta) >= version(old_meta), 'Downgrade refused'
    assert signer(args.apk) == signer(old_apk), 'Signer mismatch: refusing update, no uninstall'
    run([bt / 'zipalign', '-P', '16', '-c', '4', args.apk])
    def snapshot(name):
        folder = private / name; folder.mkdir(mode=0o700)
        files = ['fitprogress.db', 'fitprogress.db-wal', 'fitprogress.db-journal']
        def source_hashes():
            values = []
            for filename in files:
                r = run(adb + ['shell', 'run-as', PACKAGE, 'sha256sum', 'databases/' + filename], required=False)
                values.append((r.returncode, r.stdout))
            return values
        before = source_hashes()
        for filename, (exists, _) in zip(files, before):
            if exists:
                continue
            data = run(adb + ['exec-out', 'run-as', PACKAGE, 'cat', 'databases/' + filename]).stdout
            path = folder / filename; path.write_bytes(data); path.chmod(0o600)
        assert before == source_hashes(), 'Database changed during read; repeat verification when idle'
        with sqlite3.connect(folder / 'fitprogress.db') as db:
            assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'SQLite integrity check failed'
            assert db.execute('PRAGMA user_version').fetchone()[0] == 2, 'Unexpected schema'
            records = {}
            active = False
            for table in TABLES:
                rows = db.execute(f'SELECT * FROM {table} ORDER BY id').fetchall()
                records[table] = {row[0]: hashlib.sha256(json.dumps(row, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest() for row in rows}
                if table in ('workouts', 'runs'):
                    active |= any(json.loads(payload).get('status') == 'active' for payload in (row[-1] for row in rows))
            return records, active
    before, active = snapshot('before')
    assert not active, 'An active session exists; update refused'
    result = text(adb + ['install', '-r', args.apk])
    assert 'Success' in result, 'Update failed'
    immediately_after, _ = snapshot('after-install')
    assert immediately_after == before, 'User rows changed during installation'
    launch = text(adb + ['shell', 'am', 'start', '-W', '-n', PACKAGE + '/ru.fitprogress.ai.MainActivity'])
    assert 'Status: ok' in launch, 'Activity launch failed'
    def window():
        remote = '/data/local/tmp/fitprogress-update-window.xml'
        run(adb + ['shell', 'uiautomator', 'dump', remote])
        xml = run(adb + ['exec-out', 'cat', remote]).stdout
        run(adb + ['shell', 'rm', remote])
        nodes = list(ET.fromstring(xml).iter('node'))
        assert any(n.get('package') == PACKAGE for n in nodes), 'App window not visible'
        assert not any(n.get('text') == 'Ошибка чтения данных' for n in nodes), 'Data read error displayed'
        return nodes
    passed = []
    nodes = window()
    for label in ('Упражнения', 'План', 'Бег', 'Прогресс', 'Главная'):
        target = next(n for n in nodes if n.get('package') == PACKAGE and n.get('content-desc') == label)
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', target.get('bounds')))
        run(adb + ['shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2)])
        nodes = window(); passed.append(label)
    after_ui, _ = snapshot('after-ui')
    assert after_ui == before, 'Existing user rows changed during read-only screen checks'
    summary = {'package': PACKAGE, 'old_version_code': version(old_meta), 'new_version_code': version(local_meta),
               'apk_sha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(), 'signer_sha256': signer(args.apk),
               'install_r_success': True, 'all_existing_user_rows_preserved': True,
               'sqlite_integrity_verified': True, 'main_tabs_browsed_without_data_error': passed,
               'permissions_and_global_settings_changed': False, 'real_gps_route_tested': False,
               'private_snapshots_in_git': False}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(summary, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
