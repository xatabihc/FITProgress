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
    reason = 'unfinished_source_sessions' if has_open_session else None
    archive = None
    if args.copy_to_empty_qa and reason is None:
        idle(TARGET)
        # Only the new QA package. Freeze its idle process so scheduled jobs cannot reopen SQLite.
        call(['shell', 'am', 'force-stop', TARGET])
        exists = call(['shell', 'run-as', TARGET, 'test', '-e', 'databases/fitprogress.db'], required=False)
        assert exists.returncode in (0, 1), 'Cannot inspect QA database'
        target_files = []
        if exists.returncode == 0:
            with tempfile.TemporaryDirectory(prefix='qa-empty-', dir=folder) as temp:
                target_dir = Path(temp)
                for name in ['fitprogress.db', 'fitprogress.db-wal', 'fitprogress.db-journal', 'fitprogress.db-shm']:
                    present = call(['shell', 'run-as', TARGET, 'test', '-e', 'databases/' + name], required=False)
                    assert present.returncode in (0, 1)
                    if present.returncode == 0:
                        target_files.append(name)
                        (target_dir / name).write_bytes(call(['exec-out', 'run-as', TARGET, 'cat', 'databases/' + name]).stdout)
                with sqlite3.connect(target_dir / 'fitprogress.db') as target_db:
                    assert target_db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
                    assert target_db.execute('SELECT identity_hash FROM room_master_table WHERE id=42').fetchone()[0] == schema['identityHash']
                    empty = all(target_db.execute('SELECT COUNT(*) FROM ' + table).fetchone()[0] == 0 for table in ['exercises', 'workouts', 'runs', 'personal_records'])
                    if not empty:
                        reason = 'target_store_has_user_rows'
                    else:
                        empty_backup = snapshot.with_name(snapshot.stem + '-qa-empty.sqlite')
                        with sqlite3.connect(empty_backup) as backup_db:
                            target_db.backup(backup_db)
                        empty_backup.chmod(0o600)
        if reason is None:
            call(['shell', 'run-as', TARGET, 'mkdir', '-p', 'databases'])
            content = snapshot.read_bytes()
            call(['exec-in', 'run-as', TARGET, 'dd', 'of=databases/fitprogress-transfer.db', 'bs=8192'], content)
            call(['shell', 'run-as', TARGET, 'chmod', '600', 'databases/fitprogress-transfer.db'])
            digest = call(['shell', 'run-as', TARGET, 'sha256sum', 'databases/fitprogress-transfer.db']).stdout.decode().split()[0]
            assert digest == hashlib.sha256(content).hexdigest(), 'Transfer verification failed'
            idle(TARGET)
            # Only a verified empty store is archived. Nonempty stores are never replaced.
            if target_files:
                archive = 'databases/empty-before-transfer-' + os.urandom(8).hex()
                call(['shell', 'run-as', TARGET, 'mkdir', archive])
                call(['shell', 'run-as', TARGET, 'chmod', '700', archive])
                for name in target_files:
                    call(['shell', 'run-as', TARGET, 'mv', 'databases/' + name, archive + '/' + name])
            # Android SELinux denies hard links here; a no-clobber rename retains the same safety guard.
            call(['shell', 'run-as', TARGET, 'mv', '-n', 'databases/fitprogress-transfer.db', 'databases/fitprogress.db'])
            final_digest = call(['shell', 'run-as', TARGET, 'sha256sum', 'databases/fitprogress.db']).stdout.decode().split()[0]
            assert final_digest == hashlib.sha256(content).hexdigest(), 'Existing QA store retained; transfer not applied'
            copied = True
    print(json.dumps({'private_snapshot': str(snapshot), 'integrity_verified': True,
                      'original_data_modified': False, 'copied_to_empty_qa': copied,
                      'profile_and_external_image_permissions_copied': False, 'copy_skipped_reason': reason, 'empty_qa_store_archived': archive is not None}, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
