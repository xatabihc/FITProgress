#!/usr/bin/env python3
"""Private snapshot of the idle debuggable old install; optional copy to an EMPTY QA store.

Uses Android run-as, never root or access to another application's data. Original files
are only read. No payloads or user counts are printed. Snapshots must remain outside Git.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SOURCE = 'ru.fitprogress.ai'
TARGET = 'ru.fitprogress.ai.qa'

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True)
    p.add_argument('--copy-to-empty-qa', action='store_true')
    args = p.parse_args()
    adb = ['adb', '-s', args.serial]
    def call(parts, data=None, required=True):
        r = subprocess.run(adb + list(parts), input=data, capture_output=True, timeout=90)
        if required and r.returncode:
            raise RuntimeError('ADB operation failed; no original data were changed')
        return r
    def idle(package):
        r = call(['shell', 'pidof', package], required=False)
        assert not r.stdout.strip() and r.returncode == 1, 'App is running; snapshot/copy refused'
    def hashes():
        return call(['shell', 'run-as', SOURCE, 'sha256sum', 'databases/fitprogress.db', 'databases/fitprogress.db-wal']).stdout
    idle(SOURCE)
    folder = ROOT / 'qa-reports' / 'private-history-snapshot'
    folder.mkdir(parents=True, exist_ok=True); folder.chmod(0o700)
    schema = json.loads((ROOT / 'app/schemas/ru.fitprogress.ai.FitDatabase/2.json').read_text())['database']
    with tempfile.TemporaryDirectory(prefix='snapshot-', dir=folder) as temp:
        temp = Path(temp)
        before = hashes()
        for name in ['fitprogress.db', 'fitprogress.db-wal']:
            data = call(['exec-out', 'run-as', SOURCE, 'cat', 'databases/' + name]).stdout
            target = temp / name; target.write_bytes(data); target.chmod(0o600)
        idle(SOURCE)
        assert before == hashes(), 'Source changed during snapshot; copy refused'
        original = sqlite3.connect(temp / 'fitprogress.db')
        assert original.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
        assert original.execute('PRAGMA user_version').fetchone()[0] == 2
        assert original.execute('SELECT identity_hash FROM room_master_table WHERE id=42').fetchone()[0] == schema['identityHash']
        has_open_session = any(json.loads(row[0]).get('status') in ('active', 'paused')
                               for table in ['workouts', 'runs'] for row in original.execute(f'SELECT payload FROM {table}'))
        snapshot = folder / ('history-' + os.urandom(8).hex() + '.sqlite')
        with sqlite3.connect(snapshot) as output:
            original.backup(output)
        original.close(); snapshot.chmod(0o600)
    copied = False
    exists = call(['shell', 'run-as', TARGET, 'test', '-e', 'databases/fitprogress.db'], required=False) if args.copy_to_empty_qa else None
    reason = 'unfinished_source_sessions' if has_open_session else 'target_store_exists' if exists is not None and exists.returncode == 0 else None
    if args.copy_to_empty_qa and reason is None:
        assert not has_open_session, 'Unfinished source sessions exist; private snapshot saved, automatic copy refused'
        idle(TARGET)
        assert exists.returncode == 1, 'QA production database already exists; refusing to overwrite'
        call(['shell', 'run-as', TARGET, 'mkdir', '-p', 'databases'])
        content = snapshot.read_bytes()
        call(['exec-in', 'run-as', TARGET, 'dd', 'of=databases/fitprogress-transfer.db', 'bs=8192'], content)
        call(['shell', 'run-as', TARGET, 'chmod', '600', 'databases/fitprogress-transfer.db'])
        digest = call(['shell', 'run-as', TARGET, 'sha256sum', 'databases/fitprogress-transfer.db']).stdout.decode().split()[0]
        assert digest == hashlib.sha256(content).hexdigest(), 'Transfer verification failed'
        idle(TARGET)
        # A hard link is atomic and fails if the destination appears; no existing history is overwritten.
        call(['shell', 'run-as', TARGET, 'ln', 'databases/fitprogress-transfer.db', 'databases/fitprogress.db'])
        call(['shell', 'run-as', TARGET, 'rm', 'databases/fitprogress-transfer.db'])
        copied = True
    print(json.dumps({'private_snapshot': str(snapshot), 'integrity_verified': True,
                      'original_data_modified': False, 'copied_to_empty_qa': copied,
                      'profile_and_external_image_permissions_copied': False, 'copy_skipped_reason': reason}, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
