#!/usr/bin/env python3
"""Builds the compact resources of :places (src/main/resources/atlas/places) from the open sources.

    sh fetch_sources.sh <src>                     # downloads the pinned sources (about 40 MB) into <src>
    python3 build_places_data.py --src <src> --repo <repo root>
    python3 build_places_data.py --categories     # categories.json alone, after an edit of categories.py: no download
    python3 build_places_data.py --mends          # symbols.json, ui.json and cuisine.json after an edit of the
                                                  # symbols' mends and frames or the cuisines (tables.py) or of Atlas's
                                                  # own drawings (custom_icons.txt, atlas_icons.txt): no download
    python3 build_places_data.py --kinds --src <src>  # symbols.json with the open sets' icons kinds.tsv names that it
                                                  # lacks, from whichever sets <src> holds (Temaki alone will do)

Python 3 standard library only. Inputs, all pinned in fetch_sources.sh:
  id-tagging-schema 7.3.0 (ISC): presets, fields, translations
  name-suggestion-index 8.0.20260918 (BSD-3-Clause): brands and their Wikidata websites
  simple-icons 16.35.0 (CC0): brand glyphs and colours (fetched per matched slug)
  Maki 8.2.0, Temaki 5.13.0, Pinhead 15.25.0 (CC0): map symbols
  Lucide 1.56.0 (ISC): the UI symbol set
Atlas's own drawings (custom_icons.txt, own_icons.py) add icons and draw set icons anew, in one style; its own filled
symbols for kinds of place no set draws (atlas_icons.txt) go into symbols.json as atlas-<name>, beside the sets. A set
the owner imports in the symbol manager (icon_sets/<prefix>/) gives a kind the drawing kinds.tsv names, once Atlas may
carry the set; LICENSES.md credits each such set symbols.json carries, and the app's About & licences list with it.
"""
import argparse
import collections
import json
import os
import re
import subprocess
import sys
import unicodedata
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from svgpaths import fmt, svg_icon  # noqa: E402
import categories as CAT  # noqa: E402
import own_icons  # noqa: E402
import tables as T  # noqa: E402

OWN = own_icons.set_drawings()

LANGS = ['en', 'nl', 'de', 'fr', 'es', 'it', 'pt', 'pl', 'sv', 'da', 'nb', 'fi', 'cs', 'ja', 'zh-CN', 'ru', 'ar', 'tr']
HERE = os.path.dirname(os.path.abspath(__file__))
RESOURCES = os.path.join(HERE, '..', 'src', 'main', 'resources', 'atlas', 'places')
# the kinds of place and the symbol each wears: kept by hand and by KindsTest, read here whatever --out is
KINDS = os.path.join(RESOURCES, 'kinds.tsv')
# Atlas's own filled symbols, atlas-<name>: drawn as the open sets draw, for the kinds of place none of them draws
ATLAS = os.path.join(HERE, 'atlas_icons.txt')
ATLAS_PREFIX = 'atlas-'
# The sets the owner imports in the symbol manager: icon_sets/<prefix>/set.json (the set's name, licence and source) and
# icons/*.svg. A kind may wear one of their drawings once Atlas may carry the set (imported_set_problem); symbols.json
# carries the drawings kinds.tsv names, and LICENSES.md credits each set it carries one of (the parent, 10 November, the
# night: the build hook for imported sets).
SETS = os.path.join(HERE, 'icon_sets')
LICENSES = os.path.join(HERE, '..', 'LICENSES.md')
MINE_LICENCES = os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'kotlin', 'app', 'atlas', 'ui', 'mine', 'art',
                             'mine_licences.py')
OPEN_SETS = ('maki', 'temaki', 'pinhead')
# the prefixes of Atlas's own sets, which no imported set takes (the symbol manager refuses them too)
RESERVED = {'maki', 'temaki', 'pinhead', 'atlas', 'ui', 'logo', 'lucide', 'board'}

# Fields Facts and Cuisine read: id field -> canonical name. Variants of one field share the canonical name.
FACT_FIELDS = {
    'wheelchair': 'wheelchair', 'toilets/wheelchair': 'toilets/wheelchair', 'outdoor_seating': 'outdoor_seating',
    'indoor_seating': 'indoor_seating', 'takeaway': 'takeaway', 'delivery': 'delivery', 'drive_through': 'drive_through',
    'drive_through-US': 'drive_through', 'drive_through-except-US': 'drive_through', 'drive_through_common_for_atm': 'drive_through',
    'drive_through_uncommon_for_atm': 'drive_through', 'reservation': 'reservation', 'reservation_booking': 'reservation',
    'diet_multi': 'diet_multi', 'internet_access': 'internet_access', 'internet_access/fee': 'internet_access/fee',
    'payment_multi': 'payment_multi', 'payment_multi_fee': 'payment_multi', 'smoking': 'smoking', 'toilets': 'toilets',
    'changing_table': 'changing_table', 'dog': 'dog', 'dog_check': 'dog', 'dog_yes': 'dog', 'air_conditioning': 'air_conditioning',
    'level': 'level', 'level_semi': 'level', 'opening_hours': 'opening_hours', 'phone': 'phone', 'website': 'website',
    'fee': 'fee', 'charge_fee': 'charge_fee', 'capacity': 'capacity', 'capacity_parking': 'capacity', 'self_service': 'self_service',
    'organic': 'organic', 'second_hand': 'second_hand', 'stars': 'stars', 'rooms': 'rooms', 'sport': 'sport',
    'sport/sport_ice': 'sport', 'sport/sport_pub': 'sport', 'sport/sport_racing_motor': 'sport', 'sport/sport_racing_nonmotor': 'sport',
    'fuel/fuel_multi': 'fuel/fuel_multi', 'fuel/fuel_multi-RU': 'fuel/fuel_multi', 'recycling_accepts': 'recycling_accepts',
    'surface': 'surface', 'surface_sport_pitch': 'surface', 'lit': 'lit', 'incline': 'incline', 'access': 'access',
    'access_simple': 'access_simple', 'cuisine': 'cuisine', 'drinking_water': 'drinking_water', 'wikidata': 'wikidata',
    'wikipedia': 'wikipedia', 'opening_hours/drive_through': 'opening_hours/drive_through', 'self_checkout': 'self_checkout',
    'baby_feeding': 'baby_feeding', 'toilets/disposal': 'toilets/disposal', 'social_facility_for': 'social_facility_for',
}
GEOM = {'point': 'p', 'vertex': 'v', 'line': 'l', 'area': 'a', 'relation': 'r'}


def load(path):
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def dump(obj, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=False, separators=(',', ':'), sort_keys=False)
        f.write('\n')
    return os.path.getsize(path)


def norm(s):
    """The brand-name key: lower case, no accents, '&' as 'and', letters and digits only."""
    s = unicodedata.normalize('NFKD', s.lower().replace('&', ' and ').replace('+', ' plus '))
    s = ''.join(c for c in s if not unicodedata.combining(c))
    s = s.replace('ß', 'ss').replace('ø', 'o').replace('æ', 'ae').replace('ł', 'l').replace('đ', 'd')
    return re.sub(r'[\W_]+', '', s)


def countries(loc_list):
    out = []
    for x in loc_list or []:
        if not isinstance(x, str):
            continue
        k = x.lower()
        if k in T.REGIONS:
            out.extend(T.REGIONS[k])
        elif re.fullmatch(r'[a-z]{2}', k):
            out.append(k)
        elif re.match(r'[a-z]{2}-', k):
            out.append(k[:2])
    return sorted(set(out))


# ---------------------------------------------------------------------------------------------------------------- icons

class IconSets:
    def __init__(self, src):
        self.src = src
        self.maki = {f[:-4] for f in os.listdir(os.path.join(src, 'maki/x/package/icons')) if f.endswith('.svg')}
        self.temaki = {f[:-4] for f in os.listdir(os.path.join(src, 'temaki/x/package/icons')) if f.endswith('.svg')}
        self.pinhead = load(os.path.join(src, 'pinhead/index.complete.json'))['icons']

    def exists(self, icon):
        pre, _, name = icon.partition('-')
        return (pre == 'maki' and name in self.maki) or (pre == 'temaki' and name in self.temaki) or \
            (pre == 'pinhead' and name in self.pinhead)

    def resolve(self, icon):
        """A CC0 symbol id for a preset icon id, or None."""
        if not icon:
            return None
        if self.exists(icon):
            return icon
        if icon in T.ICON_STANDINS:
            sub = T.ICON_STANDINS[icon]
            if not self.exists(sub):
                raise SystemExit('stand-in %s for %s does not exist' % (sub, icon))
            return sub
        pre, _, name = icon.partition('-')
        n = name.replace('-', '_').lower()
        for cand in (n, n.replace('_alt', ''), n.replace('_', ''), n.rstrip('s')):
            if cand in self.pinhead:
                return 'pinhead-' + cand
        if name in self.maki:
            return 'maki-' + name
        if name.replace('-', '_') in self.temaki:
            return 'temaki-' + name.replace('-', '_')
        return None

    def svg(self, icon):
        pre, _, name = icon.partition('-')
        if pre == 'maki':
            return open(os.path.join(self.src, 'maki/x/package/icons', name + '.svg'), encoding='utf-8').read()
        if pre == 'temaki':
            return open(os.path.join(self.src, 'temaki/x/package/icons', name + '.svg'), encoding='utf-8').read()
        if pre == 'pinhead':
            return self.pinhead[name]['svg']
        raise KeyError(icon)


def ui_icons(src):
    out = {}
    for line in open(os.path.join(HERE, 'ui_icons.tsv'), encoding='utf-8'):
        if line.startswith('#') or not line.strip():
            continue
        atlas, lucide = line.rstrip('\n').split('\t')
        if lucide == 'custom':
            continue
        text = open(os.path.join(src, 'lucide', lucide + '.svg'), encoding='utf-8').read()
        text = re.sub(r'<!--.*?-->', '', text, flags=re.S)
        out['ui-' + atlas] = svg_icon(text)
    out.update(custom_ui_icons())
    return out


def custom_ui_icons():
    """Atlas's own UI icons (custom_icons.txt): the new ones, and Lucide's that Atlas draws anew."""
    return {k: svg_icon(v) for k, v in OWN.items() if k.startswith('ui-')}


def mend_icon(icon, drawn):
    """[icon]'s (viewBox, paths) as Atlas draws it: its own drawing where Atlas draws it anew (custom_icons.txt), else
    the mends of its drawing applied (tables.ICON_MENDS; one already in place is kept, one that no longer fits is
    reported and left out) and its frame moved onto the drawing (tables.ICON_FRAMES, from the set's frame at 0 0)."""
    if icon in OWN:
        if icon in T.ICON_MENDS or icon in T.ICON_FRAMES:
            print('mend or frame of %s is moot: Atlas draws it anew' % icon, file=sys.stderr)
        vb, paths = svg_icon(OWN[icon])
        return vb, [list(p) for p in paths]
    vb, paths = drawn[0], [list(p) for p in drawn[1]]
    for old, new in T.ICON_MENDS.get(icon, ()):
        hit = next((p for p in paths if old in p[1]), None)
        if hit:
            hit[1] = hit[1].replace(old, new, 1)
        elif not any(new in p[1] for p in paths):
            print('mend of %s no longer fits its drawing: %s' % (icon, old[:60]), file=sys.stderr)
    if icon in T.ICON_FRAMES:
        w, h = vb.split()[2:4]
        vb = ' '.join([fmt(v) for v in T.ICON_FRAMES[icon]] + [w, h])
    return vb, paths


def kinds_icons():
    """The sets' ids kinds.tsv gives its kinds: a kind's own pick, which no preset, category or cuisine may name
    (every place kind on a filled symbol, Temaki's first: the owner, 10 November, "Yes, ok"), the open sets' and those
    of the sets imported in the symbol manager."""
    prefixes = tuple(pre + '-' for pre in OPEN_SETS + tuple(imported_sets()))
    out = set()
    for line in open(KINDS, encoding='utf-8'):
        if line.startswith('#') or not line.strip():
            continue
        symbol = line.rstrip('\n').split('\t')[1]
        if symbol.startswith(prefixes):
            out.add(symbol)
    return out


def imported_sets():
    """{prefix: (its set.json, its icons folder)}: the sets imported in the symbol manager (icon_sets/<prefix>/)."""
    out = {}
    if not os.path.isdir(SETS):
        return out
    for pre in sorted(os.listdir(SETS)):
        icons = os.path.join(SETS, pre, 'icons')
        if pre in RESERVED or not re.fullmatch(r'[a-z][a-z0-9]{1,15}', pre) or not os.path.isdir(icons):
            continue
        try:
            meta = load(os.path.join(SETS, pre, 'set.json'))
        except (OSError, ValueError):
            continue
        if isinstance(meta, dict):
            out[pre] = (meta, icons)
    return out


def licence_kind(licence):
    """What a set's licence asks of Atlas: 'free' (CC0, public domain, the Unlicense: nothing), 'notice' (MIT, ISC, BSD,
    Apache 2.0, CC BY: its notice goes with the drawings), or None for any other (non-commercial, no-derivatives,
    share-alike, the GPLs, or one not known here): a look first."""
    s = re.sub(r'[^a-z0-9.]+', ' ', str(licence).lower())
    if re.search(r'\b(nc|nd|sa|non ?commercial|no ?derivs|no derivatives|share ?alike|proprietary|all rights reserved)\b'
                 r'|\b[al]?gpl', s):
        return None
    if re.search(r'\bcc0\b|\bunlicense\b|\bpublic domain\b', s):
        return 'free'
    if re.search(r'\b(mit|isc|apache|bsd)\b|\bcc by\b', s):
        return 'notice'
    return None


def licence_text(pre):
    """The licence kept beside imported set [pre] (icon_sets/<pre>/LICENSE, .txt or .md, or so spelt LICENCE), or ''."""
    for name in ('LICENSE', 'LICENSE.txt', 'LICENSE.md', 'LICENCE', 'LICENCE.txt', 'LICENCE.md'):
        path = os.path.join(SETS, pre, name)
        if os.path.isfile(path):
            return open(path, encoding='utf-8').read().strip()
    return ''


def licence_holder(text):
    """Who a set's licence notice names ("Copyright (c) 2026 Jane Doe"), as written: its credit on About. '' when it
    names no one (CC0) or only the template's blanks ("Copyright [yyyy] [name of copyright owner]")."""
    for line in text.replace('\r\n', '\n').split('\n'):
        s = re.sub(r'\s+', ' ', line).strip()
        if re.match(r'(copyright\b|©|\(c\))', s, re.I) and '[' not in s:
            return s.replace('|', '/')
    return ''


def imported_set_problem(pre, meta):
    """Why a kind may not wear a drawing of imported set [pre] (its set.json [meta]) yet, or None when it may: Atlas
    carries a set with its source and a licence that lets it, and for a licence that asks for its notice (MIT, ISC, BSD,
    Apache 2.0, CC BY) with that notice kept beside the set as icon_sets/<pre>/LICENSE (the symbol manager keeps the
    licence file of a folder it imports there)."""
    licence, source = str(meta.get('licence') or '').strip(), str(meta.get('source') or '').strip()
    if not licence or not source:
        return 'the set %s needs its licence and its source (import it again with both)' % pre
    kind = licence_kind(licence)
    if kind is None:
        return "the set %s's licence (%s) needs a look before Atlas carries it: ask Claude" % (pre, licence)
    if kind == 'notice' and not licence_text(pre):
        return ("the set %s's licence (%s) asks for its notice to go with the drawings: import the set's folder again "
                "with its licence file, or ask Claude to keep it as icon_sets/%s/LICENSE" % (pre, licence, pre))
    return None


def imported_drawing(icon, sets):
    """(viewBox, paths) of imported drawing [icon] as the build draws it (3 places, its mends and frame), or the build
    stops, saying why Atlas may not carry it."""
    pre, _, name = icon.partition('-')
    meta, folder = sets[pre]
    path = os.path.join(folder, name + '.svg')
    problem = imported_set_problem(pre, meta) or (None if os.path.isfile(path) else 'the set %s has no %s' % (pre, name))
    if problem:
        raise SystemExit('kinds.tsv names %s, but %s' % (icon, problem))
    return mend_icon(icon, svg_icon(open(path, encoding='utf-8').read(), places=3))


def credit_imported(carried):
    """LICENSES.md crediting every imported set whose drawings [carried] (symbols.json's ids) holds, and no other: a
    row each in the table of the open sets (its name, the holder its licence notice names, the licence's name, then
    where its drawings are and its source), which the app's About & licences list is made from (mine_licences.py, run
    again whenever LICENSES.md changes), and the notice of a licence that asks for one. A row of that table naming
    symbols.json under a prefix none of Atlas's own sets has is this function's, and so is a notice between its marks.
    A set whose folder is gone keeps its credit while symbols.json carries its drawings; one Atlas may no longer carry
    (its licence or notice gone) stops the build. A build with --out elsewhere changes no bundled file, and so no credit."""
    if os.path.realpath(OUT) != os.path.realpath(RESOURCES):
        return {'LICENSES.md': 'as it was (--out is not the bundled resources)'}
    sets = imported_sets()
    held = {i.partition('-')[0] for i in carried} - RESERVED
    worn, gone = sorted(held & set(sets)), held - set(sets)
    for pre in sorted(gone):
        print('symbols.json carries drawings of %s, whose set icon_sets/%s is gone: its credit stays' % (pre, pre),
              file=sys.stderr)
    old = open(LICENSES, encoding='utf-8').read()
    ours = re.compile(r'\| `symbols\.json` `([a-z][a-z0-9]*)-\*`')

    def stale(line):
        m = line.startswith('|') and ours.search(line)
        return bool(m) and m.group(1) not in RESERVED and m.group(1) not in gone

    text = '\n'.join(l for l in old.split('\n') if not stale(l))
    text = re.sub(r'<!-- imported set ([a-z0-9]+) -->\n.*?<!-- /imported set \1 -->\n\n',
                  lambda m: m.group(0) if m.group(1) in gone else '', text, flags=re.S)
    rows, notices = [], []
    for pre in worn:
        meta = sets[pre][0]
        problem = imported_set_problem(pre, meta)
        if problem:
            raise SystemExit('symbols.json carries drawings of %s, but %s' % (pre, problem))
        name, source, licence = (re.sub(r'\s+', ' ', str(meta.get(k) or '')).replace('|', '/').strip()
                                 for k in ('name', 'source', 'licence'))
        name = name or pre
        where = '[%s](%s)' % (name, source) if re.fullmatch(r'https?://\S+', source) else ', '.join(x for x in (name, source) if x)
        notice = licence_kind(licence) == 'notice'
        # About shows a licence by its name: an aside in brackets stays in the set's set.json
        named = re.sub(r'\s*\([^)]*\)', '', licence).strip() or licence
        rows.append('| %s | %s | %s | `symbols.json` `%s-*` (where a kind wears one of its drawings) | %s |'
                    % (name, licence_holder(licence_text(pre)), named, pre, where + ('; the notice is below' if notice else '')))
        if notice:
            # the notice as the set gives it; a line opening with | would read as a row of the table of bundled files
            body = '\n'.join(' ' + l if l.startswith('|') else l.rstrip() for l in licence_text(pre).replace('\r\n', '\n').split('\n'))
            notices.append('<!-- imported set %s -->\n## %s licence (%s)\n\n%s\n<!-- /imported set %s -->\n\n'
                           % (pre, re.sub(r'\s+licen[cs]e$', '', licence, flags=re.I), name, body, pre))
    if rows:
        lines = text.split('\n')
        at = max(i for i, l in enumerate(lines) if l.startswith('|') and ours.search(l))
        text = '\n'.join(lines[:at + 1] + rows + lines[at + 1:])
    if notices:
        mark = '## OpenStreetMap data in tests'
        text = text.replace(mark, ''.join(notices) + mark, 1) if mark in text else text.rstrip('\n') + '\n\n' + ''.join(notices)
    if text == old:
        return {'LICENSES.md': 'as it was'}
    with open(LICENSES, 'w', encoding='utf-8') as f:
        f.write(text)
    p = subprocess.run([sys.executable, '-I', MINE_LICENCES], capture_output=True, text=True, timeout=120)
    credits = 'credits ' + (', '.join(sorted(held)) or 'no imported set')
    if p.returncode:
        raise SystemExit('LICENSES.md %s, but mine_licences.py failed: %s' % (credits, p.stderr[-500:]))
    return {'LICENSES.md': credits, 'mine_licences.json': p.stdout.strip()}


def atlas_icons():
    """{atlas-<name>: (viewBox, paths)}: Atlas's own filled symbols (atlas_icons.txt), on Temaki's 15 grid and filled
    only, as the open sets draw, so that every list and pin has one weight (the owner, 10 November: "Yes, ok"). Their
    prefix is their own: nothing that counts or credits the open sets by theirs (maki-, temaki-, pinhead-) takes them."""
    out = {}
    if not os.path.exists(ATLAS):
        return out
    for n, line in enumerate(open(ATLAS, encoding='utf-8'), 1):
        if line.startswith('#') or not line.strip():
            continue
        name, body = line.rstrip('\n').split('\t')
        key = ATLAS_PREFIX + name
        if key in out or not re.fullmatch(r'[a-z0-9_]+', name):
            raise SystemExit('atlas_icons.txt:%d: %s is drawn twice or badly named' % (n, key))
        vb, paths = svg_icon('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 15 15">%s</svg>' % body, places=2)
        if not paths or any(flags.replace('e', '') != 'f' for flags, _ in paths):
            raise SystemExit('atlas_icons.txt:%d: %s is filled shapes only, no strokes' % (n, key))
        out[key] = (vb, [list(p) for p in paths])
    return out


def add_kinds_icons(src):
    """symbols.json with the icons kinds.tsv names that it lacks, from whichever of the pinned sets [src] holds (no
    download) or from the sets imported in the symbol manager, and LICENSES.md crediting each imported set it carries a
    drawing of. Each is drawn as a full build draws it (its mends and frame, or Atlas's own drawing)."""
    path = os.path.join(OUT, 'symbols.json')
    icons = load(path)
    dirs = {pre: os.path.join(src, pre, 'x', 'package', 'icons') for pre in ('maki', 'temaki')}
    pin = os.path.join(src, 'pinhead', 'index.complete.json')
    pinhead = load(pin)['icons'] if os.path.exists(pin) else {}
    sets = imported_sets()
    added, missing = [], []
    for icon in sorted(kinds_icons() - set(icons)):
        pre, _, name = icon.partition('-')
        svg = os.path.join(dirs.get(pre, ''), name + '.svg')
        if pre in sets:
            icons[icon] = imported_drawing(icon, sets)
            added.append(icon)
            continue
        if pre == 'pinhead' and name in pinhead:
            text = pinhead[name]['svg']
        elif pre in dirs and os.path.exists(svg):
            text = open(svg, encoding='utf-8').read()
        else:
            missing.append(icon)
            continue
        icons[icon] = mend_icon(icon, svg_icon(text, places=2 if pre in ('maki', 'pinhead') else 3))
        added.append(icon)
    if missing:
        raise SystemExit('kinds.tsv names icons that neither symbols.json nor %s has: %s' % (src, ', '.join(missing)))
    size = dump({k: [v[0], [list(p) for p in v[1]]] for k, v in sorted(icons.items())}, path)
    return dict({'added': added, 'symbols.json': size}, **credit_imported(set(icons)))


def cuisine_doc():
    """cuisine.json: each cuisine's symbol, and the country of a national cuisine (tables.CUISINE)."""
    return {k: [v[0]] + ([v[1]] if v[1] else []) for k, v in T.CUISINE.items()}


def mend_resources():
    """symbols.json, ui.json and cuisine.json as a full build would make them after an edit of the symbols' mends and
    frames or the cuisines (tables.py), of custom_icons.txt or of atlas_icons.txt, from the resources as they are: no
    sources needed. LICENSES.md credits the imported sets symbols.json carries drawings of."""
    report = {}
    carried = set()
    for name, own in (('symbols.json', atlas_icons()), ('ui.json', custom_ui_icons())):
        path = os.path.join(OUT, name)
        icons = load(path)
        if name == 'symbols.json':
            # Atlas's own filled symbols as atlas_icons.txt draws them now: one taken out of it goes
            icons = {k: v for k, v in icons.items() if not k.startswith(ATLAS_PREFIX)}
        icons.update(own)
        carried |= set(icons)
        mended = {k: mend_icon(k, v) for k, v in icons.items()}
        report[name] = dump({k: [v[0], [list(p) for p in v[1]]] for k, v in mended.items()}, path)
    for icon in sorted(set(OWN) - carried):
        print('own drawing of %s: Atlas carries no such icon' % icon, file=sys.stderr)
    cuisine = cuisine_doc()
    lacking = sorted({v[0] for v in cuisine.values()} - carried)
    if lacking:
        raise SystemExit('tables.CUISINE names symbols Atlas carries not: %s' % ' '.join(lacking))
    report['cuisine.json'] = dump(cuisine, os.path.join(OUT, 'cuisine.json'))
    report.update(credit_imported(carried))
    return report


# -------------------------------------------------------------------------------------------------------------- presets

def build_presets(src, icons):
    P = load(os.path.join(src, 'id/presets.min.json'))
    F = load(os.path.join(src, 'id/fields.min.json'))
    presets, unresolved = [], collections.Counter()
    for pid, p in P.items():
        if pid.startswith('@'):
            continue
        geom = ''.join(GEOM[g] for g in p.get('geometry', []) if g in GEOM)
        if not geom or geom == 'r':
            # a kind Atlas lists that is drawn only so gets a preset of Atlas's own in own_presets.tsv (a national park)
            continue
        icon = icons.resolve(p.get('icon'))
        if p.get('icon') and not icon:
            unresolved[p['icon']] += 1
        e = {'id': pid, 't': p.get('tags', {}), 'g': geom}
        add = {k: v for k, v in (p.get('addTags') or {}).items() if p.get('tags', {}).get(k) != v}
        if add:
            e['a'] = add
        if icon:
            e['i'] = icon
        for src_key, out_key in (('fields', 'f'), ('moreFields', 'm')):
            fs = []
            for f in p.get(src_key, []):
                if f.startswith('{') or f.startswith('@'):
                    raise SystemExit('unresolved field reference %s in %s' % (f, pid))
                c = FACT_FIELDS.get(f)
                if c and c not in fs and (out_key == 'f' or c not in e.get('f', [])):
                    fs.append(c)
            if fs:
                e[out_key] = fs
        if p.get('matchScore', 1) != 1:
            e['s'] = p['matchScore']
        if p.get('searchable') is False:
            e['x'] = 1
        ls = p.get('locationSet') or {}
        inc, exc = countries(ls.get('include')), countries(ls.get('exclude'))
        if inc and inc != ['*']:
            e['c'] = inc
        if exc and exc != ['*']:
            e['xc'] = exc
        presets.append(e)
    used_fields = {}
    for fid, canon in FACT_FIELDS.items():
        f = F.get(fid)
        if not f or canon in used_fields:
            continue
        used_fields[canon] = {'k': f.get('key') or (f.get('keys') or [''])[0], 't': f.get('type')}
        if f.get('options'):
            used_fields[canon]['o'] = f['options']
    return presets, used_fields, unresolved


def load_translation(src, lang):
    path = os.path.join(src, 'id', 'tr-%s.min.json' % lang)
    data = load(path)
    t = next(iter(data.values()))['presets']
    if lang == 'nb':  # Norwegian Bokmål: the 'no' file is far fuller; nb wins where both have a string
        extra = next(iter(load(os.path.join(src, 'id', 'tr-no.min.json')).values()))['presets']
        for sec in ('presets', 'fields', 'categories'):
            merged = dict(extra.get(sec, {}))
            merged.update(t.get(sec, {}))
            t[sec] = merged
    return t


def split_terms(v):
    if isinstance(v, list):
        return [x.strip() for x in v if x and x.strip()]
    if isinstance(v, str):
        return [x.strip() for x in re.split(r'[,\n]', v) if x.strip()]
    return []


def build_i18n(src, presets, fields, cats):
    ids = {p['id'] for p in presets}
    # Terms and aliases only where search uses them: searchable presets drawn as points or areas.
    searchable = {p['id'] for p in presets if not p.get('x') and set(p['g']) & set('pva')}
    sizes = {}
    english = load_translation(src, 'en')
    en_words = {}
    for pid, v in english.get('presets', {}).items():
        en_words[pid] = {norm(x) for x in [v.get('name') or ''] + split_terms(v.get('terms')) + split_terms(v.get('aliases'))}
    for lang in LANGS:
        t = english if lang == 'en' else load_translation(src, lang)
        out = {'p': {}, 'f': {}}
        for pid, v in t.get('presets', {}).items():
            if pid not in ids:
                continue
            name = v.get('name')
            terms, aliases = [], []
            if pid in searchable:
                # Search always looks at English too, so a translation's copies of English words add nothing.
                own = lambda xs: [x for x in xs if lang == 'en' or norm(x) not in en_words.get(pid, ())]
                terms, aliases = own(split_terms(v.get('terms'))), own(split_terms(v.get('aliases')))
            if name or terms or aliases:
                row = [name or '']
                if terms or aliases:
                    row.append(','.join(terms))
                if aliases:
                    row.append('\n'.join(aliases))
                out['p'][pid] = row
        for fid, canon in FACT_FIELDS.items():
            v = t.get('fields', {}).get(fid)
            if not v or canon in out['f']:
                continue
            opts = {}
            for k, o in (v.get('options') or {}).items():
                label = o.get('title') if isinstance(o, dict) else o
                if label:
                    opts[k] = label
            out['f'][canon] = [v.get('label', '')] + ([opts] if opts else [])
        sizes[lang] = dump(out, os.path.join(OUT, 'i18n', lang + '.json'))
    return sizes


# --------------------------------------------------------------------------------------------------------- categories

def bundled_translation(lang):
    """The presets' names in [lang] as the bundled i18n keeps them, for categories.json built without the sources."""
    names = load(os.path.join(OUT, 'i18n', lang + '.json'))['p']
    return {'presets': {pid: {'name': v[0]} for pid, v in names.items() if v}}


def build_categories(src, presets):
    trans = {lang: load_translation(src, lang) if src else bundled_translation(lang) for lang in LANGS}

    def tidy(name):
        name = re.sub(r'\s*[(（][^)）]*[)）]', '', name).strip()
        return name.split(' / ')[0].strip()

    def names(own, name_from):
        n = dict(zip(('en', 'nl', 'de', 'fr'), own))
        for lang in LANGS:
            if lang in n or not name_from:
                continue
            v = trans[lang].get('presets', {}).get(name_from, {}).get('name')
            if v and tidy(v):
                n[lang] = tidy(v)
        return n

    def also(sid):
        """The kinds of other shelves [sid] holds too, and the shelves it leads into (categories.py ALSO), each checked."""
        known = {'cat': {c[0] for c in CAT.CATEGORIES}, 'shelf': {g[0] for g in CAT.GROUPS} | set(CAT.FAMILY)}
        for node in CAT.ALSO.get(sid, ()):
            kind, _, ref = node.partition(':')
            if not (ref in known[kind] if kind in known else kind == 'preset' and '/' in ref) or ref == sid:
                raise SystemExit(f'ALSO {sid}: no such node {node}')
        return {'also': list(CAT.ALSO[sid])} if sid in CAT.ALSO else {}

    def order(node):
        """The kinds that end [node]'s row (categories.py LAST), each checked: a shelf's categories, a category's presets,
        none twice."""
        kind, _, ref = node.partition(':')
        end = CAT.LAST.get(node, ())
        for n in end:
            k, _, r = n.partition(':')
            # a preset of a key alone ("shop") is no kind of its own: its places are the category's
            ok = any(c[0] == r and c[2] == ref for c in CAT.CATEGORIES) if kind == 'shelf' else k == 'preset' and '/' in r and r in presets
            if not ok or k != ('cat' if kind == 'shelf' else 'preset'):
                raise SystemExit(f'LAST {node}: not one of its kinds: {n}')
        if len(set(end)) < len(end):
            raise SystemExit(f'LAST {node}: a kind twice')
        return {'last': list(end)} if end else {}

    shelves = {'shelf:' + g[0] for g in CAT.GROUPS} | {'shelf:' + s[0] for s in CAT.SHELVES}
    unknown = set(CAT.LAST) - shelves - {'cat:' + c[0] for c in CAT.CATEGORIES}
    if unknown:
        raise SystemExit('LAST names no shelf or category: ' + ', '.join(sorted(unknown)))
    out = []
    for gid, sym, colour, own in CAT.GROUPS:
        assert len(own) == len(LANGS), gid
        out.append({'id': gid, 'group': True, 'symbol': sym, 'colour': colour, 'names': dict(zip(CAT.LANGS, own))} | also(gid)
                   | order('shelf:' + gid))
    for family, sym, own in CAT.SHELVES:
        assert len(own) == len(LANGS), family
        out.append({'id': family, 'shelf': True, 'symbol': sym, 'colour': CAT.FAMILY[family], 'names': dict(zip(CAT.LANGS, own))}
                   | also(family) | order('shelf:' + family))
    unknown = set(CAT.ALSO) - {g[0] for g in CAT.GROUPS} - {s[0] for s in CAT.SHELVES}
    if unknown:
        raise SystemExit('ALSO names no shelf: ' + ', '.join(sorted(unknown)))
    ids = {g[0] for g in CAT.GROUPS}
    for cid, legacy, group, sym, rule, own, nf in CAT.CATEGORIES:
        if cid in ids:
            raise SystemExit('duplicate category id ' + cid)
        ids.add(cid)
        colour = next((g[2] for g in CAT.GROUPS if g[0] == group), None) or CAT.FAMILY[group]
        e = {'id': cid, 'parent': group if any(g[0] == group for g in CAT.GROUPS) else None, 'family': group,
             'symbol': sym, 'colour': colour, 'rule': rule, 'names': names(own, nf)}
        if legacy:
            e['legacy'] = legacy
        if cid in CAT.CORE:
            e['core'] = list(CAT.CORE[cid])
        out.append(e | order('cat:' + cid))
    return out


def build_links(presets):
    """The kinds that belong to each other (categories.py LINKS), each group checked: two kinds or more, none twice, each a
    category or one of [presets] by id, never a shelf."""
    cats = {c[0] for c in CAT.CATEGORIES}
    for group in CAT.LINKS:
        for node in group:
            kind, _, ref = node.partition(':')
            if not (kind == 'cat' and ref in cats or kind == 'preset' and ref in presets):
                raise SystemExit(f'LINKS: no such kind {node}')
        if len(group) < 2 or len(set(group)) < len(group):
            raise SystemExit(f'LINKS: two kinds or more, each once: {group}')
    return [list(g) for g in CAT.LINKS]


def categories_doc(cats, presets):
    """categories.json: what is not a place, the categories, the kinds that belong to each other."""
    return {'notAPlace': CAT.NOT_A_PLACE, 'categories': cats, 'links': build_links(presets)}


# ------------------------------------------------------------------------------------------------------------- brands

# 'other tags' joins k=v pairs with ';'; a value can be a ';' list itself (cuisine=burger;pizza), so Brands.kt reads a
# part without '=' as the continuation of the value before it. Keys never hold ';' or '='.
BRAND_COLUMNS = ['id', 'name', 'brand', 'wikidata', 'key=value', 'countries', 'not in', 'spread', 'logo', 'colour', 'text colour',
                 'match names', 'other tags']
WANTED_TREES = {'brands/amenity/' + v for v in 'fast_food restaurant cafe fuel charging_station bank pharmacy bar'.split()} | \
    {'brands/tourism/hotel', 'brands/leisure/fitness_centre'}


def raw_brands(repo):
    """
    (brand, brand:wikidata, key=value) of every branded element in the prototype's extracts; the brand by its name
    where only its Wikidata item is tagged, so that no brand is called by its item (Soho House was "Q7554758").
    """
    seen = set()
    keys = ('amenity', 'shop', 'tourism', 'leisure', 'craft', 'office', 'healthcare')
    for rel in ('data/ams/raw.json', 'data/eat/raw.json', 'data/errands/raw.json'):
        d = load(os.path.join(repo, rel))
        groups = [d['elements']] if 'elements' in d else [v for v in d.values() if isinstance(v, list) and v and isinstance(v[0], dict)]
        for g in groups:
            for e in g:
                t = e.get('tags') or {}
                if 'brand' in t or 'brand:wikidata' in t:
                    kv = next(('%s=%s' % (k, t[k]) for k in keys if k in t), None)
                    seen.add((t.get('brand') or (t.get('name') if 'brand:wikidata' in t else None), t.get('brand:wikidata'), kv))
    return seen


def build_brands(src, repo, keep):
    nsi = load(os.path.join(src, 'nsi/nsi.min.json'))['nsi']
    wd = load(os.path.join(src, 'nsi/wikidata.min.json'))['wikidata']
    items = []
    for tree, v in nsi.items():
        if not tree.startswith('brands/'):
            continue
        _, key, value = tree.split('/', 2)
        for it in v['items']:
            tags = it.get('tags', {})
            items.append({'tree': tree, 'id': it['id'], 'name': it.get('displayName') or tags.get('brand') or tags.get('name'),
                          'brand': tags.get('brand'), 'qid': tags.get('brand:wikidata'), 'kv': '%s=%s' % (key, tags.get(key, value)),
                          'inc': countries((it.get('locationSet') or {}).get('include')),
                          'exc': countries((it.get('locationSet') or {}).get('exclude')),
                          'match': [m for m in it.get('matchNames', [])][:6],
                          'tags': {k: v2 for k, v2 in tags.items() if k not in ('brand', 'brand:wikidata', 'name', key)
                                   and ':' not in k}})

    def spread(it):
        n = 250 if '*' in it['inc'] else len(it['inc'])
        return n - min(len(it['exc']), n - 1) if n else 0

    # The simple-icons index: normalised title or alias -> slug.
    si = load(os.path.join(src, 'si/simple-icons.json'))
    si_by_name = collections.defaultdict(set)
    si_meta = {}
    for e in si:
        si_meta[e['slug']] = e
        names = [e['title']]
        al = e.get('aliases') or {}
        names += al.get('aka', [])
        names += [d['title'] for d in al.get('dup', [])]
        names += list((al.get('loc') or {}).values())
        for n in names:
            si_by_name[norm(n)].add(e['slug'])
    name_qids = collections.defaultdict(set)
    for it in items:
        for n in {it['name'], it['brand']} - {None}:
            name_qids[norm(n)].add(it['qid'] or it['id'])

    def domain(url):
        m = re.match(r'https?://(?:www\d?\.)?([^/:]+)', url or '')
        return m.group(1).lower() if m else None

    def logo_for(it):
        cands = set()
        for n in {it['name'], it['brand']} - {None}:
            cands |= si_by_name.get(norm(n), set())
        if not cands:
            return None, None
        sites = {domain(u) for u in (wd.get(it['qid'] or '', {}).get('officialWebsites') or [])} - {None}
        for slug in sorted(cands):
            sd = domain(si_meta[slug]['source'])
            if sd and any(sd == s or sd.endswith('.' + s) or s.endswith('.' + sd) for s in sites):
                return slug, 'site'
        n = norm(it['name'] or it['brand'] or '')
        if it['name'] in T.LOGO_DENY:
            return None, None
        if len(cands) == 1 and len(name_qids[n]) == 1 and len(n) >= 3:
            return next(iter(cands)), 'name'
        return None, None

    wanted = [it for it in items if it['tree'] in WANTED_TREES or it['tree'].startswith('brands/shop/')]
    wanted.sort(key=lambda it: (-spread(it), logo_for(it)[0] is None, it['qid'] is None, it['name'].lower()))
    chosen = {it['id']: it for it in (wanted[:keep] if keep else wanted)}

    # Every brand seen in the prototype's extracts, matched by Wikidata id, else by name and main tag.
    by_qid = collections.defaultdict(list)
    by_name = collections.defaultdict(list)
    for it in items:
        if it['qid']:
            by_qid[it['qid']].append(it)
        for n in {it['name'], it['brand']} - {None}:
            by_name[norm(n)].append(it)
        for m in it['match']:
            by_name[norm(m)].append(it)
    synthetic, raw_hits, raw_total = [], 0, 0
    for brand, qid, kv in sorted(raw_brands(repo), key=lambda x: str(x)):
        raw_total += 1
        hits = by_qid.get(qid, []) if qid else []
        if not hits and brand and not qid:
            # by name only for brands without an item, and only within the same main key: a café is no supermarket
            key = (kv or '').split('=')[0]
            hits = [it for it in by_name.get(norm(brand), []) if it['kv'].split('=')[0] == key]
        if hits:
            raw_hits += 1
            best = next((h for h in hits if h['kv'] == kv), hits[0])
            chosen.setdefault(best['id'], best)
        elif brand or qid:
            sid = 'osm-' + (qid.lower() if qid else norm(brand))
            if sid not in chosen and all(s['id'] != sid for s in synthetic):
                synthetic.append({'id': sid, 'name': brand or qid, 'brand': brand, 'qid': qid, 'kv': kv or '', 'inc': [],
                                  'exc': [], 'match': [], 'tags': {}, 'tree': 'osm'})
    out, logos, how = [], {}, collections.Counter()
    for it in list(chosen.values()) + synthetic:
        slug, why = logo_for(it) if it['tree'] != 'osm' else (None, None)
        if slug:
            logos[slug] = si_meta[slug]
            how[why] += 1
        bg, fg = T.BRAND_COLOURS.get(it['qid'] or '') or \
            ((si_meta[slug]['hex'], None) if slug else (None, None))
        out.append([it['id'], it['name'], it['brand'] if it['brand'] and it['brand'] != it['name'] else '', it['qid'] or '', it['kv'],
                    ','.join(it['inc']) if it['inc'] != ['*'] else '', ','.join(it['exc']), str(spread(it)), slug or '', bg or '',
                    fg or '', '|'.join(it['match']), ';'.join('%s=%s' % kv for kv in sorted(it['tags'].items()))])
    out.sort(key=lambda r: r[0])
    return out, logos, {'kept': len(out), 'synthetic': len(synthetic), 'raw_brands': raw_total, 'raw_matched_nsi': raw_hits,
                        'logos': len(logos), 'logo_match': dict(how)}


def dump_tsv(rows, header, path):
    """One brand per line, tab-separated; tabs and line breaks inside values become spaces."""
    with open(path, 'w', encoding='utf-8') as f:
        f.write('# ' + '\t'.join(header) + '\n')
        for r in rows:
            f.write('\t'.join(re.sub(r'[\t\r\n]+', ' ', v) for v in r) + '\n')
    return os.path.getsize(path)


def fetch_logos(src, slugs):
    d = os.path.join(src, 'si', 'icons')
    os.makedirs(d, exist_ok=True)
    out = {}
    for slug in sorted(slugs):
        path = os.path.join(d, slug + '.svg')
        if not os.path.exists(path):
            url = 'https://cdn.jsdelivr.net/npm/simple-icons@16.35.0/icons/%s.svg' % slug
            req = urllib.request.Request(url, headers={'User-Agent': 'Atlas places build (https://github.com)'})
            with urllib.request.urlopen(req, timeout=30) as r:
                open(path, 'wb').write(r.read())
        out['logo-' + slug] = svg_icon(open(path, encoding='utf-8').read())
    return out


# --------------------------------------------------------------------------------------------------------------- main

def main():
    global OUT
    ap = argparse.ArgumentParser()
    ap.add_argument('--src')
    ap.add_argument('--repo')
    ap.add_argument('--out', default=RESOURCES)
    ap.add_argument('--brands', type=int, default=0, help='keep only the N most widespread NSI brands (0: all)')
    ap.add_argument('--categories', action='store_true',
                    help="categories.json alone, its presets' names from --src, else from the bundled i18n")
    ap.add_argument('--mends', action='store_true',
                    help="symbols.json and ui.json after an edit of the symbols' mends and frames (tables.py) or of "
                         "custom_icons.txt, from the resources as they are")
    ap.add_argument('--kinds', action='store_true',
                    help="symbols.json with the icons kinds.tsv names that it lacks, from the open sets in --src (no "
                         "download) or from the sets imported in the symbol manager, and LICENSES.md crediting those")
    a = ap.parse_args()
    OUT = os.path.abspath(a.out)
    src = a.src
    report = {}
    if a.kinds:
        if not src:
            ap.error('--kinds needs --src')
        print(json.dumps(add_kinds_icons(src), indent=1))
        return
    if a.mends:
        print(json.dumps(mend_resources(), indent=1))
        return
    if a.categories:
        with open(os.path.join(OUT, 'presets.json'), encoding='utf-8') as f:
            known = {p['id'] for p in json.load(f)['presets']}
        cats = build_categories(src, known)
        report['categories.json'] = dump(categories_doc(cats, known), os.path.join(OUT, 'categories.json'))
        print(json.dumps(report, indent=1))
        return
    if not src or not a.repo:
        ap.error('--src and --repo, or --categories')

    icons = IconSets(src)
    presets, fields, unresolved = build_presets(src, icons)
    report['presets'] = len(presets)
    report['presets_without_cc0_icon'] = sum(unresolved.values())
    report['unresolved_icons'] = sorted(unresolved)
    # fact fields by their index in one shared list: the names would repeat in every preset
    names = sorted({f for p in presets for k in ('f', 'm') for f in p.get(k, [])})
    compact = [dict(p, **{k: [names.index(f) for f in p[k]] for k in ('f', 'm') if k in p}) for p in presets]
    report['presets.json'] = dump({'fields': names, 'presets': compact}, os.path.join(OUT, 'presets.json'))
    report['fields.json'] = dump(fields, os.path.join(OUT, 'fields.json'))

    known = {p['id'] for p in presets}
    cats = build_categories(src, known)
    report['categories.json'] = dump(categories_doc(cats, known), os.path.join(OUT, 'categories.json'))
    report['i18n'] = build_i18n(src, presets, fields, cats)

    cuisine = cuisine_doc()
    report['cuisine.json'] = dump(cuisine, os.path.join(OUT, 'cuisine.json'))

    # Map symbols: every icon a preset, a category or a cuisine names (not a category's UI symbol, nor a drawing of the
    # boards' own, whose ids have a dot: Sport's ball is the boards' place.pitch).
    need = {p['i'] for p in presets if 'i' in p} | {
        c['symbol'] for c in cats if not c['symbol'].startswith('ui-') and '.' not in c['symbol']}
    # and the generic symbols Symbols.symbolFor falls back on (keep in step with its KEY_SYMBOLS); a cuisine drawn by Atlas
    # itself (ui-spaghetti, custom_icons.txt) is in ui.json, not in the open sets
    need |= {v[0] for v in T.CUISINE.values() if not v[0].startswith('ui-')} | {'maki-marker', 'maki-shop', 'maki-circle', 'temaki-briefcase', 'temaki-tools',
                                                    'maki-monument', 'maki-attraction', 'maki-park'}
    # and every kind's own pick in kinds.tsv, which no preset may name (Temaki's tap and cup for drinking water), a set's
    # imported in the symbol manager among them
    need |= kinds_icons()
    sets = imported_sets()
    poi = {}
    for icon in sorted(i for i in need if not i.startswith(ATLAS_PREFIX)):
        if icon.partition('-')[0] in sets:
            poi[icon] = imported_drawing(icon, sets)
            continue
        if not icons.exists(icon):
            raise SystemExit('missing symbol ' + icon)
        poi[icon] = mend_icon(icon, svg_icon(icons.svg(icon), places=2 if icon.startswith(('maki', 'pinhead')) else 3))
    # and Atlas's own filled symbols, which a category or a cuisine may name as a kind does (atlas_icons.txt)
    atlas = atlas_icons()
    missing = sorted(i for i in need if i.startswith(ATLAS_PREFIX) and i not in atlas)
    if missing:
        raise SystemExit('missing symbols, not drawn in atlas_icons.txt: ' + ', '.join(missing))
    poi.update(atlas)
    report['symbols'] = len(poi)
    report['symbols.json'] = dump({k: [v[0], [list(p) for p in v[1]]] for k, v in poi.items()}, os.path.join(OUT, 'symbols.json'))
    report.update(credit_imported(set(poi)))

    ui = {k: mend_icon(k, v) for k, v in ui_icons(src).items()}
    report['ui'] = len(ui)
    report['ui.json'] = dump({k: [v[0], [list(p) for p in v[1]]] for k, v in ui.items()}, os.path.join(OUT, 'ui.json'))

    brands, logo_meta, stats = build_brands(src, a.repo, a.brands)
    report.update(stats)
    report['brands.tsv'] = dump_tsv(brands, BRAND_COLUMNS, os.path.join(OUT, 'brands.tsv'))
    logos = fetch_logos(src, logo_meta)
    report['logos.json'] = dump({k: [v[0], [list(p) for p in v[1]]] for k, v in logos.items()}, os.path.join(OUT, 'logos.json'))
    total = sum(os.path.getsize(os.path.join(dp, f)) for dp, _, fs in os.walk(OUT) for f in fs)
    report['total_bytes'] = total
    print(json.dumps(report, indent=1, ensure_ascii=False))


if __name__ == '__main__':
    main()
