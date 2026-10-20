#!/usr/bin/env python3
"""pillcheck.py: whether the chrome's frosted pills show frosted glass under their tint (plan-vector D5.1, K11b).

On the SurfaceView host the map's core draws the glass under each frosted shape (CP/passes/Frosted.cpp) and Compose
draws the tint over it. A pill whose glass is missing, or lags its tint, shows the map sharp through the tint. This
reads screen captures (adb's `screencap -p` PNGs, or raw `screencap` output) and the shapes the app logs
(`vk chrome <n> shapes: <left> <top> <right> <bottom> r <radius> blur <σ> sat <s> frosted|clear[ alpha <a>]; ...`, in
screen pixels; or the core's `vk frosted <n> shapes: ...`), and judges (a shape a fade holds under alpha 1 is left out):

  check IMAGE --shapes LOG [--expect frosted|plain]
      Along each frosted shape's edge, the luminance 1.5 px inside it against the luminance 1.5 px outside: under a
      plain tint the map's streets and buildings run on beneath the pill at the tint's opacity (a tenth to two fifths of
      their contrast under the app's tints of 60 % to 90 %); under frosted glass nothing of them does. Each side's
      variation along the edge (less its running mean) is regressed, the inside's on the outside's, and set against
      the map's own: the outside's against itself 3 px further out, the same distance apart. That share is about
      (1 - tint) for a plain tint and near 0 for glass; the pill's own icons, text and hairlines, which do not follow
      the map and stand out from the glass, are clipped to three times the inside's median variation first. A shape is
      FROSTED under 4 %, PLAIN over 6 %, INCONCLUSIVE between, or where the map along it varies too little, or too
      unlike itself 3 px on, to tell. Exit 0 when no conclusive shape contradicts --expect (frosted by default) and at
      least one was conclusive.
  compare IMAGE_A IMAGE_B --shapes LOG [--limit 2]
      The mean absolute difference of the two captures inside the frosted shapes (pixels the shape covers whole), over
      R, G and B: the core's frosting (SurfaceView host) against HWUI's (TextureView host). Exit 0 within the limit.
  film IMAGES... --shapes LOG [--min-share 0.95]
      A pill animation captured frame by frame: in each capture each frosted shape is found where its tint lies now
      (shifted along x by up to --search px from the logged box), and its glass judged on each straight side as check
      does: a glass that lags its pill leaves the map sharp in a strip just inside the pill's trailing edge. Exit 0 when
      the share of captures with every pill whole reaches --min-share.
  fade STILL FADED --shapes LOG
      VK bench's fade probe: the still map (STILL) and the same map under a layer of alpha 0.5 (FADED), over the map's
      surface (`surface <x> <y>` of the shapes' line, `vk window: surface changed <w> x <h>`) away from the pills. The
      faded luminance is fitted as a times the still one plus c: a near 1 says the SurfaceView ignored the layer's alpha
      (IGNORED), near 0.5 that it followed it (HONOURED, c saying towards what), between, PARTIAL. Prints the verdict
      with the API level of the probe's line; exit 0 when conclusive. A record for each API level, not a check.
  selftest
      Synthetic captures: frosted pills pass, plain-tint pills fail, a lagging glass tears, two frostings a level apart
      compare within 2, a frosting and a plain tint do not, a fade is told from none.

Standard library only (CI's Python and a Mac's), like perfsplit.py.
"""
import math
import re
import struct
import sys
import zlib

# ---- Images --------------------------------------------------------------------------------------------------------


class Image:
    """An RGBA image, 8 bits a channel, top row first."""

    def __init__(self, width, height, rgba):
        self.width = width
        self.height = height
        self.rgba = rgba

    def pixel(self, x, y):
        i = (y * self.width + x) * 4
        return self.rgba[i], self.rgba[i + 1], self.rgba[i + 2]

    def luma(self):
        """Rec. 709 luminance of the encoded values, one float a pixel."""
        p = self.rgba
        return [0.2126 * p[i] + 0.7152 * p[i + 1] + 0.0722 * p[i + 2] for i in range(0, len(p), 4)]


def _paeth(a, b, c):
    p = a + b - c
    pa = abs(p - a)
    pb = abs(p - b)
    pc = abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def read_png(data):
    """A PNG of 8-bit RGB or RGBA (what screencap -p writes), not interlaced."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos = 8
    idat = []
    width = height = colour = 0
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos : pos + 4])
        kind = data[pos + 4 : pos + 8]
        chunk = data[pos + 8 : pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, depth, colour, _, _, interlace = struct.unpack(">IIBBBBB", chunk)
            if depth != 8 or interlace != 0 or colour not in (2, 6):
                raise ValueError("only 8-bit RGB or RGBA, not interlaced")
        elif kind == b"IDAT":
            idat.append(chunk)
        elif kind == b"IEND":
            break
    bpp = 4 if colour == 6 else 3
    raw = zlib.decompress(b"".join(idat))
    stride = width * bpp
    out = bytearray(width * height * 4)
    prev = bytearray(stride)
    at = 0
    for y in range(height):
        kind = raw[at]
        line = bytearray(raw[at + 1 : at + 1 + stride])
        at += 1 + stride
        if kind == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 255
        elif kind == 2:
            line = bytearray((a + b) & 255 for a, b in zip(line, prev))
        elif kind == 3:
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 255
        elif kind == 4:
            for i in range(stride):
                if i >= bpp:
                    line[i] = (line[i] + _paeth(line[i - bpp], prev[i], prev[i - bpp])) & 255
                else:
                    line[i] = (line[i] + prev[i]) & 255
        prev = line
        row = y * width * 4
        if bpp == 4:
            out[row : row + width * 4] = line
        else:
            for x in range(width):
                out[row + x * 4 : row + x * 4 + 3] = line[x * 3 : x * 3 + 3]
                out[row + x * 4 + 3] = 255
    return Image(width, height, out)


def read_raw(data):
    """screencap's raw output: width, height, format (and, from Android 9, the data space) as 32-bit words, then RGBA."""
    width, height, fmt = struct.unpack("<III", data[:12])
    body = width * height * 4
    header = len(data) - body
    if fmt != 1 or header not in (12, 16):
        raise ValueError("not screencap's raw RGBA_8888 (format %d, %d header bytes)" % (fmt, header))
    return Image(width, height, bytearray(data[header:]))


def read_image(path):
    with open(path, "rb") as f:
        data = f.read()
    return read_png(data) if data[:8] == b"\x89PNG\r\n\x1a\n" else read_raw(data)


def write_png(path, image):
    """For looking at a capture or a test's picture."""
    rows = b"".join(b"\x00" + bytes(image.rgba[y * image.width * 4 : (y + 1) * image.width * 4]) for y in range(image.height))

    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    head = struct.pack(">IIBBBBB", image.width, image.height, 8, 6, 0, 0, 0)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", head) + chunk(b"IDAT", zlib.compress(rows, 6)) + chunk(b"IEND", b""))


# ---- Shapes --------------------------------------------------------------------------------------------------------

SHAPE = re.compile(
    r"(-?[\d.]+) (-?[\d.]+) (-?[\d.]+) (-?[\d.]+) r ([\d.]+) blur ([\d.]+) sat ([\d.]+)(?: (frosted|clear))?"
    r"(?: alpha ([\d.]+))?(?: block \d+)?"
)


def parse_shapes(text):
    """The shapes of the last `vk chrome` line in `text` (the app's, in screen pixels), else of the last `vk frosted`."""
    chrome = [line for line in text.splitlines() if "vk chrome " in line and " shapes:" in line]
    frosted = [line for line in text.splitlines() if "vk frosted " in line and " shapes:" in line]
    line = chrome[-1] if chrome else (frosted[-1] if frosted else "")
    body = line.split(" shapes:", 1)[1] if " shapes:" in line else ""
    shapes = []
    for part in body.split(";"):
        m = SHAPE.search(part)
        if not m:
            continue
        l, t, r, b, radius, blur, sat = (float(v) for v in m.groups()[:7])
        shapes.append(
            {"left": l, "top": t, "right": r, "bottom": b, "radius": radius, "blur": blur, "sat": sat,
             "frosted": m.group(8) != "clear", "alpha": float(m.group(9)) if m.group(9) else 1.0}
        )
    return shapes


def full_glass(shape):
    """Whether the shape is frosted at its full alpha (a shape a fade holds under 1 shows its glass faded)."""
    return shape["frosted"] and shape["alpha"] >= 0.99


def sdf(shape, x, y, dx=0.0):
    """The signed distance (px) from the pixel centred at (x, y) to the shape's rounded rectangle, shifted by dx."""
    hx = (shape["right"] - shape["left"]) / 2
    hy = (shape["bottom"] - shape["top"]) / 2
    r = max(0.0, min(shape["radius"], hx, hy))
    qx = abs(x - (shape["left"] + dx + hx)) - (hx - r)
    qy = abs(y - (shape["top"] + hy)) - (hy - r)
    outside = math.hypot(max(qx, 0.0), max(qy, 0.0))
    return outside + min(max(qx, qy), 0.0) - r


# ---- The judgements --------------------------------------------------------------------------------------------------

DEPTH = 1.5  # px inside and outside the edge the luminance is read at: past its antialiasing
FAR = 4.5  # px outside where the map is read again, for its own likeness 3 px apart
SPAN = 6  # contour samples either side of a sample in the running mean taken off each side's variation
CLIP = 3.0  # the inside's variation is clipped to this many times its median (and a level): the pill's own content
FROSTED_BELOW = 0.04
PLAIN_ABOVE = 0.06
QUIET = 1.5  # the outside's variation (levels, root mean square) under which the map is too plain to tell
UNLIKE = 0.15  # the map's likeness 3 px apart under which it is too busy to tell


def contour(shape, dx=0.0, side=None):
    """Points along the shape's edge, a pixel apart, with the outward normal: (x, y, nx, ny). A side (left, right) takes
    the part of the edge that faces it (its normal within 72° of it): its straight edge and its corners' arcs."""
    l, t, r, b = shape["left"] + dx, shape["top"], shape["right"] + dx, shape["bottom"]
    rad = max(0.0, min(shape["radius"], (r - l) / 2, (b - t) / 2))
    points = []

    def segment(x0, y0, x1, y1, nx, ny):
        n = max(1, int(math.hypot(x1 - x0, y1 - y0)))
        for i in range(n):
            u = (i + 0.5) / n
            points.append((x0 + (x1 - x0) * u, y0 + (y1 - y0) * u, nx, ny))

    def arc(cx, cy, a0, a1):
        n = max(1, int(rad * abs(a1 - a0)))
        for i in range(n):
            a = a0 + (a1 - a0) * (i + 0.5) / n
            points.append((cx + rad * math.cos(a), cy + rad * math.sin(a), math.cos(a), math.sin(a)))

    segment(l + rad, t, r - rad, t, 0.0, -1.0)
    arc(r - rad, t + rad, -math.pi / 2, 0.0)
    segment(r, t + rad, r, b - rad, 1.0, 0.0)
    arc(r - rad, b - rad, 0.0, math.pi / 2)
    segment(r - rad, b, l + rad, b, 0.0, 1.0)
    arc(l + rad, b - rad, math.pi / 2, math.pi)
    segment(l, b - rad, l, t + rad, -1.0, 0.0)
    arc(l + rad, t + rad, math.pi, 1.5 * math.pi)
    if side == "left":
        return [q for q in points if q[2] < -0.3]
    if side == "right":
        return [q for q in points if q[2] > 0.3]
    return points


def _at(luma, width, height, x, y):
    xi = int(math.floor(x))
    yi = int(math.floor(y))
    if xi < 0 or yi < 0 or xi >= width or yi >= height:
        return None
    return luma[yi * width + xi]


def _detrended(values):
    n = len(values)
    out = []
    for i in range(n):
        lo = max(0, i - SPAN)
        hi = min(n, i + SPAN + 1)
        out.append(values[i] - sum(values[lo:hi]) / (hi - lo))
    return out


def judge(image, luma, shape, dx=0.0, side=None):
    """(verdict, the share of the map's variation along the edge that recurs inside, the outside's variation) of one
    frosted shape: FROSTED, PLAIN or INCONCLUSIVE."""
    inside = []
    outside = []
    further = []
    for x, y, nx, ny in contour(shape, dx, side):
        a = _at(luma, image.width, image.height, x - DEPTH * nx, y - DEPTH * ny)
        b = _at(luma, image.width, image.height, x + DEPTH * nx, y + DEPTH * ny)
        c = _at(luma, image.width, image.height, x + FAR * nx, y + FAR * ny)
        if a is None or b is None or c is None:
            continue  # past the screen's edge
        inside.append(a)
        outside.append(b)
        further.append(c)
    if len(outside) < 4 * SPAN:
        return "INCONCLUSIVE", 0.0, 0.0
    hi = _detrended(inside)
    ho = _detrended(outside)
    hf = _detrended(further)
    energy = sum(v * v for v in ho)
    variation = math.sqrt(energy / len(ho))
    likeness = sum(a * b for a, b in zip(ho, hf)) / max(1e-9, sum(v * v for v in hf))
    if variation < QUIET or likeness < UNLIKE:
        return "INCONCLUSIVE", 0.0, variation
    clip = CLIP * sorted(abs(v) for v in hi)[len(hi) // 2] + 1.0
    share = sum(max(-clip, min(clip, a)) * b for a, b in zip(hi, ho)) / energy / likeness
    if share < FROSTED_BELOW:
        return "FROSTED", share, variation
    if share > PLAIN_ABOVE:
        return "PLAIN", share, variation
    return "INCONCLUSIVE", share, variation


def check(image, shapes, expect="frosted", out=sys.stdout):
    luma = image.luma()
    conclusive = 0
    wrong = 0
    for n, shape in enumerate(shapes):
        if not full_glass(shape):
            continue
        verdict, share, variation = judge(image, luma, shape)
        print("pill %d (%.0f %.0f %.0f %.0f): %s, %.1f %% of the map's variation along its edge (%.1f levels) recurs "
              "inside" % (n, shape["left"], shape["top"], shape["right"], shape["bottom"], verdict, 100 * share,
                          variation), file=out)
        if verdict == "INCONCLUSIVE":
            continue
        conclusive += 1
        if verdict != expect.upper():
            wrong += 1
    ok = conclusive > 0 and wrong == 0
    print("pill check: %s (%d of the frosted shapes conclusive, %d %s)" % (
        "PASS" if ok else "FAIL", conclusive, wrong, "not " + expect if wrong else "against"), file=out)
    return ok


def compare(a, b, shapes, limit=2.0, out=sys.stdout):
    if (a.width, a.height) != (b.width, b.height):
        print("compare: FAIL (the captures differ in size: %dx%d, %dx%d)" % (a.width, a.height, b.width, b.height), file=out)
        return False
    total = 0.0
    count = 0
    for n, shape in enumerate(shapes):
        if not full_glass(shape):
            continue
        s_total = 0.0
        s_count = 0
        for y in range(max(0, int(shape["top"])), min(a.height, int(math.ceil(shape["bottom"])))):
            for x in range(max(0, int(shape["left"])), min(a.width, int(math.ceil(shape["right"])))):
                if sdf(shape, x + 0.5, y + 0.5) > -0.5:
                    continue
                i = (y * a.width + x) * 4
                s_total += abs(a.rgba[i] - b.rgba[i]) + abs(a.rgba[i + 1] - b.rgba[i + 1]) + abs(a.rgba[i + 2] - b.rgba[i + 2])
                s_count += 3
        if s_count:
            print("pill %d: MAD %.3f over %d pixels" % (n, s_total / s_count, s_count // 3), file=out)
        total += s_total
        count += s_count
    mad = total / count if count else float("nan")
    ok = count > 0 and mad <= limit
    print("compare: %s (MAD %.3f inside the frosted pills, limit %.1f)" % ("PASS" if ok else "FAIL", mad, limit), file=out)
    return ok


def locate(image, luma, shape, search):
    """Where the shape's tint lies now along x: the shift (px, within search) at which a band just inside its ends is
    most even (the tint over glass, or over the map at the tint's opacity, varies far less than the map beside it)."""
    band = []
    for x, y, nx, ny in contour(shape):
        if abs(nx) < 0.3:
            continue  # the top and bottom edges say nothing of a shift along x
        for depth in (1.0, 2.0, 3.0, 4.0):
            band.append((x - depth * nx, y - depth * ny))
    best = 0.0
    best_spread = float("inf")
    w = image.width
    for dx in range(-int(search), int(search) + 1):
        values = []
        for x, y in band:
            v = _at(luma, w, image.height, x + dx, y)
            if v is not None:
                values.append(v)
        if len(values) < len(band) // 2:
            continue
        mean = sum(values) / len(values)
        spread = sum((v - mean) ** 2 for v in values) / len(values)
        if spread < best_spread:
            best_spread = spread
            best = float(dx)
    return best


def film(images, shapes, search=110.0, min_share=0.95, out=sys.stdout):
    whole = 0
    judged = 0
    for k, image in enumerate(images):
        luma = image.luma()
        verdicts = []
        torn = False
        conclusive = False
        for n, shape in enumerate(shapes):
            if not full_glass(shape):
                continue
            dx = locate(image, luma, shape, search)
            for side in ("left", "right"):
                verdict, _, _ = judge(image, luma, shape, dx, side)
                verdicts.append("%d%s %s" % (n, side[0], verdict.lower()))
                if verdict == "PLAIN":
                    torn = True
                if verdict != "INCONCLUSIVE":
                    conclusive = True
        if not conclusive:
            print("capture %d: inconclusive (%s)" % (k, ", ".join(verdicts)), file=out)
            continue
        judged += 1
        whole += 0 if torn else 1
        print("capture %d: %s (%s)" % (k, "torn" if torn else "whole", ", ".join(verdicts)), file=out)
    share = whole / judged if judged else float("nan")
    ok = judged > 0 and share >= min_share
    print("film: %s (%d of %d captures with every pill whole, %.1f %%; at least %.0f %% asked)" % (
        "PASS" if ok else "FAIL", whole, judged, 100 * share if judged else 0, 100 * min_share), file=out)
    return ok


FADE_KEEP_OFF = 12.0  # px around each shape left out of the fade's fit: the pills' tint and glass fade too
FADE_STEP = 2  # every second pixel of every second row: enough for the fit, a quarter of the time


def surface_rect(text, width, height):
    """The map surface's box in screen pixels from the log: `surface <x> <y>` of the last shapes line and the size of
    the last `vk window: surface changed <w> x <h>` (else the capture's size), cut to the capture."""
    x = y = 0.0
    for line in text.splitlines():
        m = re.search(r" shapes:.*; surface (-?[\d.]+) (-?[\d.]+)", line)
        if m:
            x, y = float(m.group(1)), float(m.group(2))
    w, h = width, height
    for line in text.splitlines():
        m = re.search(r"vk window: surface changed (\d+) x (\d+)", line)
        if m:
            w, h = int(m.group(1)), int(m.group(2))
    x0, y0 = max(0, int(math.ceil(x))), max(0, int(math.ceil(y)))
    return x0, y0, min(width, int(x + w)), min(height, int(y + h))


def fade(still, faded, shapes, rect, api="?", out=sys.stdout):
    """Fits the faded capture's luminance as a times the still one plus c over `rect` away from the shapes, and says
    whether the SurfaceView followed the layer's alpha 0.5: (verdict, a, c, r2)."""
    if (still.width, still.height) != (faded.width, faded.height):
        print("fade: INCONCLUSIVE (the captures differ in size)", file=out)
        return "INCONCLUSIVE", float("nan"), float("nan"), float("nan")
    ls, lf = still.luma(), faded.luma()
    x0, y0, x1, y1 = rect
    n = sx = sf = sxx = sff = sxf = 0.0
    for y in range(y0, y1, FADE_STEP):
        for x in range(x0, x1, FADE_STEP):
            if any(sdf(shape, x + 0.5, y + 0.5) < FADE_KEEP_OFF for shape in shapes):
                continue
            a, b = ls[y * still.width + x], lf[y * still.width + x]
            n += 1
            sx += a
            sf += b
            sxx += a * a
            sff += b * b
            sxf += a * b
    if n < 1000:
        print("fade: INCONCLUSIVE (%d pixels of the map away from the pills)" % n, file=out)
        return "INCONCLUSIVE", float("nan"), float("nan"), float("nan")
    var_s = sxx / n - (sx / n) ** 2
    var_f = sff / n - (sf / n) ** 2
    cov = sxf / n - (sx / n) * (sf / n)
    if var_s < 25.0:
        print("fade: INCONCLUSIVE (the still map varies by %.1f levels: too even to fit)" % math.sqrt(max(var_s, 0.0)), file=out)
        return "INCONCLUSIVE", float("nan"), float("nan"), float("nan")
    a = cov / var_s
    c = sf / n - a * sx / n
    r2 = cov * cov / (var_s * var_f) if var_f > 0 else 1.0
    if a >= 0.9:
        verdict, said = "IGNORED", "the SurfaceView drew at its full alpha under the layer"
    elif a <= 0.65:
        towards = "black" if c < 40 else ("white" if c > 90 else "a grey of %.0f" % (c / max(1e-6, 1 - a)))
        verdict, said = "HONOURED", "the map faded with the layer, towards %s" % towards
    else:
        verdict, said = "PARTIAL", "the map faded by less than the layer"
    print("fade: API %s: %s (%s: the map drew at %.2f of itself + %.1f levels, r2 %.3f, over %d pixels)" % (
        api, verdict, said, a, c, r2, n), file=out)
    return verdict, a, c, r2


# ---- The self-test ---------------------------------------------------------------------------------------------------


class _Random:
    """A small deterministic generator (the test's pictures are the same on every machine)."""

    def __init__(self, seed):
        self.state = seed & 0xFFFFFFFF

    def next(self):
        self.state = (1103515245 * self.state + 12345) & 0x7FFFFFFF
        return self.state

    def below(self, n):
        return self.next() % n


def _map(width, height, seed=7):
    """A busy picture with sharp edges, like a city's map: blocks of colour, streets between them."""
    rnd = _Random(seed)
    rgba = bytearray(width * height * 4)
    for i in range(0, len(rgba), 4):
        rgba[i : i + 4] = b"\xe8\xe2\xd6\xff"
    for _ in range(width * height // 300):
        w = 4 + rnd.below(26)
        h = 4 + rnd.below(26)
        x = rnd.below(width)
        y = rnd.below(height)
        c = (90 + rnd.below(150), 90 + rnd.below(150), 90 + rnd.below(150))
        for yy in range(y, min(height, y + h)):
            for xx in range(x, min(width, x + w)):
                i = (yy * width + xx) * 4
                rgba[i : i + 3] = bytes(c)
    return Image(width, height, rgba)


def _blurred(image, radius, passes=3):
    """Box blurs along x then y, the edges repeated, each a sliding sum: the test's frosted glass."""
    w, h = image.width, image.height
    n = 2 * radius + 1
    src = [[float(v) for v in image.rgba[i::4]] for i in range(3)]
    for c in range(3):
        plane = src[c]
        for _ in range(passes):
            out = [0.0] * (w * h)
            for y in range(h):
                row = y * w
                s = sum(plane[row + min(w - 1, max(0, k))] for k in range(-radius, radius + 1))
                for x in range(w):
                    out[row + x] = s / n
                    s += plane[row + min(w - 1, x + radius + 1)] - plane[row + max(0, x - radius)]
            plane = out
            out = [0.0] * (w * h)
            for x in range(w):
                s = sum(plane[min(h - 1, max(0, k)) * w + x] for k in range(-radius, radius + 1))
                for y in range(h):
                    out[y * w + x] = s / n
                    s += plane[min(h - 1, y + radius + 1) * w + x] - plane[max(0, y - radius) * w + x]
            plane = out
        src[c] = plane
    rgba = bytearray(image.rgba)
    for c in range(3):
        for i, v in enumerate(src[c]):
            rgba[i * 4 + c] = max(0, min(255, int(round(v))))
    return Image(w, h, rgba)


def _with_pills(base, glass, shapes, tint=0.8, glass_dx=0):
    """`base` with each pill: `glass` (shifted by glass_dx) inside it, then a white tint of `tint` over the pill."""
    w, h = base.width, base.height
    rgba = bytearray(base.rgba)
    for shape in shapes:
        x0 = max(0, int(min(shape["left"], shape["left"] + glass_dx)) - 1)
        x1 = min(w, int(max(shape["right"], shape["right"] + glass_dx)) + 2)
        for y in range(max(0, int(shape["top"]) - 1), min(h, int(shape["bottom"]) + 2)):
            for x in range(x0, x1):
                i = (y * w + x) * 4
                if glass is not None and sdf(shape, x + 0.5, y + 0.5, glass_dx) <= -0.5:
                    rgba[i : i + 3] = glass.rgba[i : i + 3]
                if sdf(shape, x + 0.5, y + 0.5) <= -0.5:
                    for c in range(3):
                        rgba[i + c] = int(round(255 * tint + rgba[i + c] * (1 - tint)))
    return Image(w, h, rgba)


def selftest():
    quiet = open("/dev/null", "w")
    w, h = 360, 240
    base = _map(w, h)
    glass = _blurred(base, 6)
    shapes = parse_shapes(
        "AtlasMap: vk chrome 2 shapes: 20.0 20.0 340.0 70.0 r 25.0 blur 15.0 sat 1.60 frosted; "
        "40.0 150.0 220.0 200.0 r 14.0 blur 15.0 sat 1.00 frosted; surface 0.0 0.0; core draws the glass"
    )
    assert len(shapes) == 2 and shapes[0]["radius"] == 25.0 and shapes[1]["frosted"], shapes
    for tint in (0.6, 0.9):
        frosted = _with_pills(base, glass, shapes, tint=tint)
        plain = _with_pills(base, None, shapes, tint=tint)
        assert check(frosted, shapes, out=quiet), "frosted pills must pass (tint %.1f)" % tint
        assert not check(plain, shapes, out=quiet), "plain-tint pills must fail (tint %.1f)" % tint
        assert check(plain, shapes, expect="plain", out=quiet), "plain-tint pills are plain (tint %.1f)" % tint
    frosted = _with_pills(base, glass, shapes)
    plain = _with_pills(base, None, shapes)
    # Two frostings a little apart (a σ apart, the tint a little lighter) compare within 2; a plain tint does not.
    other = _with_pills(base, _blurred(base, 7), shapes, tint=0.805)
    assert compare(frosted, other, shapes, out=quiet), "two frostings must compare within 2"
    assert not compare(frosted, plain, shapes, limit=2.0, out=quiet), "a frosting and a plain tint must not"
    # A film: whole pills, and pills whose glass lags them by 6 px.
    moved = [dict(s, left=s["left"] + 6, right=s["right"] + 6) for s in shapes]
    lagging = _with_pills(base, glass, moved, glass_dx=-6)
    whole = _with_pills(base, glass, moved)
    assert film([frosted, whole], shapes, search=12, min_share=1.0, out=quiet), "whole pills must pass the film"
    assert not film([frosted, lagging], shapes, search=12, min_share=0.95, out=quiet), "a lagging glass must tear"
    # A faded shape is left out of the judgements.
    faded_shapes = parse_shapes(
        "AtlasMap: vk chrome 2 shapes: 20.0 20.0 340.0 70.0 r 25.0 blur 15.0 sat 1.60 frosted alpha 0.50; "
        "40.0 150.0 220.0 200.0 r 14.0 blur 15.0 sat 1.00 frosted; surface 0.0 0.0; core draws the glass"
    )
    assert faded_shapes[0]["alpha"] == 0.5 and not full_glass(faded_shapes[0]) and full_glass(faded_shapes[1])
    # The fade probe: the map under the layer drawn at half (towards white, as a punched hole half cleared would) is
    # HONOURED; drawn as it was, IGNORED.
    log = ("AtlasMap: vk window: surface changed 360 x 240\n"
           "AtlasMap: vk chrome 2 shapes: 20.0 20.0 340.0 70.0 r 25.0 blur 15.0 sat 1.60 frosted; "
           "40.0 150.0 220.0 200.0 r 14.0 blur 15.0 sat 1.00 frosted; surface 0.0 0.0; core draws the glass")
    rect = surface_rect(log, w, h)
    assert rect == (0, 0, 360, 240), rect
    halved = Image(w, h, bytearray(v if i % 4 == 3 else (v + 255) // 2 for i, v in enumerate(frosted.rgba)))
    verdict, a, c, _ = fade(frosted, halved, shapes, rect, out=quiet)
    assert verdict == "HONOURED" and abs(a - 0.5) < 0.02 and abs(c - 127.5) < 3, (verdict, a, c)
    assert fade(frosted, frosted, shapes, rect, out=quiet)[0] == "IGNORED"
    # The PNG reader reads what the writer wrote; the raw reader screencap's output.
    import os
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        p = os.path.join(d, "f.png")
        write_png(p, frosted)
        again = read_image(p)
        assert again.rgba == frosted.rgba
        raw = struct.pack("<IIII", w, h, 1, 0) + bytes(frosted.rgba)
        r = os.path.join(d, "f.raw")
        with open(r, "wb") as f:
            f.write(raw)
        assert read_image(r).rgba == frosted.rgba
    _filters_roundtrip()
    print("pillcheck selftest: ok")
    return True


def _filters_roundtrip():
    """Each PNG row filter (sub, up, average, Paeth) decoded as the encoder meant."""
    w, h = 7, 5
    rnd = _Random(3)
    pixels = bytearray(rnd.below(256) for _ in range(w * h * 4))
    stride = w * 4
    rows = []
    prev = bytearray(stride)
    for y in range(h):
        line = pixels[y * stride : (y + 1) * stride]
        kind = y % 5
        enc = bytearray(stride)
        for i in range(stride):
            a = line[i - 4] if i >= 4 else 0
            b = prev[i]
            c = prev[i - 4] if i >= 4 else 0
            pred = (0, a, b, (a + b) >> 1, _paeth(a, b, c))[kind]
            enc[i] = (line[i] - pred) & 255
        rows.append(bytes([kind]) + bytes(enc))
        prev = line

    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) +
           chunk(b"IDAT", zlib.compress(b"".join(rows))) + chunk(b"IEND", b""))
    assert read_png(png).rgba == pixels


# ---- The command line ------------------------------------------------------------------------------------------------


def _option(args, name, default=None):
    if name in args:
        i = args.index(name)
        value = args[i + 1]
        del args[i : i + 2]
        return value
    return default


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 2
    command, args = argv[0], argv[1:]
    if command == "selftest":
        return 0 if selftest() else 1
    log = _option(args, "--shapes")
    if log is None:
        print("pillcheck: --shapes LOG is needed")
        return 2
    with open(log, encoding="utf-8", errors="replace") as f:
        shapes = parse_shapes(f.read())
    if not [s for s in shapes if s["frosted"]]:
        print("pillcheck: no frosted shape in %s" % log)
        return 1
    if command == "check":
        expect = _option(args, "--expect", "frosted")
        return 0 if check(read_image(args[0]), shapes, expect) else 1
    if command == "compare":
        limit = float(_option(args, "--limit", "2"))
        return 0 if compare(read_image(args[0]), read_image(args[1]), shapes, limit) else 1
    if command == "fade":
        with open(log, encoding="utf-8", errors="replace") as f:
            text = f.read()
        still, faded = read_image(args[0]), read_image(args[1])
        m = re.findall(r"vkbench fade: .*\(API (\d+)\)", text)
        verdict = fade(still, faded, shapes, surface_rect(text, still.width, still.height), m[-1] if m else "?")[0]
        return 0 if verdict != "INCONCLUSIVE" else 1
    if command == "film":
        share = float(_option(args, "--min-share", "0.95"))
        search = float(_option(args, "--search", "110"))
        return 0 if film([read_image(p) for p in args], shapes, search, share) else 1
    print("pillcheck: unknown command %s" % command)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
