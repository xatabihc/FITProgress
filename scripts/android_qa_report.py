#!/usr/bin/env python3
"""Render only curated, non-personal QA evidence."""
import collections
import html
import json
from pathlib import Path
import sys


def generate(directory):
    directory = Path(directory)
    data = json.loads((directory / 'results.json').read_text())
    counts = collections.Counter(r['status'] for r in data['checks'])
    lines = ['# FitProgressAI — автоматическая проверка', '', data['started'], '',
             ' / '.join(f'{s}: {counts[s]}' for s in ('PASS', 'FAIL', 'BLOCKED', 'NOT TESTED')), '',
             'PASS относится только к указанному утверждению; клик не подтверждает функцию.', '']
    for prefix, label in [('unit.', 'JUnit'), ('device.ru.', 'Аппаратные методы')]:
        subset = collections.Counter(r['status'] for r in data['checks'] if r['id'].startswith(prefix))
        lines += [label + ': ' + ' / '.join(f'{k}: {subset[k]}' for k in ('PASS', 'FAIL', 'NOT TESTED')), '']
    for severity in ('критическая', 'высокая', 'средняя', 'низкая', 'требует анализа'):
        failures = [r['id'] for r in data['checks'] if r['status'] == 'FAIL' and r.get('severity', 'требует анализа') == severity]
        if failures: lines += [f'Ошибки, {severity}: ' + ', '.join(failures), '']
    for row in sorted(data['checks'], key=lambda r: ({'FAIL': 0, 'BLOCKED': 1}.get(r['status'], 2), r['id'])):
        lines += [f"## {row['id']} — {row['status']}", '', row['detail'], '']
        if row['status'] == 'FAIL':
            lines += [f"Критичность: {row.get('severity', 'требует анализа')}",
                      f"Воспроизведение: {row.get('repro', 'Повторить scripts/android_qa.sh')}",
                      f"Возможная причина: {row.get('cause', 'См. диагностический артефакт; причина не установлена')}", '']
    text = '\n'.join(lines)
    (directory / 'report.md').write_text(text)
    artifacts = ''.join('<li><a href="' + name + '">' + name + '</a></li>' for name in ('catalog.png', 'hierarchy.xml', 'performance.json', 'gradle.txt', 'instrumentation.txt', 'failure-frames.txt', 'persistence-seed.txt', 'persistence-verify.txt') if (directory / name).exists())
    pictures = '<img alt="Изолированный каталог" src="catalog.png" style="max-width:400px;width:100%">' if (directory / 'catalog.png').exists() else ''
    (directory / 'report.html').write_text('<!doctype html><html lang="ru"><meta charset="utf-8"><title>FitProgressAI QA</title><style>body{max-width:1000px;margin:2rem auto;font:16px system-ui}pre{white-space:pre-wrap}</style><pre>' + html.escape(text) + '</pre><ul>' + artifacts + '</ul>' + pictures + '</html>')
    return counts


if __name__ == '__main__':
    print(dict(generate(sys.argv[1])))
