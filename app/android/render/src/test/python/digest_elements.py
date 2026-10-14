#!/usr/bin/env python3
"""Independent check of the pack format: prints the element count and a SHA-256 of a canonical form of an Overpass
extract's elements (every float as its integer count of 1e-7 degrees, compact JSON, the extract's key order).
The Kotlin test makes the same digest from the pack's elements, so a field the reader drops shows up as a different hash.
usage: digest_elements.py <raw.json>"""
import hashlib, json, sys

def conv(o):
    if isinstance(o, float): return int(round(o * 1e7))
    if isinstance(o, dict): return {k: conv(v) for k, v in o.items()}
    if isinstance(o, list): return [conv(v) for v in o]
    return o

d = json.load(open(sys.argv[1]))
h = hashlib.sha256(); n = 0
for e in d['elements']:
    h.update(json.dumps(conv(e), separators=(',', ':')).encode()); h.update(b'\n'); n += 1
rest = {k: v for k, v in d.items() if k != 'elements'}
print(n, h.hexdigest(), json.dumps(rest, separators=(',', ':'), sort_keys=True))
