#!/usr/bin/env python3
"""Validate the pinned, bundled exercise snapshot without network access."""
import json
import re
import unicodedata
from collections import defaultdict
from pathlib import Path

root = Path(__file__).resolve().parent.parent
asset = root / 'app/src/main/assets/exercises-open.json'
data = json.loads(asset.read_text(encoding='utf-8'))
assert data['source'] == 'yuhonas/free-exercise-db'
assert data['revision'] == 'f00c92c7dcf1216a928a52c3706c7ce8e2f71ed5'
assert data['license'] == 'Unlicense'
rows = data['exercises']
assert len(rows) == data['count'] == len({row['id'] for row in rows}) == 876
valid = [row for row in rows if row.get('id') and row.get('name', '').strip() and row.get('primaryMuscles') and any(step.strip() for step in row.get('instructions', []))]
assert len(valid) == 871
assert all(row['category'] and isinstance(row['secondaryMuscles'], list) for row in rows)
def normalized_name(value):
    value = unicodedata.normalize('NFKC', value).casefold().replace('ё', 'е')
    return re.sub(r'[^\w]+', ' ', value).strip()

names = defaultdict(list)
for row in valid:
    names[normalized_name(row['name'])].append(row['id'])
duplicates = {name: ids for name, ids in names.items() if len(ids) > 1}
assert not duplicates, f'Exact normalized source-name duplicates: {duplicates}'
missing_equipment = sum(not row.get('equipment', '').strip() for row in valid)
assert all(row['name'].strip() and not re.search(r'[<>\[\]{}]', row['name']) for row in valid)
print(f'PASS: {len(rows)} unique pinned records; {len(valid)} meet name/target/technique minimum; {len(duplicates)} exact normalized name duplicates; {missing_equipment} valid records omit equipment and are shown as unknown; 5 incomplete records are excluded by loader.')
