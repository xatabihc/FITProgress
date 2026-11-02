#!/usr/bin/env python3
"""Validate the pinned, bundled exercise snapshot without network access."""
import json
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
print(f'PASS: {len(rows)} unique pinned records; {len(valid)} meet name/target/technique minimum; 5 incomplete records are excluded by loader.')
