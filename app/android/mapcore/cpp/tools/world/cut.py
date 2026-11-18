#!/usr/bin/env python3
"""A cut of the published world for the world's test cases (ATLAS_WORLD_DIR), read by range as a phone reads it.

  python3 -I tools/world/cut.py <latest> <out folder>

<latest> is the folder of a release that holds world.json, world-latest's
(https://github.com/<owner>/<repo>/releases/download/world-latest), naming the world release a phone takes. The whole
world (12.3 GB in 61 parts for world-20261102-planet-3) is too much for each of CI's host jobs, and the cases draw a
few places only: the canal ring from 1 to 1,000 km, and the bend's and the poles' places (frame/world_vk_test.cpp).
So the cut takes world.idx and the globe whole, and of the packs only those whose box meets one of AREAS below, each
read by an HTTP range of its part, checked against its SHA-256, and laid end to end in a part of the same name, with a
world.idx of them (the release's header and date, its packs' entries in its order, each at its range in the cut's
part). A pack's bytes are the release's, byte for byte, so the store reads the cut as it reads the world; where the
cut has no pack, the world has no fine tile there, as over the open sea, and the cover is the coarser pack's or the
globe's. Writes <out>/cut.json: the release, the areas and the packs taken. About 1 GB for that world.

Python 3's standard library only. Fails (exit 1) when a file does not come after five tries, or is not as world.json
or world.idx says.
"""

import argparse
import hashlib
import json
import os
import struct
import sys
import time
import urllib.request

MAGIC = b"ATWX"
VERSION = 1
HEADER = 16  # magic, version, a reserved word and the data's date
ENTRY = struct.Struct("<BBBBIIQQI4i32s")  # tiles/WorldIndex.hpp: 80 bytes
FINE, COARSE = 6, 3  # a fine pack's block is an L6 tile (L9 to L11), a coarse one's an L3 tile (L6 to L8)

# What the world's cases view (frame/world_vk_test.cpp), each a box (south, west, north, east, degrees) and the
# blocks it takes. A view's tiles beyond the fine packs taken are the coarse pack's, and beyond those the globe's.
AREAS = [
    # the canal ring (52.37, 4.89) from 1 to 100 km, straight down and pitched: its L6 block and the three it lies
    # beside (it is near their corner), and the coarse block over them
    ("the canal ring, 1 to 100 km", (51.0, 3.5, 53.8, 6.5), (FINE, COARSE)),
    # the Netherlands from 1,000 km: the coarse blocks a view of about 1,000 km across meets
    ("the Netherlands, to 1,000 km", (43.0, -5.0, 61.0, 15.0), (COARSE,)),
    # the bend's sweep from 1 km at Tokyo, the open sea and 85 degrees south
    ("Tokyo, the bend", (35.4, 139.4, 36.0, 140.0), (FINE, COARSE)),
    ("the open sea, the bend", (34.5, -40.5, 35.5, -39.5), (FINE, COARSE)),
    ("85 degrees south, the bend and the pole", (-86.0, -5.0, -84.0, 25.0), (FINE, COARSE)),
    # the poles from 2,500 km at 20 degrees east (the globe's levels there; the blocks for a pitched view's nearer ground)
    ("85 degrees north, the pole", (84.0, 15.0, 86.0, 25.0), (FINE, COARSE)),
]

TRIES = 5
CHUNK = 1 << 20


def fetch(url, out=None, first=None, length=None):
    """The bytes of `url` (or its range [first, first + length)), into the file `out` when given: (bytes, sha256)."""
    headers = {"User-Agent": "atlas-ci-world-cut"}
    if first is not None:
        headers["Range"] = "bytes=%d-%d" % (first, first + length - 1)
    for attempt in range(1, TRIES + 1):
        try:
            request = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(request, timeout=120) as response:
                if first is not None and response.status != 206:
                    raise OSError("HTTP %d, not a range" % response.status)
                digest = hashlib.sha256()
                kept = bytearray()
                read = 0
                sink = open(out, "wb") if out is not None else None
                try:
                    while True:
                        chunk = response.read(CHUNK)
                        if not chunk:
                            break
                        digest.update(chunk)
                        read += len(chunk)
                        if sink is not None:
                            sink.write(chunk)
                        else:
                            kept += chunk
                finally:
                    if sink is not None:
                        sink.close()
                if length is not None and read != length:
                    raise OSError("%d bytes of %d" % (read, length))
                return bytes(kept), digest.digest()
        except OSError as e:  # urllib's errors, a short read, a broken connection
            print("%s: try %d of %d: %s" % (url, attempt, TRIES, e), file=sys.stderr)
            if attempt == TRIES:
                sys.exit("cut.py: %s did not come" % url)
            time.sleep(5 * attempt)
    raise AssertionError


def read_index(data):
    if data[:4] != MAGIC or struct.unpack_from("<H", data, 4)[0] != VERSION:
        sys.exit("cut.py: world.idx is no world index this reader knows")
    at = HEADER
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
        sys.exit("cut.py: world.idx holds %d packs in %d bytes" % (count, len(data) - at))
    return data[:HEADER], parts, [ENTRY.unpack_from(data, at + i * ENTRY.size) for i in range(count)]


def write_index(path, header, parts, entries):
    out = bytearray(header)
    out += struct.pack("<H", len(parts))
    for name, length in parts:
        raw = name.encode("utf-8")
        out += struct.pack("<B", len(raw)) + raw + struct.pack("<Q", length)
    out += struct.pack("<I", len(entries))
    for e in entries:
        out += ENTRY.pack(*e)
    with open(path, "wb") as f:
        f.write(out)


def meets(entry, box):
    south, west, north, east = (v / 1e7 for v in entry[9:13])
    s, w, n, e = box
    return not (north < s or south > n or east < w or west > e)


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("latest")
    p.add_argument("out")
    a = p.parse_args()
    latest = a.latest.rstrip("/") + "/"
    raw, _ = fetch(latest + "world.json")
    world = json.loads(raw)
    release, base = world["release"], world["url"]
    # the release's files beside world-latest's, on the same host and repository
    if not base.startswith(latest.rsplit("/", 2)[0] + "/"):
        sys.exit("cut.py: world.json's url %s is not a release beside %s" % (base, latest))
    os.makedirs(a.out, exist_ok=True)
    index_bytes, sha = fetch(base + world["index"]["name"])
    if sha.hex() != world["index"]["sha256"]:
        sys.exit("cut.py: %s's world.idx is not the one its world.json names" % release)
    header, parts, entries = read_index(index_bytes)

    taken = [e for e in entries if any(e[0] in levels and meets(e, box) for _, box, levels in AREAS)]
    cut_parts = []  # (name, bytes) in the release's order of parts
    number = {}  # the release's part -> the cut's
    cut_entries = []
    at = {}  # the cut's part -> its bytes so far
    started = time.monotonic()
    for z, first, last, part, x, y, offset, length, tiles, s, w, n, e, digest in taken:
        name = parts[part][0]
        if part not in number:
            number[part] = len(cut_parts)
            cut_parts.append([name, 0])
            open(os.path.join(a.out, name), "wb").close()
        k = number[part]
        target = os.path.join(a.out, "pack.tmp")
        _, got = fetch(base + name, target, offset, length)
        if got != digest:
            sys.exit("cut.py: pack %d/%d/%d of %s is not as world.idx says" % (z, x, y, name))
        with open(os.path.join(a.out, name), "ab") as out, open(target, "rb") as pack:
            while True:
                chunk = pack.read(CHUNK)
                if not chunk:
                    break
                out.write(chunk)
        os.remove(target)
        cut_entries.append((z, first, last, k, x, y, at.get(k, 0), length, tiles, s, w, n, e, digest))
        at[k] = at.get(k, 0) + length
        cut_parts[k][1] = at[k]
        print("pack %d/%d/%d (L%d to L%d): %.1f MB of %s" % (z, x, y, first, last, length / 1e6, name))
    if not taken:
        sys.exit("cut.py: no pack of %s meets the areas" % release)
    write_index(os.path.join(a.out, "world.idx"), header, [tuple(c) for c in cut_parts], cut_entries)

    globe = world["globe"]
    _, sha = fetch(base + globe["name"], os.path.join(a.out, globe["name"]))
    if sha.hex() != globe["sha256"]:
        sys.exit("cut.py: %s's globe is not the one its world.json names" % release)

    total = sum(length for _, length in cut_parts) + globe["bytes"]
    note = {
        "release": release,
        "url": base,
        "areas": [{"name": name, "box": list(box), "blocks": list(levels)} for name, box, levels in AREAS],
        "packs": ["world/%d/%d/%d" % (e[0], e[4], e[5]) for e in cut_entries],
        "bytes": total,
        "of": world.get("bytes"),
    }
    with open(os.path.join(a.out, "cut.json"), "w", encoding="utf-8") as f:
        json.dump(note, f, indent=1)
    print("cut of %s: %d packs of %d, %.0f MB of %.0f MB with the globe, in %.0f s" %
          (release, len(cut_entries), len(entries), total / 1e6, (world.get("bytes") or 0) / 1e6,
           time.monotonic() - started))


if __name__ == "__main__":
    main()
