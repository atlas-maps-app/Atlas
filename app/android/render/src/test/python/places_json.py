#!/usr/bin/env python3
"""Writes data/regions/places.py's PLACES table as JSON (tuples become arrays) to stdout.
usage: places_json.py <repo>"""
import json, os, runpy, sys
print(json.dumps(runpy.run_path(os.path.join(sys.argv[1], 'data', 'regions', 'places.py'))['PLACES']))
