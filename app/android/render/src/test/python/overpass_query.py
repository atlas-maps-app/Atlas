#!/usr/bin/env python3
"""Prints the Overpass query fetch/regions.py makes for a region: overpass_query.py <repo> <name>"""
import os, runpy, sys
repo, name = sys.argv[1], sys.argv[2]
P = runpy.run_path(os.path.join(repo, 'data', 'regions', 'places.py'))['PLACES']
s, w, n, e = P[name]['bbox']
print(('[out:json][timeout:240][bbox:%s,%s,%s,%s];'
       '(node(if:count_tags()>0);way;relation[type=multipolygon];relation[boundary~"^(national_park|protected_area)$"];);'
       'out geom;') % (s, w, n, e))
