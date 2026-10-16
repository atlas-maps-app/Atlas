#!/usr/bin/env python3
"""mine_strings.py: the mine package's words in four languages from one table.

    python3 mine_strings.py   # writes res/values{,-nl,-de,-fr}/strings_mine.xml from mine_strings.txt

mine_strings.txt has one string per line, `key|English|Nederlands|Deutsch|Français` (German and French may be empty and
then fall back to English); lines starting with # are comments. Keys are written with a `mine_` prefix unless they
already have one. Python 3 standard library only.
"""
import os
import re
from xml.sax.saxutils import escape

HERE = os.path.dirname(os.path.abspath(__file__))
RES = HERE
while not os.path.isdir(os.path.join(RES, 'res')) or not os.path.isdir(os.path.join(RES, 'kotlin')):
    RES = os.path.dirname(RES)
RES = os.path.join(RES, 'res')
LANGS = [('', 1), ('-nl', 2), ('-de', 3), ('-fr', 4)]


def quote(s):
    s = escape(s).replace("'", "\\'").replace('"', '\\"')
    return s.replace('\\n', '\\n')


def main():
    rows = []
    plural_rows = []
    with open(os.path.join(HERE, 'mine_strings.txt'), encoding='utf-8') as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line.strip() or line.startswith('#'):
                continue
            parts = line.split('|')
            if parts[0].strip().endswith('#'):
                # key#|en one|en other|nl one|nl other|de one|de other|fr one|fr other
                while len(parts) < 9:
                    parts.append('')
                plural_rows.append((parts[0].strip()[:-1] if parts[0].strip()[:-1].startswith('mine_') else 'mine_' + parts[0].strip()[:-1], parts[1:9]))
                continue
            while len(parts) < 5:
                parts.append('')
            key = parts[0].strip()
            if not re.fullmatch(r'[a-z0-9_]+', key):
                raise SystemExit('line %d: bad key %r' % (n, key))
            if not key.startswith('mine_'):
                key = 'mine_' + key
            if not parts[1]:
                raise SystemExit('line %d: %s has no English' % (n, key))
            rows.append((key, parts[1:5]))
    # A key written twice keeps its last text (a later section corrects an earlier one).
    last = {key: i for i, (key, _) in enumerate(rows)}
    rows = [r for i, r in enumerate(rows) if last[r[0]] == i]
    for suffix, col in LANGS:
        d = os.path.join(RES, 'values' + suffix)
        os.makedirs(d, exist_ok=True)
        out = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
        for key, texts in rows:
            t = texts[col - 1]
            if not t:
                continue
            out.append('    <string name="%s">%s</string>' % (key, quote(t)))
        for key, texts in plural_rows:
            one, other = texts[(col - 1) * 2], texts[(col - 1) * 2 + 1]
            if not other:
                continue
            out.append('    <plurals name="%s">' % key)
            out.append('        <item quantity="one">%s</item>' % quote(one or other))
            out.append('        <item quantity="other">%s</item>' % quote(other))
            out.append('    </plurals>')
        out.append('</resources>')
        with open(os.path.join(d, 'strings_mine.xml'), 'w', encoding='utf-8') as f:
            f.write('\n'.join(out) + '\n')
    print(len(rows), 'strings')


if __name__ == '__main__':
    main()
