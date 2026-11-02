#!/usr/bin/env python3
"""Bounded QA: read-only device audit by default, no settings changes or data erasure."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import uuid
import xml.etree.ElementTree as ET
from android_qa_report import generate

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'ru.fitprogress.ai'
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial')
p.add_argument('--skip-build', action='store_true')
p.add_argument('--gps-service', action='store_true', help='Allow real GPS service lifecycle in isolated storage with current pre-granted permissions; no route accuracy claim')
p.add_argument('--test-class', help='Optional instrumentation class or class#method filter')
p.add_argument('--isolated-idle-confirmed', action='store_true', help='Owner confirms no active real session and permits process interruption; existing verified test APK only')
p.add_argument('--approve-install', action='store_true', help='Explicit owner approval to install current app/test APK, after certificate and version checks')
a = p.parse_args()
if a.test_class and not re.fullmatch(r'[A-Za-z0-9_.$]+(?:#[A-Za-z0-9_$]+)?(?:,[A-Za-z0-9_.$]+(?:#[A-Za-z0-9_$]+)?)*', a.test_class):
    p.error('Invalid instrumentation class filter')
build_ok = a.skip_build
os.chdir(ROOT)
started = dt.datetime.now().astimezone()
out = ROOT / 'qa-reports' / started.strftime('%Y%m%d-%H%M%S-%f')
out.mkdir(parents=True, mode=0o700)
os.chmod(out, 0o700)
checks = []
instrumented = False

def add(name, status, detail, **kw):
    checks.append(dict(id=name, status=status, detail=detail, **kw))
    if not name.startswith(('unit.', 'device.ru.')) or status == 'FAIL':
        print(f'{status}: {name}', flush=True)

def run(cmd, timeout=60):
    try:
        r = subprocess.run([str(c) for c in cmd], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
        return r.returncode, r.stdout.decode('utf-8', errors='replace')
    except (OSError, subprocess.TimeoutExpired) as e:
        return 124, type(e).__name__

def save():
    (out / 'results.json').write_text(json.dumps(dict(started=started.isoformat(), checks=checks), ensure_ascii=False, indent=2))
    counts = generate(out)
    print(f'Report: {out / "report.html"}; {dict(counts)}', flush=True)

try:
    if not a.skip_build:
        cmd = ['./gradlew', '--no-parallel', '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8', '-Pkotlin.compiler.execution.strategy=in-process', 'testDebugUnitTest', 'lintDebug', 'assembleDebug']
        code, text = run(cmd, 1800)
        build_ok = code == 0
        # Build logs contain source diagnostics only; remove absolute home paths.
        (out / 'gradle.txt').write_text(text.replace(str(Path.home()), '$HOME'))
        add('A.build-unit-lint', 'PASS' if code == 0 else 'BLOCKED' if code == 124 or 'Could not resolve' in text or 'Permission denied' in text or 'Read-only file system' in text else 'FAIL', 'Gradle: testDebugUnitTest lintDebug assembleDebug. См. gradle.txt; код ' + str(code))
        for file in ROOT.glob('app/build/test-results/testDebugUnitTest/TEST-*.xml'):
            # Only test names/status: no stdout or exception messages with possible data.
            tree = ET.parse(file)
            for t in tree.iter('testcase'):
                add('unit.' + t.get('classname', '') + '.' + t.get('name', ''), 'FAIL' if t.find('failure') is not None or t.find('error') is not None else 'NOT TESTED' if t.find('skipped') is not None else 'PASS', 'JUnit из текущего Gradle запуска.' if code == 0 else 'JUnit на диске; актуальность при неуспешной сборке не гарантирована.') if code == 0 else None
    else:
        add('A.build-unit-lint', 'NOT TESTED', 'Сборка пропущена явным параметром.')
    ru = ET.parse('app/src/main/res/values/strings.xml').getroot()
    en = ET.parse('app/src/main/res/values-en/strings.xml').getroot()
    keys = lambda root: {x.get('name') for x in root if x.tag == 'string' and x.get('translatable') != 'false'}
    missing = sorted(keys(ru) ^ keys(en))
    add('C.resource-parity', 'FAIL' if missing else 'PASS', f'ru(default)/en: {len(keys(ru))}/{len(keys(en))} ключей; расхождения: {missing}')
    sources = list(Path('app/src/main/java').rglob('*.kt'))
    literals = [(str(f), len(re.findall(r'Text\(\s*"[^"\n]*[А-Яа-яЁёA-Za-z][^"\n]*"', f.read_text()))) for f in sources]
    literals = [(f, n) for f, n in literals if n]
    (out / 'localization.json').write_text(json.dumps(literals, ensure_ascii=False, indent=2))
    add('C.hardcoded-ui', 'FAIL' if literals else 'PASS', f'Статический аудит: {sum(n for _, n in literals)} Text с буквенными литералами. Это препятствует полной локализации en; видимый русский интерфейс отдельно не подтверждён.', severity='низкая', repro='Поиск Text("...") в Kotlin; см. localization.json', cause='Часть интерфейса не использует stringResource.')
    library = Path('app/src/main/java/ru/fitprogress/ai/ExerciseLibrary.kt').read_text()
    asset = json.loads(Path('app/src/main/assets/exercises-open.json').read_text())['exercises']
    names_block = library.split('private val russianNames = mapOf(', 1)[1].split('\n    )', 1)[0]
    guides_block = library.split('private val russianGuides = mapOf(', 1)[1].split('\n    )', 1)[0]
    names = set(re.findall(r'"([^"]+)" to ', names_block))
    guides = set(re.findall(r'"([^"]+)" to ', guides_block))
    missing_names = sum(row['id'] not in names for row in asset)
    missing_guides = sum(row['id'] not in guides for row in asset)
    add('C.catalog-source-coverage', 'FAIL' if missing_names or missing_guides else 'PASS', f'Встроенный открытый каталог: {len(asset)} записей, {missing_names} названий и {missing_guides} инструкций используют английский fallback. Это аудит данных и отображающего кода, не визуальная проверка.', severity='средняя', repro='Открыть каталог и упражнения без russianNames/russianGuides; исходник ExerciseLibrary.kt.', cause='Ограниченные словари перевода; fallback на оригинал EN.')
    code, devices = run(['adb', 'devices', '-l'])
    available = re.findall(r'^(\S+)\s+device\s', devices, re.M)
    serial = a.serial or (available[0] if len(available) == 1 else None)
    if code or serial not in available:
        add('device', 'BLOCKED', 'Физическое устройство недоступно/не авторизовано либо требуется --serial. Эмулятор не использован.')
    else:
        adb = lambda *args, timeout=60: run(['adb', '-s', serial, *args], timeout)
        _, emulator = adb('shell', 'getprop', 'ro.kernel.qemu')
        if emulator.strip() == '1':
            raise RuntimeError('Требуется физический телефон, обнаружен эмулятор')
        add('device', 'PASS', 'Авторизованное физическое устройство доступно; идентификатор не сохранён.')
        info = {}
        for key, cmd in {'android': ['getprop', 'ro.build.version.release'], 'sdk': ['getprop', 'ro.build.version.sdk'], 'space': ['df', '-k', '/data'], 'gps_enabled': ['cmd', 'location', 'is-location-enabled']}.items():
            rc, value = adb('shell', *cmd)
            info[key] = value.strip() if rc == 0 else 'BLOCKED'
        _, battery = adb('shell', 'dumpsys', 'battery')
        for key in ('level', 'scale', 'temperature'):
            found = re.search(r'^\s*' + key + r': (\d+)', battery, re.M)
            info[key] = int(found[1]) if found else None
        (out / 'device.json').write_text(json.dumps(info, ensure_ascii=False, indent=2))
        add('E.gps-device-state', 'BLOCKED' if info['gps_enabled'] != 'true' else 'PASS', 'Текущее состояние GPS: ' + info['gps_enabled'] + '. Включение/выключение не выполнялось. Доступность mock provider не подтверждена.')
        disk_rows = info['space'].splitlines()
        try: free_kb = int(disk_rows[-1].split()[3])
        except (IndexError, ValueError): free_kb = 0
        healthy = info['level'] is not None and info['level'] >= 20 and free_kb >= 1048576 and info['temperature'] is not None and info['temperature'] < 450
        add('device.health', 'PASS' if healthy else 'BLOCKED', 'Метаданные ОС, батарея, свободное место: device.json. Для установки требуются заряд ≥20%, свободно ≥1 ГБ, температура <45°C; это не нагрузочный тест.')
        rc, package = adb('shell', 'dumpsys', 'package', PACKAGE)
        grants = re.findall(r'android.permission.(ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION|POST_NOTIFICATIONS): granted=(true|false)', package)
        (out / 'permissions.json').write_text(json.dumps(dict(grants), indent=2))
        add('E.permissions', 'PASS' if grants else 'BLOCKED', 'Сняты фактические разрешения: permissions.json. Выдача разрешений не выполнялась.')
        bt = Path.home() / 'Android/Sdk/build-tools/35.0.0'
        apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
        testapk = ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
        def cert(path):
            rc, value = run([bt / 'apksigner', 'verify', '--print-certs', path])
            digests = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)', value)
            return sorted(digests) if rc == 0 and digests else None
        def installed_copy(pkg, dest):
            rc, value = adb('shell', 'pm', 'path', pkg)
            paths = re.findall(r'^package:(.+)', value, re.M)
            if rc or len(paths) != 1:
                return False
            return adb('pull', paths[0].strip(), str(dest))[0] == 0
        compatible = identical = False
        with tempfile.TemporaryDirectory(prefix='fit-qa-') as tmp:
            installed = Path(tmp) / 'installed.apk'
            if apk.exists() and installed_copy(PACKAGE, installed):
                local_cert, installed_cert = cert(apk), cert(installed)
                compatible = bool(local_cert and local_cert == installed_cert)
                identical = hashlib.sha256(apk.read_bytes()).digest() == hashlib.sha256(installed.read_bytes()).digest()
                (out / 'apk-metadata.json').write_text(json.dumps(dict(package=PACKAGE, local_cert_sha256=local_cert, installed_cert_sha256=installed_cert, local_apk_sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), installed_apk_sha256=hashlib.sha256(installed.read_bytes()).hexdigest()), indent=2))
                add('A.signature', 'PASS' if compatible else 'BLOCKED', f'Сертификаты SHA256 совпадают: {compatible}; APK побайтово совпадают: {identical}. Неравные подписи запрещают установку; ротация ключей автоматически не разрешается.')
            else:
                add('A.signature', 'BLOCKED', 'Не удалось проверить APK и установленный сертификат.')
            if a.approve_install and a.isolated_idle_confirmed and compatible and build_ok and healthy:
                rc, badging = run([bt / 'aapt2', 'dump', 'badging', apk])
                local_v = re.search(r"versionCode='(\d+)'", badging)
                current_v = re.search(r'versionCode=(\d+)', package)
                if rc == 0 and local_v and current_v and int(local_v[1]) >= int(current_v[1]):
                    rc, _ = adb('install', '-r', str(apk), timeout=180)
                    add('A.update', 'PASS' if rc == 0 else 'FAIL', 'Подтверждённая установка -r без удаления данных. Сохранность истории этим не доказана.')
                    identical = rc == 0
                else:
                    add('A.update', 'BLOCKED', 'Версия APK неизвестна или ниже установленной.')
            else:
                add('A.update', 'BLOCKED', 'Нужно подтверждение владельца, свободный телефон и совместимая подпись.')
            # Never invoke connectedAndroidTest: it may install target APK without our checks.
            if a.isolated_idle_confirmed and identical and build_ok and healthy:
                if a.approve_install:
                    rc, _ = run(['./gradlew', '--no-parallel', '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8', '-Pkotlin.compiler.execution.strategy=in-process', 'assembleDebugAndroidTest'], 1800)
                else:
                    rc = 0
                if rc == 0 and testapk.exists() and cert(testapk) == cert(apk):
                    _, manifest = run([bt / 'aapt2', 'dump', 'xmltree', testapk, '--file', 'AndroidManifest.xml'])
                    safe = 'ru.fitprogress.ai.FitTestRunner' in manifest
                    test_copy = Path(tmp) / 'test.apk'
                    installed_test = installed_copy(PACKAGE + '.test', test_copy)
                    same = installed_test and test_copy.read_bytes() == testapk.read_bytes()
                    if safe and a.approve_install:
                        same = adb('install', '-r', '-t', str(testapk), timeout=180)[0] == 0
                    if safe and same:
                        args = ['shell', 'am', 'instrument', '-w', '-r']
                        if a.test_class: args += ['-e', 'class', a.test_class]
                        if a.gps_service: args += ['-e', 'gpsService', 'true']
                        samples = []
                        stop = threading.Event()
                        def sample():
                            while not stop.wait(5):
                                _, mem = adb('shell', 'dumpsys', 'meminfo', PACKAGE, timeout=10)
                                _, cpu = adb('shell', 'dumpsys', 'cpuinfo', timeout=10)
                                _, ids = adb('shell', 'pidof', PACKAGE, timeout=10)
                                crash = 0
                                cpu_sample = '\n'.join(re.sub(r'\d+/' + re.escape(PACKAGE), PACKAGE, line.strip()) for line in cpu.splitlines() if '/' + PACKAGE + ':' in line)
                                if ids.strip().isdigit():
                                    top_rc, top_text = adb('shell', 'top', '-b', '-n', '1', '-p', ids.strip(), timeout=10)
                                    if top_rc == 0:
                                        rows = [re.sub(r'^\s*\d+\s+', '', line).strip() for line in top_text.splitlines() if re.match(r'^\s*' + ids.strip() + r'\s', line) and PACKAGE in line]
                                        cpu_sample = '\n'.join(rows)
                                    _, logs = adb('logcat', '-d', '-t', '100', '--pid=' + ids.strip(), timeout=10)
                                    crash = len(re.findall(r'FATAL EXCEPTION|Fatal signal|ANR in ', logs))
                                samples.append(dict(memory='\n'.join(l.strip() for l in mem.splitlines() if re.match(r'\s*TOTAL', l)), cpu=cpu_sample, crash_markers=crash))
                        monitor = threading.Thread(target=sample, daemon=True)
                        monitor.start()
                        try:
                            rc, output = adb(*args, PACKAGE + '.test/ru.fitprogress.ai.FitTestRunner', timeout=600)
                        finally:
                            stop.set(); monitor.join(timeout=45)
                        if rc == 124:
                            adb('shell', 'am', 'force-stop', PACKAGE, timeout=15)
                            add('instrumentation.timeout', 'BLOCKED', 'Лимит 10 минут исчерпан. Процесс тестируемого приложения остановлен по ранее подтверждённому разрешению простоя; данные не удалялись.')
                        (out / 'performance.json').write_text(json.dumps(samples, ensure_ascii=False, indent=2))
                        add('G.sampled-performance', 'FAIL' if any(s['crash_markers'] for s in samples) else 'PASS' if samples else 'NOT TESTED', f'{len(samples)} ограниченных выборок CPU/RAM и crash-маркеров текущего PID. Пики между выборками и системные ANR не исключены.')
                        # Save only protocol statuses, not arbitrary exception messages or personal data.
                        protocol = '\n'.join(line for line in output.splitlines() if re.match(r'INSTRUMENTATION_STATUS: (class|test|numtests|current)=|INSTRUMENTATION_STATUS_CODE:|OK \(|FAILURES', line))
                        (out / 'instrumentation.txt').write_text(protocol)
                        frames = [line.strip() for line in output.splitlines() if re.match(r'\s*at (ru\.fitprogress\.ai|androidx\.compose\.ui\.test)\.[A-Za-z0-9_.$]+\([^)]*\)$', line)]
                        (out / 'failure-frames.txt').write_text('\n'.join(frames))
                        ok = rc == 0 and bool(re.search(r'OK \(\d+ tests?\)', output)) and 'FAILURES' not in output
                        instrumented = ok
                        current = {}
                        for line in output.splitlines():
                            pair = re.match(r'INSTRUMENTATION_STATUS: (class|test)=(.+)', line)
                            if pair: current[pair[1]] = pair[2]
                            status = re.match(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
                            if status and int(status[1]) != 1 and current.get('test'):
                                n = int(status[1])
                                add('device.' + current.get('class', '') + '.' + current['test'], 'PASS' if n == 0 else 'NOT TESTED' if n in (-3, -4) else 'FAIL', 'Изолированный instrumentation; код ' + str(n), repro='Повторить с фильтром am instrument -e class ' + current.get('class', '') + '#' + current['test'])
                                current = {}
                        if any(r['id'].endswith('.isolatedCatalogArtifacts') and r['status'] == 'PASS' for r in checks):
                            for filename in ('catalog.png', 'hierarchy.xml'):
                                result = subprocess.run(['adb', '-s', serial, 'exec-out', 'run-as', PACKAGE, 'cat', 'files/qa-artifacts/' + filename], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
                                if result.returncode == 0 and result.stdout:
                                    (out / filename).write_bytes(result.stdout)
                        add('B-D.instrumentation', 'PASS' if ok else 'BLOCKED' if rc == 124 else 'FAIL', 'Compose/репозиторий/ограниченный обход: instrumentation.txt. GPS hardware разрешён только отдельным --gps-service; пропуски не равны PASS, живой маршрут и точность не подтверждены.')
                        if a.approve_install and not a.test_class:
                            marker = 'qa-' + uuid.uuid4().hex
                            phases = []
                            for phase in ('seed', 'verify'):
                                if phase == 'verify':
                                    update_code, _ = adb('install', '-r', str(apk), timeout=180)
                                    if update_code != 0:
                                        phases.append(False)
                                        break
                                phase_code, phase_output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', PACKAGE + '.AndroidQaPersistenceTest', '-e', 'qaPhase', phase, '-e', 'qaMarker', marker, PACKAGE + '.test/ru.fitprogress.ai.FitTestRunner', timeout=120)
                                phase_ok = phase_code == 0 and 'OK (1 test)' in phase_output and 'FAILURES' not in phase_output and 'INSTRUMENTATION_STATUS_CODE: 0' in phase_output
                                phases.append(phase_ok)
                                (out / ('persistence-' + phase + '.txt')).write_text('\n'.join(line for line in phase_output.splitlines() if re.match(r'INSTRUMENTATION_STATUS: (class|test)=|INSTRUMENTATION_STATUS_CODE:|OK \(|FAILURES', line)))
                                if not phase_ok: break
                            add('F.update-history-persistence', 'PASS' if len(phases) == 2 and all(phases) else 'FAIL', 'Завершённая тестовая тренировка: 1 подход, объём 160; seed → install -r того же проверенного APK → verify в новом процессе. Только отдельная instrumentation-база. Production-история не читалась.', severity='высокая', repro='Повторить полный одобренный прогон; см. persistence-seed.txt и persistence-verify.txt', cause='При падении seed: ошибка фикстуры/сохранения; verify: обновление или восстановление хранилища; см. отдельный повтор для подтверждения.')

                    else:
                        add('B-D.instrumentation', 'BLOCKED', 'Test APK не установлен/не совпадает либо runner небезопасен.')
                else:
                    add('B-D.instrumentation', 'BLOCKED', 'Нет проверенного test APK или сборка недоступна.')
            else:
                add('B-D.instrumentation', 'BLOCKED', 'Требуется --isolated-idle-confirmed и точное совпадение установленной и локальной сборки. Процесс реальной тренировки не прерывался.')
        # Read-only process diagnostics: never save full logcat, history, UI text or GPS.
        _, pid_text = adb('shell', 'pidof', PACKAGE)
        pids = pid_text.split()
        if pids and all(x.isdigit() for x in pids):
            pid = pids[0]
            rc, logs = adb('logcat', '-d', '-t', '500', '--pid=' + pid)
            markers = [line for line in logs.splitlines() if re.search(r'FATAL EXCEPTION|ANR in |Fatal signal', line)]
            (out / 'logcat.txt').write_text('\n'.join(re.sub(r':.*$', ': [message withheld]', line) for line in markers))
            add('G.current-process-crashes', 'FAIL' if markers else 'PASS' if rc == 0 else 'BLOCKED', 'Только последние 500 строк текущего PID; отсутствие маркеров не доказывает стабильность всех сценариев.')
            rc, memory = adb('shell', 'dumpsys', 'meminfo', PACKAGE)
            (out / 'memory.txt').write_text('\n'.join(line for line in memory.splitlines() if re.match(r'\s*(TOTAL|Native Heap|Dalvik Heap|Java Heap)', line)))
            rc, cpu = adb('shell', 'dumpsys', 'cpuinfo')
            (out / 'cpu.txt').write_text('\n'.join(re.sub(r'\d+/' + re.escape(PACKAGE), PACKAGE, line) for line in cpu.splitlines() if '/' + PACKAGE + ':' in line))
        else:
            add('G.current-process-crashes', 'NOT TESTED', 'Процесс не запущен; для диагностики не запускался.')
    for name, reason in {
        'A.cold-warm-background': 'Фон/возврат изолированной Activity учитывается отдельным тестом; холодный/тёплый запуск production с реальной базой не выполнялся.',
        'B.all-screens-accessibility-insets': 'Полнота обхода, контраст, системные панели и все диалоги требуют отдельных assertions; клики недостаточны.',
        'B.screenshots-ui-hierarchy': 'Снимки production и UI XML могут содержать персональные данные; не сохранялись. Используйте изолированный QA тест.',
        'C.visible-russian-catalog': 'Требуется изолированный UI запуск и проверка названий, описаний, фильтров; глобальная локаль не менялась.',
        'E.gps-route': 'Реальная точность/маршрут не проверены. Mock provider владельцем не подтверждён; разрешения не изменялись.',
        'E.no-network-no-gps': 'Отключение сети/GPS требует разрешения; локальные unit-тесты не заменяют аппаратную проверку.',
        'F.update-history-persistence': 'Нет разрешённой установки с контрольной тестовой историей до/после; реальные данные не читались.',
        'G.extended-stress': 'Длительный стресс не выполнялся. Короткий ограниченный обход учитывается отдельным device.AndroidQaTest; Monkey не использовался.'
    }.items():
        if any(r['id'] == name for r in checks):
            continue
        add(name, 'PASS' if name == 'B.screenshots-ui-hierarchy' and (out / 'catalog.png').exists() and (out / 'hierarchy.xml').exists() else 'BLOCKED', 'Изолированный каталог: catalog.png и структурная hierarchy.xml без текста.' if name == 'B.screenshots-ui-hierarchy' and (out / 'catalog.png').exists() and (out / 'hierarchy.xml').exists() else reason)
except Exception as e:
    add('orchestrator', 'BLOCKED', type(e).__name__ + ': безопасное завершение; повторите диагностику окружения.')
finally:
    save()
raise SystemExit(1 if any(x['status'] == 'FAIL' for x in checks) else 0)
