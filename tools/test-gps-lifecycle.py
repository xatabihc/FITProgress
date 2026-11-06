#!/usr/bin/env python3
"""Exercise the real GPS foreground service in isolated QA storage; restore location state.

No mock route, no user history edits, no lock-screen/Doze or outdoor-accuracy claim.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    adb = ['adb', '-s', args.serial]
    def run(parts, timeout=60):
        r = subprocess.run(adb + parts, capture_output=True, text=True, timeout=timeout)
        assert r.returncode == 0, 'ADB lifecycle operation failed'
        return r.stdout + r.stderr
    original = run(['shell', 'cmd', 'location', 'is-location-enabled', '--user', '0']).strip()
    assert original in ('true', 'false'), 'Unknown original location state'
    result = None
    try:
        if original == 'false':
            run(['shell', 'cmd', 'location', 'set-location-enabled', 'true', '--user', '0'])
        result = run(['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                      'ru.fitprogress.ai.GpsServiceStartTest', '-e', 'gpsService', 'true',
                      'ru.fitprogress.ai.qa.test/ru.fitprogress.ai.FitTestRunner'], 120)
    finally:
        run(['shell', 'cmd', 'location', 'set-location-enabled', original, '--user', '0'])
        restored = run(['shell', 'cmd', 'location', 'is-location-enabled', '--user', '0']).strip()
        assert restored == original, 'Original location state was not restored'
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'instrumentation.txt').write_text(result or '')
    passed = bool(result and re.search(r'OK \(1 test\)', result) and
                  re.search(r'^INSTRUMENTATION_STATUS_CODE: 0$', result, re.M) and
                  not re.search(r'^INSTRUMENTATION_STATUS_CODE: -[1234]$', result, re.M))
    summary = {'gps_foreground_service_lifecycle_passed': passed,
               'original_location_enabled': original == 'true', 'original_location_state_restored': True,
               'production_history_modified': False, 'mock_provider_used': False,
               'outdoor_route_accuracy_tested': False, 'lock_screen_doze_tested': False}
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))
    assert passed, 'GPS lifecycle test did not pass; see bounded test output'

if __name__ == '__main__':
    main()
