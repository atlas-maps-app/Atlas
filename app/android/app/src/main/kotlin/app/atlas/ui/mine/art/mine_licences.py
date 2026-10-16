#!/usr/bin/env python3
"""mine_licences.py: every bundled dataset, font and library with its licence, for About & licences.

    python3 mine_licences.py   # writes app/src/main/res/raw/mine_licences.json

It reads the LICENSES.md files of the modules (the tables `| What | Where | Licence |`) and adds the libraries of the
Gradle catalogue, which carry their licences with their jars. Python 3 standard library only.
"""
import glob
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
while not os.path.isdir(os.path.join(ROOT, 'canvas')):
    ROOT = os.path.dirname(ROOT)
ANDROID = os.path.join(ROOT, 'app', 'android')
OUT = os.path.join(ANDROID, 'app', 'src', 'main', 'res', 'raw', 'mine_licences.json')

LIBRARIES = [
    ('AndroidX: core, activity, lifecycle, navigation 3, DataStore, WorkManager, Media3, Car App', 'Apache License 2.0'),
    ('Jetpack Compose and Material 3', 'Apache License 2.0'),
    ('Kotlin and kotlinx.coroutines, kotlinx.serialization', 'Apache License 2.0'),
    ('OkHttp and Okio (Square)', 'Apache License 2.0'),
    ('SQLite, as bundled by androidx.sqlite', 'Public domain (SQLite); Apache License 2.0 (androidx.sqlite)'),
    ('BRouter (offline routing, vendored as Java source in the :brouter module)', 'MIT License, © 2019 BRouter contributors'),
]


def clean(cell):
    cell = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', cell)
    return cell.replace('`', '').strip()


def tables(path, module):
    out = []
    head = None
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if not line.startswith('|'):
                head = None
                continue
            cells = [c.strip() for c in line.strip().strip('|').split('|')]
            if all(re.fullmatch(r':?-{2,}:?', c) for c in cells):
                continue
            if head is None:
                head = [c.lower() for c in cells]
                continue
            licence_col = next((i for i, h in enumerate(head) if h.startswith('licence') or h.startswith('license')), len(cells) - 1)
            credit_col = next((i for i, h in enumerate(head) if h.startswith('credit')), None)
            entry = {'module': module, 'what': clean(cells[0]), 'licence': clean(cells[licence_col])}
            if credit_col is not None and credit_col < len(cells):
                entry['credit'] = clean(cells[credit_col]).strip('"')
            out.append(entry)
    return out


def main():
    found = []
    paths = sorted(glob.glob(os.path.join(ANDROID, '*', 'LICENSES.md')) + glob.glob(os.path.join(ANDROID, 'app', 'src', 'main', 'kotlin', 'app', 'atlas', 'ui', '*', 'LICENSES.md')))
    for p in paths:
        rel = os.path.relpath(p, ANDROID)
        module = rel.split(os.sep)[0] if '/ui/' not in rel.replace(os.sep, '/') else 'app ' + rel.split(os.sep)[-2]
        found += tables(p, module)
    for what, lic in LIBRARIES:
        found.append({'module': 'libraries', 'what': what, 'licence': lic})
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump({'items': found}, f, ensure_ascii=False, separators=(',', ':'))
        f.write('\n')
    print(len(found), 'licence lines from', len(paths), 'files')


if __name__ == '__main__':
    main()
