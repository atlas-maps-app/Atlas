#!/usr/bin/env python3
"""The terrain packs around some L9 tiles, cut out of a terrain run's files (ENGINE.md part 6, terrain).

  python3 -I tools/relief/cut.py <run folder> <out folder> <z/x/y>... [--around N]

A terrain run (terrain.yml) keeps a region's packs end to end in parts (<region>-terrain.000, ...) beside their index
(<region>-terrain.idx, the world index's format: tiles/WorldIndex.hpp). Each pack whose block is one of the tiles named,
or within N blocks of one (1 by default), is written whole to <out>/<region>-terrain.<z>-<x>-<y>.atlas after its
SHA-256 is checked against the index's. Prints what it wrote. Python 3's standard library only.
"""

import argparse
import hashlib
import os
import struct
import sys

MAGIC = b"ATWX"
ENTRY = struct.Struct("<BBBBIIQQI4i32s")


def read_index(path):
    data = open(path, "rb").read()
    if data[:4] != MAGIC:
        sys.exit("%s: not a world index" % path)
    at = 16
    (parts_count,) = struct.unpack_from("<H", data, at)
    at += 2
    parts = []
    for _ in range(parts_count):
        n = data[at]
        name = data[at + 1 : at + 1 + n].decode("utf-8")
        (length,) = struct.unpack_from("<Q", data, at + 1 + n)
        parts.append((name, length))
        at += 1 + n + 8
    (count,) = struct.unpack_from("<I", data, at)
    at += 4
    if len(data) - at != count * ENTRY.size:
        sys.exit("%s: %d packs in %d bytes" % (path, count, len(data) - at))
    entries = [ENTRY.unpack_from(data, at + i * ENTRY.size) for i in range(count)]
    return parts, entries


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("run")
    p.add_argument("out")
    p.add_argument("tiles", nargs="+")
    p.add_argument("--around", type=int, default=1)
    a = p.parse_args()
    wanted = []
    for t in a.tiles:
        z, x, y = (int(v) for v in t.split("/"))
        wanted.append((z, x, y))
    os.makedirs(a.out, exist_ok=True)
    written = 0
    for name in sorted(os.listdir(a.run)):
        if not name.endswith("-terrain.idx"):
            continue
        stem = name[: -len(".idx")]
        parts, entries = read_index(os.path.join(a.run, name))
        for z, _first, _last, part, x, y, offset, length, _tiles, *_box, sha in entries:
            if not any(z == wz and abs(x - wx) <= a.around and abs(y - wy) <= a.around for wz, wx, wy in wanted):
                continue
            with open(os.path.join(a.run, parts[part][0]), "rb") as f:
                f.seek(offset)
                pack = f.read(length)
            if len(pack) != length or hashlib.sha256(pack).digest() != sha:
                sys.exit("%s: pack %d/%d/%d is not as its index says" % (name, z, x, y))
            target = os.path.join(a.out, "%s.%d-%d-%d.atlas" % (stem, z, x, y))
            with open(target, "wb") as f:
                f.write(pack)
            print("%s: %d bytes" % (target, length))
            written += 1
    if written == 0:
        sys.exit("no pack of %s lies within %d of %s" % (a.run, a.around, " ".join(a.tiles)))


if __name__ == "__main__":
    main()
