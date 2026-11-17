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

A tap on a line opens its licence in full. The two tables of `ui/mine/LICENSES.md` marked "the licence texts" and "the
notices" say which: each text once, cut whole from the file that holds it (a copy the repository ships, or one taken
from the licence's publisher into `art/licences/`), with the licence names that open it; and the lines whose own notice
or texts are not their credit and their licence's name. The texts go to `res/raw/mine_licence_texts.json`, read only
when one is opened; the list gets which text each licence name opens (`names`) and each line's notice and texts.

Atlas's own licence (the repository's `LICENSE`, the MIT License) opens from the top of the screen (`atlas`).

The list keeps a hash of each licence file (`made_from`) and of each file a text or notice is taken from
(`texts_from`). MineStateTest hashes them again and fails while the list is behind its files: run this again after
changing one. Python 3 standard library only.
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
TEXTS_OUT = os.path.join(ANDROID, 'app', 'src', 'main', 'res', 'raw', 'mine_licence_texts.json')
TEXTS = os.path.join(HERE, '..', 'LICENSES.md')  # ui/mine/LICENSES.md: the texts and the notices

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


def read(path):
    """A file's text, line ends as \\n."""
    with open(path, encoding='utf-8', newline='') as f:
        return f.read().replace('\r\n', '\n')


def tables(path):
    """(the nearest line above, the rows) of each table of a licence file."""
    out, rows, above = [], None, ''
    for line in read(path).split('\n'):
        if line.startswith('|'):
            if rows is None:
                rows = []
                out.append((above, rows))
            rows.append(line)
        else:
            rows = None
        if line.strip():
            above = line.strip()
    return out


def about_tables(path):
    """The rows of each table of a licence file, but a table marked not to be on About."""
    return [rows for above, rows in tables(path) if not MARK.match(above)]


def records(rows):
    """A table's rows as {heading: cell}, the headings in lower case, the cells cleaned."""
    head, out = None, []
    for line in rows:
        cells = [c.strip() for c in line.strip().strip('|').split('|')]
        if all(re.fullmatch(r':?-{2,}:?', c) for c in cells):
            continue
        if head is None:
            head = [c.lower() for c in cells]
            continue
        out.append({h: clean(cells[i]) if i < len(cells) else '' for i, h in enumerate(head)})
    return out


def marked(path, what):
    """The records of the table of [path] marked "not on About: [what]"."""
    found = [records(rows) for above, rows in tables(path) if MARK.match(above) and what in above]
    if len(found) != 1:
        raise SystemExit('%s has %d tables marked "%s"' % (path, len(found), what))
    return found[0]


def find(lines, cell, after, path):
    """The index of the first line from [after] that is [cell] (a cell ending in … is a line's start)."""
    for i in range(after, len(lines)):
        line = lines[i].strip()
        if line == cell or (cell.endswith('…') and line.startswith(cell[:-1])):
            return i
    raise SystemExit('%s has no line %r' % (path, cell))


def cut(path, start, end):
    """The lines of [path] from the line [start] to the line [end], whole (the first and last line when blank)."""
    lines = read(path).split('\n')
    while lines and not lines[-1].strip():
        lines.pop()
    a = find(lines, start, 0, path) if start else next(i for i, l in enumerate(lines) if l.strip())
    b = find(lines, end, a, path) if end else len(lines) - 1
    return '\n'.join(lines[a:b + 1])


def words(text):
    return re.sub(r'\s+', ' ', text).strip()


def licence_texts(items):
    """The texts, by id ({title, text}); which text each licence name opens; and the files they are taken from. Each
    line of [items] that has a notice or texts of its own gets them."""
    texts, names, used = {}, {}, set()
    for r in marked(TEXTS, 'the licence texts'):
        tid, path = r['text'], os.path.join(ANDROID, r['file'])
        if tid in texts:
            raise SystemExit('the licence text %s twice' % tid)
        texts[tid] = {'title': r['title'], 'text': cut(path, r['from'], r['to'])}
        used.add(path)
        for name in filter(None, (n.strip() for n in r['licences'].split(';'))):
            if names.setdefault(name, tid) != tid:
                raise SystemExit('the licence %s opens %s and %s' % (name, names[name], tid))
    by_what = {i['what']: i for i in items}
    for r in marked(TEXTS, 'the notices'):
        item = by_what.get(r['what'])
        if item is None:
            raise SystemExit('the notice of %s, which About does not list' % r['what'])
        paths = [os.path.join(ANDROID, p.strip()) for p in r['taken from'].split(';') if p.strip()]
        used.update(paths)
        held = ' '.join(words(read(p)) for p in paths)
        notice = [l.strip() for l in r['notice'].split('<br>') if l.strip()]
        for line in notice:
            if words(line) not in held:
                raise SystemExit('the notice of %s: %r is in none of %s' % (r['what'], line, r['taken from']))
        if notice:
            item['notice'] = '\n'.join(notice)
        own = [t.strip() for t in r['texts'].split(';') if t.strip()]
        for t in own:
            if t not in texts:
                raise SystemExit('the notice of %s names the text %s, which the texts table lacks' % (r['what'], t))
        if own:
            item['texts'] = own
    # Atlas's own licence, the repository's LICENSE: its copyright line, then the MIT License's text
    own = os.path.join(ROOT, 'LICENSE')
    lines = [l for l in read(own).split('\n') if l.strip()]
    notice = [l for l in lines if l.startswith('Copyright')]
    if len(notice) != 1 or words(read(own)).partition(notice[0])[2].strip() != words(texts['mit']['text']):
        raise SystemExit('LICENSE is no longer the MIT License with its copyright line')
    used.add(own)
    atlas = {'notice': notice[0], 'texts': ['mit']}
    files = {os.path.relpath(p, ANDROID).replace(os.sep, '/'): made_from(p) for p in sorted(used)}
    return texts, names, files, atlas


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
    texts, names, texts_from, atlas = licence_texts(items)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump({'items': items, 'names': names, 'atlas': atlas, 'made_from': hashes, 'texts_from': texts_from}, f,
                  ensure_ascii=False, separators=(',', ':'))
        f.write('\n')
    with open(TEXTS_OUT, 'w', encoding='utf-8') as f:
        json.dump({'texts': texts}, f, ensure_ascii=False, separators=(',', ':'))
        f.write('\n')
    print(len(items), 'licence lines from', len(paths), 'files;', len(texts), 'licence texts,',
          sum(len(t['text'].encode('utf-8')) for t in texts.values()), 'bytes')


if __name__ == '__main__':
    main()
