#!/usr/bin/env python3
"""prints W, H, the count of null cells and a SHA-256 of the height grid of a dem.json (heights as integers of 1e-6 m).
usage: digest_dem.py <dem.json>"""
import hashlib, json, sys
d = json.load(open(sys.argv[1]))
h = hashlib.sha256(); nulls = 0
for row in d['z']:
    for v in row:
        if v is None: nulls += 1; h.update(b'n,')
        else: h.update(('%d,' % int(round(v * 1e6))).encode())
    h.update(b'\n')
print(len(d['z'][0]), len(d['z']), nulls, h.hexdigest())
