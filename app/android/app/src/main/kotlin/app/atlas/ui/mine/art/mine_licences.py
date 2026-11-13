#!/usr/bin/env python3
"""mine_licences.py: every font, symbol set, dataset, piece of ported code and library Atlas carries, each with its
credit and the name of its licence, for About & licences.

    python3 mine_licences.py   # writes app/src/main/res/raw/mine_licences.json

It reads the tables of the modules' LICENSES.md files and adds the libraries of the Gradle catalogue, which carry their
licences with their jars. A table's first column names the thing (a Family column instead in the boards' typefaces,
`| File | Family | Copyright | Licence |`), a Credit column (or else a Copyright column) is its credit, and a Licence
column (or else the last) the licence's name; the other columns (where it is, its source, notes) are for the people
who keep the file. A table whose nearest line above is a marker

    <!-- not on About: why -->

stays off the list: what only the tests read, what the credits card above the list shows already (the providers'),
methods Atlas's own code implements, Atlas's own work. Lines that name the same thing under the same licence are one
line, with each credit they give (a font two modules carry, a family's regular and italic files), in the first card
that shows it (Licences.section in AboutScreen.kt, mirrored by section() below). A card shows the fonts first: the
lines of a table headed Font, or with a Family column.

The list keeps a hash of each licence file (`made_from`). MineStateTest hashes them again and fails while the list is
behind its files: run this again after changing one. Python 3 standard library only.
"""
import glob
import hashlib
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
while not os.path.isdir(os.path.join(ROOT, 'app', 'android')):  # the repository's root, in any checkout of it
    ROOT = os.path.dirname(ROOT)
ANDROID = os.path.join(ROOT, 'app', 'android')
OUT = os.path.join(ANDROID, 'app', 'src', 'main', 'res', 'raw', 'mine_licences.json')

# The Gradle catalogue's libraries in the APK: name, credit, licence.
LIBRARIES = [
    ('AndroidX', 'The Android Open Source Project', 'Apache License 2.0'),
    ('Jetpack Compose and Material 3', 'The Android Open Source Project', 'Apache License 2.0'),
    ('Kotlin, kotlinx.coroutines and kotlinx.serialization', 'JetBrains s.r.o. and Kotlin Programming Language contributors', 'Apache License 2.0'),
    ('OkHttp and Okio', 'Square, Inc.', 'Apache License 2.0'),
    ('SQLite', 'The SQLite developers', 'Public domain'),
    ('BRouter', 'BRouter contributors', 'MIT License'),
    ('QR Code generator library', 'Project Nayuki', 'MIT License'),
]

MARK = re.compile(r'<!--\s*not on About\b')


def clean(cell):
    cell = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', cell)
    return cell.replace('`', '').strip()


def unquote(cell):
    """A credit written in quotes, as the source asks for it ("Slug algorithm by Eric Lengyel"), without them."""
    return cell[1:-1] if len(cell) > 1 and cell[0] == cell[-1] == '"' and '"' not in cell[1:-1] else cell


def section(module):
    """The card of About & licences a line is shown in, as Licences.section in AboutScreen.kt."""
    return {'providers': 0, 'places': 1, 'libraries': 3}.get(module, 2)


def licence_files():
    """The LICENSES.md files the list is made from: a module's, an app screen package's, and the map core's C++ one."""
    return sorted(glob.glob(os.path.join(ANDROID, '*', 'LICENSES.md'))
                  + glob.glob(os.path.join(ANDROID, 'app', 'src', 'main', 'kotlin', 'app', 'atlas', 'ui', '*', 'LICENSES.md'))
                  + glob.glob(os.path.join(ANDROID, 'mapcore', 'cpp', 'LICENSES.md')))


def made_from(path):
    """The hash of a licence file, line ends as \\n: what MineStateTest's sha256 takes of it."""
    with open(path, 'rb') as f:
        return hashlib.sha256(f.read().replace(b'\r\n', b'\n')).hexdigest()


def about_tables(path):
    """The rows of each table of a licence file, but a table marked not to be on About."""
    with open(path, encoding='utf-8', newline='') as f:
        lines = f.read().replace('\r\n', '\n').split('\n')
    out, rows, above = [], None, ''
    for line in lines:
        if line.startswith('|'):
            if rows is None:
                rows = []
                if not MARK.match(above):
                    out.append(rows)
            rows.append(line)
        else:
            rows = None
        if line.strip():
            above = line.strip()
    return out


def lines_of(path, module):
    found = []
    for rows in about_tables(path):
        head = None
        for line in rows:
            cells = [c.strip() for c in line.strip().strip('|').split('|')]
            if all(re.fullmatch(r':?-{2,}:?', c) for c in cells):
                continue
            if head is None:
                head = [c.lower() for c in cells]
                font = head[0] == 'font' or 'family' in head
                continue
            what_col = next((i for i, h in enumerate(head) if h == 'family'), 0)
            licence_col = next((i for i, h in enumerate(head) if h.startswith('licence') or h.startswith('license')), len(cells) - 1)
            credit_col = next((i for i, h in enumerate(head) if h.startswith('credit')), None)
            copyright_col = next((i for i, h in enumerate(head) if h == 'copyright'), None)
            entry = {'module': module, 'what': clean(cells[what_col]), 'licence': clean(cells[licence_col]), 'font': font}
            if credit_col is not None and credit_col < len(cells):
                credit = unquote(clean(cells[credit_col]))
            elif copyright_col is not None and copyright_col < len(cells):
                credit = clean(cells[copyright_col])  # as the font carries it, its quotes too
            else:
                credit = ''
            if credit:
                entry['credit'] = credit
            found.append(entry)
    return found


def once(found):
    """One line for each thing under each licence: the first of its lines in the lowest card, with every credit its
    lines give, in their order; the fonts before the rest."""
    first, credits = {}, {}
    for e in found:
        key = (e['what'], e['licence'])
        if key not in first or section(e['module']) < section(first[key]['module']):
            first[key] = e
        given = credits.setdefault(key, [])
        if e.get('credit') and e['credit'] not in given:
            given.append(e['credit'])
    out = []
    for e in found:
        key = (e['what'], e['licence'])
        if first[key] is e:
            line = {'module': e['module'], 'what': e['what'], 'licence': e['licence']}
            if credits[key]:
                line['credit'] = '\n'.join(credits[key])
            out.append((not e.get('font'), len(out), line))
    return [line for _, _, line in sorted(out)]


def main():
    found = []
    hashes = {}
    paths = licence_files()
    for p in paths:
        rel = os.path.relpath(p, ANDROID)
        module = rel.split(os.sep)[0] if '/ui/' not in rel.replace(os.sep, '/') else 'app ' + rel.split(os.sep)[-2]
        found += lines_of(p, module)
        hashes[rel.replace(os.sep, '/')] = made_from(p)
    for what, credit, lic in LIBRARIES:
        found.append({'module': 'libraries', 'what': what, 'licence': lic, 'credit': credit})
    items = once(found)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump({'items': items, 'made_from': hashes}, f, ensure_ascii=False, separators=(',', ':'))
        f.write('\n')
    print(len(items), 'licence lines from', len(paths), 'files')


if __name__ == '__main__':
    main()
