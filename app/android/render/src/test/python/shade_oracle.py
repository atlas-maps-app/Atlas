#!/usr/bin/env python3
"""Oracle for Shade.kt: render/shade.py on the New York extract (data/regions/newyork, local only). shade.py reads
<render>/../osm/raw.json and real.load() reads real.OSM: both are pointed at an osm/ folder made by real_render_oracle.mkosm
(the extract, plus a made-up building-year and street-tree file). Run with PYTHONHASHSEED=0.

    shade_oracle.py plan REPO WORKDIR OUTFILE      load_graph, TreeShade, sun_at, dijkstra, score and plan, as JSON
"""
import json, os, sys, time, hashlib
import real as R
import shade as SH
import real_render_oracle as RRO


def main():
    repo, work, outfile = (os.path.abspath(a) for a in sys.argv[2:5])
    osm = os.path.join(work, 'osm')
    RRO.mkosm(repo, osm)
    R.OSM = osm
    SH.HERE = os.path.join(work, 'render'); os.makedirs(SH.HERE, exist_ok=True)          # shade.py reads HERE/../osm/raw.json
    t0 = time.time()
    S = R.load()
    adj, coord, kind, parks = SH.load_graph()
    out = dict(nodes=len(adj), edges=sum(len(v) for v in adj.values()), parks={k: len(v) for k, v in parks.items()},
               adj_order=list(adj.keys())[:50], plans=[])
    names = [k for k, v in parks.items() if len(v) > 3][:3]
    for dest in names:
        for m in [(2026, 9, 30, 12, 0), (2026, 9, 30, 15, 0), (2026, 9, 30, 17, 20), (2026, 7, 15, 14, 0)]:
            P = SH.plan(m, S, dest)
            f, s = P['fast'], P['shady']
            segs = lambda q: hashlib.md5(json.dumps(q['segs']).encode()).hexdigest()
            out['plans'].append(dict(dest=dest, moment=m, az=P['az'], el=P['el'], weight=P['weight'], src=P['src'], dst=P['dst'],
                                     fast=dict(length=f['length'], sun=f['sun'], shade=f['shade'], segs=segs(f), n=len(f['segs'])),
                                     shady=dict(length=s['length'], sun=s['sun'], shade=s['shade'], segs=segs(s), n=len(s['segs'])),
                                     fast_path=P['fast_path'], shady_path=P['shady_path']))
    out['seconds'] = time.time() - t0
    json.dump(out, open(outfile, 'w'))


if __name__ == '__main__':
    main()
