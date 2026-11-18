#!/usr/bin/env python3
"""The relief's pictures as PNGs (ENGINE.md part 6, terrain): relief_vk_test's PPMs side by side.

  python3 -I tools/relief/montage.py <folder>

relief_vk_test's last case writes <view>-<sky>-flat.ppm, -relief.ppm and -relief-no-sky-view.ppm for each board's view
and sky into <folder>. Each is written again as a PNG beside it, and each view and sky's three as one PNG
(<view>-<sky>.png: flat, relief, relief without the sky-view, from the left, 8 px of white between). Python 3's standard
library only.
"""

import os
import re
import struct
import sys
import zlib

KINDS = ("flat", "relief", "relief-no-sky-view")
GAP = 8


def read_ppm(path):
    data = open(path, "rb").read()
    tokens = []
    at = 0
    while len(tokens) < 4:
        while data[at : at + 1].isspace():
            at += 1
        if data[at : at + 1] == b"#":
            at = data.index(b"\n", at) + 1
            continue
        end = at
        while not data[end : end + 1].isspace():
            end += 1
        tokens.append(data[at:end])
        at = end
    at += 1
    if tokens[0] != b"P6" or tokens[3] != b"255":
        sys.exit("%s: not an 8-bit P6 PPM" % path)
    w, h = int(tokens[1]), int(tokens[2])
    pixels = data[at : at + w * h * 3]
    if len(pixels) != w * h * 3:
        sys.exit("%s: cut short" % path)
    return w, h, pixels


def write_png(path, w, h, rgb):
    def chunk(kind, body):
        c = kind + body
        return struct.pack(">I", len(body)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    rows = b"".join(b"\x00" + rgb[y * w * 3 : (y + 1) * w * 3] for y in range(h))
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    folder = sys.argv[1]
    groups = {}
    for name in sorted(os.listdir(folder)):
        m = re.fullmatch(r"(.+)-(sunny|overcast)-(%s)\.ppm" % "|".join(re.escape(k) for k in KINDS), name)
        if not m:
            continue
        w, h, rgb = read_ppm(os.path.join(folder, name))
        write_png(os.path.join(folder, name[: -len(".ppm")] + ".png"), w, h, rgb)
        groups.setdefault((m.group(1), m.group(2)), {})[m.group(3)] = (w, h, rgb)
    for (view, sky), frames in sorted(groups.items()):
        shown = [frames[k] for k in KINDS if k in frames]
        h = max(f[1] for f in shown)
        w = sum(f[0] for f in shown) + GAP * (len(shown) - 1)
        out = bytearray(b"\xff" * (w * h * 3))
        x0 = 0
        for fw, fh, rgb in shown:
            for y in range(fh):
                out[(y * w + x0) * 3 : (y * w + x0 + fw) * 3] = rgb[y * fw * 3 : (y + 1) * fw * 3]
            x0 += fw + GAP
        write_png(os.path.join(folder, "%s-%s.png" % (view, sky)), w, h, bytes(out))
        print("%s-%s.png: %s" % (view, sky, ", ".join(k for k in KINDS if k in frames)))


if __name__ == "__main__":
    main()
