#!/usr/bin/env python3
"""How many of a country's windows hold a large island's outline or a coastline piece (packs.yml on a proof branch).

    python3 -I census-water.py <country.txt> <id.windows> <id.windows.idx> [minutes]

As notes/countries/kinds.py measured Norway's 59 uncut windows ("50 of the 59 hold a large island outline or
coastline piece"): an element is large when its compact JSON is 1 % or more of its window's elements' (kinds.py shares
a window's bytes out by that length); an island's outline is place=island, islet or archipelago, a coastline piece
natural=coastline, bay, strait or cape, place=sea or ocean, or natural=water with water=sea or fjord (kinds.py's
"islands (their coast)" and "sea and coast", its tags without kinds.py's META keys). Also counted: the windows that
hold a natural=coastline way at all (where load/Regions.cpp's sea_from_coast can close a sea, and region_water's guard
can drop it), and of the windows `packer country` did not build (country.txt's "not built" lines) those refused with
"the water differs", with how many of them hold a large one. A window whose data holds none of the values these tags
take is not parsed. Four processes, the windows in an order of their own; it stops after [minutes] (40) and says how
many it read, a sample of the country.
"""
import concurrent.futures
import gzip
import json
import re
import struct
import sys
import time

META = {'source', 'source:date', 'note', 'fixme', 'FIXME', 'created_by', 'attribution', 'source:geometry', 'nvdb:id',
        'ref:kystverket', 'kartverket:id'}
VALUES = (b'"coastline"', b'"island"', b'"islet"', b'"archipelago"', b'"bay"', b'"strait"', b'"cape"', b'"sea"',
          b'"ocean"', b'"fjord"')


def own(t):
    return {k: v for k, v in (t or {}).items() if k not in META and not k.startswith('source:')}


def kind(t):
    """kinds.py's kind, for its two kinds alone: 'island', 'coast' or None."""
    t = own(t)
    if not t or t.get('natural') == 'glacier':
        return None
    if t.get('place') in ('islet', 'island', 'archipelago'):
        return 'island'
    if (t.get('natural') in ('coastline', 'bay', 'strait', 'cape') or t.get('place') in ('sea', 'ocean') or
            t.get('natural') == 'water' and t.get('water') in ('sea', 'fjord')):
        return 'coast'
    return None


def compact(v):
    return len(json.dumps(v, separators=(',', ':'), ensure_ascii=False).encode())


def index(path):
    b = open(path, 'rb').read()
    n = struct.unpack_from('<I', b, 8)[0]
    return [struct.unpack_from('<IIQI', b, 32 + i * 52) for i in range(n)]


def refused(report):
    out = {}
    for line in open(report, encoding='utf-8'):
        m = re.match(r'not built(?:, left out)?: 14/(\d+)/(\d+): (.*)$', line.strip())
        if m:
            out[(int(m.group(1)), int(m.group(2)))] = m.group(3)
    return out


DATA = None


def start(path):
    global DATA
    DATA = open(path, 'rb')


def census(entry):
    x, y, off, ln = entry
    DATA.seek(off)
    raw = gzip.decompress(DATA.read(ln))
    if not any(v in raw for v in VALUES):
        return x, y, False, None, False
    els = json.loads(raw).get('elements', [])
    total = compact(els) - 2 - max(0, len(els) - 1)
    large = None
    coastline = False
    for el in els:
        k = kind(el.get('tags'))
        if k is None:
            continue
        if k == 'coast' and el.get('type') == 'way' and (el.get('tags') or {}).get('natural') == 'coastline':
            coastline = True
        if total > 0 and compact(el) >= 0.01 * total and large != 'island':
            large = k
    return x, y, True, large, coastline


def main(report, data, idx, minutes=40):
    started = time.time()
    # in an order of their own, so that a read the time stops is a sample of the whole country, not its west
    entries = sorted(index(idx), key=lambda e: ((e[0] * 2654435761) ^ (e[1] * 40503)) & 0xFFFFFFFF)
    bad = refused(report) if report else {}
    water = {k for k, why in bad.items() if 'the water differs' in why}
    read = parsed = 0
    large = {'island': [], 'coast': []}
    coastline = 0
    held = set()
    with concurrent.futures.ProcessPoolExecutor(4, initializer=start, initargs=(data,)) as pool:
        for x, y, p, k, c in pool.map(census, entries, chunksize=32):
            read += 1
            parsed += p
            coastline += c
            if k:
                large[k].append(f'14/{x}/{y}')
                held.add((x, y))
            if time.time() - started > minutes * 60:
                print(f'stopped after {minutes} min', flush=True)
                pool.shutdown(wait=False, cancel_futures=True)
                break
    n = len(large['island']) + len(large['coast'])
    print(f'{read} of the {len(entries)} windows read ({parsed} parsed, {time.time() - started:.0f} s): {n} hold a large '
          f"island's outline or coastline piece ({len(large['island'])} an island's, {len(large['coast'])} a coastline "
          f'piece alone); {coastline} hold a coastline way at all')
    for k in ('island', 'coast'):
        if large[k]:
            print(f'  {k}: ' + ', '.join(large[k][:20]) + (f', and {len(large[k]) - 20} more' if len(large[k]) > 20 else ''))
    print(f'not built: {len(bad)}, {len(water)} of them refused with "the water differs", '
          f'{len(water & held)} of those holding a large one')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3], *(int(a) for a in sys.argv[4:5]))
