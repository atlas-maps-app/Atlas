#!/usr/bin/env python3
"""The buildings behind each count a country's cleaning left that should be 0 (packs.yml on gates-proof alone).

    python3 -I locate-leftovers.py <country.txt> <id.windows> <id.windows.idx> <atlas_stagep> <out-dir> [minutes]

`packer country`'s report names the windows ("the cleaning left counts that should be 0 in N windows: 14/x/y:
unmeasured 1; ...", its first 20). For each, its answer is read from the windows' data by its index entry, and the
core's own tool (`atlas_stagep windows`, one window) is run on less and less of it: first its buildings alone (the
rest dropped when the count holds without it), then the box about the tile halved while the buildings in one half
alone keep the count, then the buildings dropped a chunk at a time while it holds. What is left is printed, ids and
tags, and saved as a seed (`<out-dir>/14-x-y.json.gz`, the buildings alone, where they keep the count by themselves).
Four windows at a time; each stops after [minutes] (15) and says how far it got.
"""
import concurrent.futures
import gzip
import json
import math
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time

Z = 1 << 14
KEYS = ('unsettled', 'uncovered', 'pairs', 'unmeasured')


def windows_named(report):
    for line in open(report, encoding='utf-8'):
        if line.startswith('the cleaning left counts that should be 0 in '):
            body = line.split(' windows: ', 1)[1].strip()
            for part in body.split('; '):
                m = re.match(r'14/(\d+)/(\d+): (.*)$', part)
                if m:
                    counts = dict((k, int(v)) for k, v in (c.split(' ') for c in m.group(3).split(', ')))
                    yield int(m.group(1)), int(m.group(2)), counts


def index(path):
    b = open(path, 'rb').read()
    n = struct.unpack_from('<I', b, 8)[0]
    out = {}
    for i in range(n):
        x, y, off, ln = struct.unpack_from('<IIQI', b, 32 + i * 52)
        out[(x, y)] = (off, ln)
    return out


def box(x, y):
    w, e = x / Z * 360 - 180, (x + 1) / Z * 360 - 180
    n = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / Z))))
    s = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + 1) / Z))))
    return s - 0.0018, w - 0.003, n + 0.0018, e + 0.003


def pts(el):
    return [p for p in (el.get('geometry') or [q for m in el.get('members', []) for q in (m.get('geometry') or [])]) if p]


def isb(el):
    t = el.get('tags') or {}
    return 'building' in t or 'building:part' in t


def locate(job):
    x, y, counts, answer, tool, out, minutes = job
    key = next(k for k in KEYS if counts.get(k))
    started = time.time()
    said = []
    d = json.loads(answer)
    work = tempfile.mkdtemp(prefix=f'locate-{x}-{y}-')
    runs = [0]

    def count(elements):
        doc = dict(d)
        doc['elements'] = elements
        with open(f'{work}/w.json', 'w') as f:
            json.dump(doc, f, separators=(',', ':'))
        with open(f'{work}/list.txt', 'w') as f:
            f.write(f'{x} {y} {work}/w.json\n')
        runs[0] += 1
        line = subprocess.run([tool, 'windows', f'{work}/list.txt', f'{work}/out', '1'], capture_output=True, text=True).stdout
        m = re.search(rf' {key}=(\d+)', line)
        return int(m.group(1)) if m else 0, line.strip()

    def late():
        return time.time() - started > minutes * 60

    try:
        blds = [el for el in d['elements'] if isb(el)]
        others = [el for el in d['elements'] if not isb(el)]
        whole, line = count(d['elements'])
        said.append(f'14/{x}/{y}: {key} {whole} in the whole window, {len(blds)} buildings and parts ({line.split(" ", 5)[-1]})')
        if whole == 0:
            said.append('  the tool does not give the count here: not located')
            return x, y, said, None
        if count(blds)[0]:
            others = []
            said.append('  its buildings alone keep it')
        s, w, n, e = box(x, y)
        kept = blds
        for _ in range(16):
            if late():
                break
            halves = [(s, w, n, (w + e) / 2), (s, (w + e) / 2, n, e)] if (e - w) * 0.63 > (n - s) else [(s, w, (s + n) / 2, e), ((s + n) / 2, w, n, e)]
            for h in halves:
                k = [el for el in kept if any(h[0] <= p['lat'] <= h[2] and h[1] <= p['lon'] <= h[3] for p in pts(el))]
                if k and count(others + k)[0]:
                    (s, w, n, e), kept = h, k
                    break
            else:
                break
        said.append(f'  halved to [{s:.5f}, {w:.5f}, {n:.5f}, {e:.5f}]: {len(kept)} buildings and parts')
        size = max(1, len(kept) // 2)
        while not late():
            i = 0
            while i < len(kept) and not late():
                trial = kept[:i] + kept[i + size:]
                if trial and count(others + trial)[0]:
                    kept = trial
                else:
                    i += size
            if size == 1:
                break
            size = max(1, size // 2)
        done = not late()
        mine, line = count(kept)
        said.append(f'  {"left" if done else "stopped after " + str(minutes) + " min with"} {len(kept)}; '
                    f'{"alone they keep it" if mine else "alone they do not keep it (the rest of the window is needed)"}: '
                    f'{line.split(" ", 5)[-1]} ({runs[0]} runs, {time.time() - started:.0f} s)')
        for el in sorted(kept, key=lambda el: -len(pts(el)))[:12]:
            t = el.get('tags') or {}
            p = pts(el)
            said.append(f"    {el['type']}/{el['id']} {len(p)} points at {p[0]['lat']:.5f} {p[0]['lon']:.5f} " +
                        json.dumps({k: t[k] for k in ('building', 'building:part', 'name', 'building:levels', 'height', 'min_height', 'min_level', 'layer') if k in t}, ensure_ascii=False))
        if len(kept) > 12:
            said.append(f'    and {len(kept) - 12} more')
        seed = None
        if mine:
            seed = f'{out}/14-{x}-{y}.json.gz'
            doc = dict(d)
            doc['elements'] = kept
            with gzip.open(seed, 'wt') as f:
                json.dump(doc, f, separators=(',', ':'))
        return x, y, said, seed
    except Exception as x_:  # a window that fails here says so; the others go on
        said.append(f'  failed: {x_!r}')
        return x, y, said, None
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main(report, data, idx, tool, out, minutes=15):
    os.makedirs(out, exist_ok=True)
    named = list(windows_named(report))
    if not named:
        print('the cleaning left no count that should be 0: nothing to locate')
        return
    where = index(idx)
    jobs = []
    with open(data, 'rb') as f:
        for x, y, counts in named:
            off, ln = where[(x, y)]
            f.seek(off)
            jobs.append((x, y, counts, gzip.decompress(f.read(ln)), tool, out, minutes))
    print(f'{len(jobs)} windows to locate, four at a time, at most {minutes} min each', flush=True)
    with concurrent.futures.ProcessPoolExecutor(4) as pool:
        for _, _, said, _ in pool.map(locate, jobs):
            print('\n'.join(said), flush=True)


if __name__ == '__main__':
    main(*sys.argv[1:6], *(int(a) for a in sys.argv[6:7]))
