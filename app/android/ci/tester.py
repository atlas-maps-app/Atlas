#!/usr/bin/env python3
"""tester.py: a tester's flow, run on an installed alpha on the running emulator, through adb alone.

  python3 -I app/android/ci/tester.py check <flow file>
  python3 -I app/android/ci/tester.py run <flow file> --apk <apk> --out <dir> [--tag <alpha>] [--label <name>]

A flow is lines a tester writes without reading code: tester-flows.md has every line, tester-example.flow one flow.
The alphas are not debuggable (no run-as, no instrumentation), so the runner uses only what works on any installed
app: am and pm, input, uiautomator's dump of the screen (the texts an app shows to accessibility), screencap,
settings, svc, sendevent for two fingers, and the emulator's console (adb emu) for the location and the network's speed.

`run` installs the APK as a user's phone would (no permission granted), then runs the flow's steps in order. Each step
keeps a screenshot (shots/NN-<name>.png), when it started, how long its action took and whether it did what it said;
steps.tsv has them all, summary.md the same as a table for the job's summary. A step that fails is recorded with its
screenshot (and, for a text it did not find, the screen's texts in shots/NN-<name>.ui.xml) and the run goes on, unless
its line ends "or stop". A crash or an ANR during a step marks that step. The device's log (logcat.txt), the crash
buffer (crash.txt) and the ANR traces (anr/) are kept beside them, with the device's facts (device.txt) and the runner's
own log (run.log).

Exit status: 0 when the flow ran, whatever its steps did; 2 when the flow does not parse; 3 when the harness failed (no
device, no APK, an install that failed, the device gone for good during the run).
Environment: ATLAS_ADB (the adb to run, default adb), ATLAS_TESTER_POLL (seconds between looks at the screen while a step
waits for a text, default 0.5), ATLAS_TESTER_LOOK (seconds a tap or long press on a text looks for it, default 5),
ATLAS_TESTER_SCALE (scales the pauses the runner makes on its own, such as the settle after each step; default 1, the
tests set 0).

Python 3's standard library only.
"""

import argparse
import math
import os
import re
import subprocess
import sys
import time
import unicodedata
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

PKG = "app.atlas"
ACTIVITY = f"{PKG}/.MainActivity"

# Named places for `location`: central Amsterdam is the Westerkerk on the canal ring, where the project's own tests and
# CI's bench map stand.
PLACES = {"amsterdam": (52.37452, 4.88377)}

PERMISSIONS = {
    "location": ["android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"],
    "notifications": ["android.permission.POST_NOTIFICATIONS"],
    "microphone": ["android.permission.RECORD_AUDIO"],
}

KEYS = {
    "enter": "KEYCODE_ENTER", "back": "KEYCODE_BACK", "home": "KEYCODE_HOME", "tab": "KEYCODE_TAB",
    "delete": "KEYCODE_DEL", "backspace": "KEYCODE_DEL", "search": "KEYCODE_SEARCH", "menu": "KEYCODE_MENU",
    "recents": "KEYCODE_APP_SWITCH", "escape": "KEYCODE_ESCAPE", "space": "KEYCODE_SPACE",
    "up": "KEYCODE_DPAD_UP", "down": "KEYCODE_DPAD_DOWN", "left": "KEYCODE_DPAD_LEFT", "right": "KEYCODE_DPAD_RIGHT",
}

# The emulator console's network speeds; its delays have presets for three of them, the rest take umts's.
SPEEDS = ["gsm", "hscsd", "gprs", "edge", "umts", "hsdpa", "lte", "evdo"]
DELAYS = {"gsm": "gprs", "hscsd": "gprs", "gprs": "gprs", "edge": "edge"}

ROTATIONS = {"portrait": 0, "landscape": 1}

DEFAULT_WAIT = 30.0     # a wait for a text that names no limit
DEFAULT_SETTLE = 1.0    # the pause after each step's action, before its screenshot
LONGPRESS_MS = 1000
SWIPE_MS = 300


def env_float(name, default):
    try:
        return float(os.environ.get(name, default))
    except ValueError:
        return default


SCALE = env_float("ATLAS_TESTER_SCALE", 1.0)
POLL = env_float("ATLAS_TESTER_POLL", 0.5)
LOOK = env_float("ATLAS_TESTER_LOOK", 5.0)


# --- the flow --------------------------------------------------------------------------------------------------------

class FlowError(Exception):
    def __init__(self, line, message):
        super().__init__(f"line {line}: {message}")
        self.line = line


class Quoted(str):
    """A token written in quotes: a text, kept as written."""


OPEN_QUOTES = '"“'
CLOSE_QUOTES = {'"': '"', "“": "”"}


def pieces(raw, line):
    """The commands on one line of a flow: split at `;` and cut at `#`, both outside quotes."""
    out, buf, quote, i = [], [], None, 0
    while i < len(raw):
        c = raw[i]
        if quote:
            buf.append(c)
            if c == "\\" and i + 1 < len(raw):
                buf.append(raw[i + 1]); i += 2; continue
            if c == quote:
                quote = None
        elif c in OPEN_QUOTES:
            quote = CLOSE_QUOTES[c]; buf.append(c)
        elif c == ";":
            out.append("".join(buf)); buf = []
        elif c == "#" and (not buf or buf[-1].isspace() or not "".join(buf).strip()):
            break
        else:
            buf.append(c)
        i += 1
    if quote:
        raise FlowError(line, "a text without its closing quote")
    out.append("".join(buf))
    return [p.strip() for p in out if p.strip()]


def tokens(text, line):
    out, i = [], 0
    while i < len(text):
        c = text[i]
        if c.isspace():
            i += 1
        elif c in OPEN_QUOTES:
            close, j, buf = CLOSE_QUOTES[c], i + 1, []
            while j < len(text) and text[j] != close:
                if text[j] == "\\" and j + 1 < len(text):
                    buf.append(text[j + 1]); j += 2; continue
                buf.append(text[j]); j += 1
            if j >= len(text):
                raise FlowError(line, "a text without its closing quote")
            out.append(Quoted("".join(buf))); i = j + 1
        else:
            j = i
            while j < len(text) and not text[j].isspace() and text[j] not in OPEN_QUOTES:
                j += 1
            out.append(text[i:j]); i = j
    return out


@dataclass
class Step:
    n: int          # the step's number, from 1
    line: int       # its line in the flow
    text: str       # the command as written
    verb: str
    args: dict = field(default_factory=dict)
    stop: bool = False  # "or stop": the run ends here when this step fails


DURATION = re.compile(r"^(\d+(?:\.\d+)?)(ms|s|sec|m|min)?$", re.I)
COORD = re.compile(r"^\d+(?:\.\d+)?%?$")


def duration(tok, line, what="a time"):
    m = DURATION.match(tok) if not isinstance(tok, Quoted) else None
    if not m:
        raise FlowError(line, f"{what} such as 5s, 500ms or 2m, not {tok!r}")
    value, unit = float(m.group(1)), (m.group(2) or "s").lower()
    return value / 1000 if unit == "ms" else value * 60 if unit in ("m", "min") else value


def is_duration(tok):
    return not isinstance(tok, Quoted) and bool(DURATION.match(tok)) and not tok.isdigit()


def is_coord(tok):
    return not isinstance(tok, Quoted) and bool(COORD.match(tok))


def target(toks, i, line, what):
    """A place on the screen at toks[i]: "a text" [n], center, or x y (pixels, or % of the screen).
    Returns (target, index after it)."""
    if i >= len(toks):
        raise FlowError(line, f"{what} needs a place: a \"text\", center, or x y")
    tok = toks[i]
    if isinstance(tok, Quoted):
        if not tok.strip():
            raise FlowError(line, "an empty text")
        nth = 1
        if i + 1 < len(toks) and not isinstance(toks[i + 1], Quoted) and toks[i + 1].isdigit():
            nth = int(toks[i + 1]); i += 1
            if nth < 1:
                raise FlowError(line, "the n-th text counts from 1")
        return ("text", str(tok), nth), i + 1
    if tok.lower() in ("center", "centre", "middle"):
        return ("point", "50%", "50%"), i + 1
    if is_coord(tok) and i + 1 < len(toks) and is_coord(toks[i + 1]):
        return ("point", tok, toks[i + 1]), i + 2
    raise FlowError(line, f"{what} needs a place: a \"text\", center, or x y (such as 540 1200 or 50% 80%), not {tok!r}")


def done(toks, i, line, verb):
    if i < len(toks):
        raise FlowError(line, f"{verb}: nothing more was expected, not {' '.join(toks[i:])!r}")


def keyword(tok):
    return "" if isinstance(tok, Quoted) else tok.lower()


def parse_command(toks, line):
    """(verb, args) for one command's tokens."""
    verb, rest = keyword(toks[0]), toks[1:]
    if verb in ("launch", "open", "close", "fresh", "background"):
        done(rest, 0, line, verb)
        return ("launch" if verb == "open" else verb), {}
    if verb == "back":
        times = 1
        if rest:
            if isinstance(rest[0], Quoted) or not rest[0].isdigit() or int(rest[0]) < 1:
                raise FlowError(line, f"back takes how many times, such as back 2, not {rest[0]!r}")
            times = int(rest[0]); done(rest, 1, line, verb)
        return "back", {"times": times}
    if verb in ("grant", "revoke"):
        if not rest:
            raise FlowError(line, f"{verb} needs what: {', '.join(PERMISSIONS)}")
        for tok in rest:
            if keyword(tok) not in PERMISSIONS:
                raise FlowError(line, f"{verb} knows {', '.join(PERMISSIONS)}, not {tok!r}")
        return verb, {"what": [keyword(t) for t in rest]}
    if verb in ("tap", "longpress"):
        where, i = target(rest, 0, line, verb)
        args = {"target": where}
        if verb == "longpress":
            args["ms"] = LONGPRESS_MS
            if i < len(rest):
                args["ms"] = int(duration(rest[i], line, "how long to press") * 1000); i += 1
        done(rest, i, line, verb)
        return verb, args
    if verb == "type":
        if len(rest) != 1 or not isinstance(rest[0], Quoted):
            raise FlowError(line, 'type takes one text in quotes, such as type "cafe"')
        text = str(rest[0])
        bad = [c for c in text if not (32 <= ord(c) < 127)]
        if bad:
            raise FlowError(line, f"type can only type plain ASCII (adb's input text cannot type {bad[0]!r}); "
                                  "Atlas's search folds accents, so type cafe for café")
        if not text:
            raise FlowError(line, "type needs a text")
        return "type", {"text": text}
    if verb == "key":
        if len(rest) != 1:
            raise FlowError(line, f"key takes one key: {', '.join(sorted(KEYS))}")
        k = keyword(rest[0])
        if k in KEYS:
            return "key", {"code": KEYS[k]}
        if re.fullmatch(r"keycode_[a-z0-9_]+", k) or k.isdigit():
            return "key", {"code": k.upper()}
        raise FlowError(line, f"key knows {', '.join(sorted(KEYS))} (or KEYCODE_...), not {rest[0]!r}")
    if verb == "swipe":
        args, i = {"ms": SWIPE_MS}, 0
        if rest and keyword(rest[0]) in ("up", "down", "left", "right"):
            args["dir"] = keyword(rest[0]); i = 1
            if i < len(rest) and keyword(rest[i]) == "from":
                i += 1
            if i < len(rest) and not is_duration(rest[i]):
                args["from"], i = target(rest, i, line, "swipe from")
        elif len(rest) >= 4 and all(is_coord(t) for t in rest[:4]):
            args["points"] = list(rest[:4]); i = 4
        else:
            raise FlowError(line, "swipe takes up, down, left or right [from a place], or x1 y1 x2 y2")
        if i < len(rest):
            args["ms"] = int(duration(rest[i], line, "how long the swipe takes") * 1000); i += 1
        done(rest, i, line, verb)
        return "swipe", args
    if verb == "zoom":
        if not rest or keyword(rest[0]) not in ("in", "out"):
            raise FlowError(line, "zoom takes in or out [at a place]")
        args, i = {"dir": keyword(rest[0])}, 1
        if i < len(rest) and keyword(rest[i]) == "at":
            i += 1
        if i < len(rest):
            args["at"], i = target(rest, i, line, "zoom at")
        done(rest, i, line, verb)
        return "zoom", args
    if verb == "turn":
        if not rest or not re.fullmatch(r"-?\d+(\.\d+)?", keyword(rest[0])):
            raise FlowError(line, "turn takes the degrees to turn the map with two fingers, such as turn 45 or turn -90")
        args, i = {"degrees": float(rest[0])}, 1
        if i < len(rest) and keyword(rest[i]) == "at":
            i += 1
        if i < len(rest):
            args["at"], i = target(rest, i, line, "turn at")
        done(rest, i, line, verb)
        return "turn", args
    if verb == "rotate":
        if len(rest) != 1 or keyword(rest[0]) not in ROTATIONS:
            raise FlowError(line, "rotate takes landscape or portrait")
        return "rotate", {"rotation": ROTATIONS[keyword(rest[0])]}
    if verb in ("wait", "expect"):
        args, i = {}, 0
        if verb == "wait" and i < len(rest) and keyword(rest[i]) == "for":
            i += 1
        if i < len(rest) and keyword(rest[i]) in ("gone", "no", "not"):
            args["gone"] = True; i += 1
        if i < len(rest) and isinstance(rest[i], Quoted):
            if not rest[i].strip():
                raise FlowError(line, "an empty text")
            args["text"] = str(rest[i]); i += 1
            if verb == "wait":
                args["limit"] = DEFAULT_WAIT
                if i < len(rest) and keyword(rest[i]) == "up" and i + 1 < len(rest) and keyword(rest[i + 1]) == "to":
                    i += 2
                if i < len(rest):
                    args["limit"] = duration(rest[i], line, "the time limit"); i += 1
        elif verb == "wait" and not args and i < len(rest):
            args["seconds"] = duration(rest[i], line); i += 1
        else:
            raise FlowError(line, f'{verb} takes a "text"' + (", or a time such as wait 5s" if verb == "wait" else ""))
        done(rest, i, line, verb)
        return verb, args
    if verb == "location":
        if len(rest) == 1 and keyword(rest[0]) in ("on", "off"):
            return "location", {"enabled": keyword(rest[0]) == "on"}
        if len(rest) == 1 and keyword(rest[0]) in PLACES:
            lat, lon = PLACES[keyword(rest[0])]
            return "location", {"lat": lat, "lon": lon, "name": keyword(rest[0])}
        if len(rest) == 2:
            try:
                lat, lon = float(rest[0].rstrip(",")), float(rest[1])
            except ValueError:
                lat = lon = None
            if lat is not None and -90 <= lat <= 90 and -180 <= lon <= 180:
                return "location", {"lat": lat, "lon": lon}
        raise FlowError(line, f"location takes {', '.join(PLACES)}, a latitude and longitude such as 52.3745 4.8838, on or off")
    if verb == "network":
        if rest and keyword(rest[0]) in ("on", "off") and len(rest) == 1:
            return "network", {"mode": keyword(rest[0])}
        if rest and keyword(rest[0]) == "slow" and len(rest) <= 2:
            speed = keyword(rest[1]) if len(rest) == 2 else "edge"
            if speed not in SPEEDS:
                raise FlowError(line, f"network slow takes one of {', '.join(SPEEDS)}, not {rest[1]!r}")
            return "network", {"mode": "slow", "speed": speed}
        raise FlowError(line, "network takes off, on, or slow [gsm|gprs|edge|umts|hsdpa|lte]")
    if verb == "shot":
        if len(rest) != 1 or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,47}", str(rest[0])):
            raise FlowError(line, "shot takes a name of letters, digits, - and _, such as shot search-results")
        return "shot", {"name": str(rest[0])}
    if verb == "settle":
        if len(rest) != 1:
            raise FlowError(line, "settle takes a time, such as settle 2s")
        return "settle", {"seconds": duration(rest[0], line)}
    raise FlowError(line, f"no such line: {toks[0]!r} (tester-flows.md lists them)")


def parse(text):
    """The flow's steps, or FlowError naming the first line that does not parse."""
    steps = []
    for number, raw in enumerate(text.splitlines(), 1):
        for piece in pieces(raw, number):
            toks = tokens(piece, number)
            stop = False
            if len(toks) >= 2 and keyword(toks[-2]) == "or" and keyword(toks[-1]) == "stop":
                stop, toks = True, toks[:-2]
            if not toks:
                raise FlowError(number, '"or stop" after nothing')
            verb, args = parse_command(toks, number)
            steps.append(Step(len(steps) + 1, number, piece, verb, args, stop))
    if not steps:
        raise FlowError(0, "the flow has no steps")
    return steps


# --- the device ------------------------------------------------------------------------------------------------------

class HarnessError(Exception):
    pass


@dataclass
class Result:
    rc: int
    out: object  # str, or bytes for a binary call
    err: str


GONE = re.compile(r"(device offline|no devices/emulators found|device '.*' not found|device still connecting)")


class Adb:
    def __init__(self, log):
        self.cmd = os.environ.get("ATLAS_ADB", "adb")
        self.log = log
        self.gone = False   # the device left and did not come back

    def _call(self, args, timeout):
        try:
            p = subprocess.run([self.cmd, *args], capture_output=True, timeout=timeout)
            return Result(p.returncode, p.stdout, p.stderr.decode("utf-8", "replace"))
        except subprocess.TimeoutExpired:
            return Result(124, b"", f"adb {' '.join(args)} timed out after {timeout} s")
        except FileNotFoundError:
            raise HarnessError(f"no adb: {self.cmd}")

    def run(self, *args, timeout=60, binary=False):
        r = self._call(args, timeout)
        if r.rc != 0 and GONE.search(r.err) and not self.gone:
            # The device dropping off for a moment (smoke.sh's adb() met it on 4 November): once it is back, run again.
            self._call(["reconnect", "offline"], 20)
            back = self._call(["wait-for-device"], 120)
            if back.rc == 0:
                self.log(f"adb {' '.join(args)} met '{r.err.strip()}': the device came back, and the command ran again")
                r = self._call(args, timeout)
            else:
                self.log(f"adb {' '.join(args)} met '{r.err.strip()}', and the device did not come back in 120 s")
                self.gone = True
        if not binary:
            r.out = r.out.decode("utf-8", "replace").replace("\r", "")
        return r

    def shell(self, command, timeout=60):
        return self.run("shell", command, timeout=timeout)


def sh_quote(s):
    return "'" + s.replace("'", "'\\''") + "'"


def fold(s):
    """A text as the runner compares it: no accents, straight quotes, one space, any case."""
    s = unicodedata.normalize("NFKD", s or "")
    s = "".join(c for c in s if not unicodedata.combining(c))
    s = s.replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"')
    s = s.replace(" ", " ").replace("‑", "-")
    return " ".join(s.split()).casefold()


@dataclass
class Node:
    text: str
    desc: str
    bounds: tuple  # x1, y1, x2, y2
    pkg: str

    @property
    def label(self):
        return self.text or self.desc

    @property
    def centre(self):
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2


BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def ui_nodes(xml):
    root = ET.fromstring(xml)
    nodes = []
    for n in root.iter("node"):
        m = BOUNDS.match(n.get("bounds", ""))
        if not m:
            continue
        b = tuple(int(v) for v in m.groups())
        if b[2] <= b[0] or b[3] <= b[1]:
            continue
        nodes.append(Node(n.get("text", ""), n.get("content-desc", ""), b, n.get("package", "")))
    return nodes


def find(nodes, text):
    """The nodes that show `text`: those it names exactly first, then those that contain it, each in the screen's order."""
    q = fold(text)
    exact = [n for n in nodes if q in (fold(n.text), fold(n.desc))]
    within = [n for n in nodes if n not in exact and (q in fold(n.text) or q in fold(n.desc))]
    return exact + within


def png_size(data):
    if len(data) >= 24 and data[:8] == b"\x89PNG\r\n\x1a\n":
        return int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")
    return None


def px(value, extent):
    value = str(value)
    if value.endswith("%"):
        return int(round(float(value[:-1]) / 100 * (extent - 1)))
    return int(round(float(value)))


class Touch:
    """Two fingers on the emulator's touchscreen, through sendevent (the `input` command has one finger only)."""

    def __init__(self, path, axes, has_btn):
        self.path, self.axes, self.has_btn = path, axes, has_btn

    @staticmethod
    def from_getevent(text):
        """The first direct multi-touch device in `getevent -pl`'s list, or None."""
        found = []
        for block in re.split(r"(?m)^add device \d+: ", text)[1:]:
            path = block.split("\n", 1)[0].strip()
            name = (re.search(r'name:\s+"([^"]*)"', block) or [None, ""])[1]
            axes = {}
            for m in re.finditer(r"(ABS_MT_[A-Z_]+)\s*:\s*value -?\d+, min (-?\d+), max (-?\d+)", block):
                axes[m.group(1)] = (int(m.group(2)), int(m.group(3)))
            if "ABS_MT_POSITION_X" in axes and "ABS_MT_POSITION_Y" in axes:
                direct = "INPUT_PROP_DIRECT" in block
                order = int((re.search(r"(\d+)$", name) or [None, "99"])[1])
                found.append((not direct, order, path, name, axes, "BTN_TOUCH" in block))
        if not found:
            return None
        found.sort(key=lambda f: (f[0], f[1], f[2]))
        _, _, path, name, axes, btn = found[0]
        t = Touch(path, axes, btn)
        t.name = name
        return t

    def script(self, frames, natural, rotation):
        """The device's shell commands for frames of finger positions in the screen's coordinates (as it is turned):
        each frame a list of (x, y) per finger; the last frame lifts them."""
        W, H = natural
        (x0, x1), (y0, y1) = self.axes["ABS_MT_POSITION_X"], self.axes["ABS_MT_POSITION_Y"]
        pressure = self.axes.get("ABS_MT_PRESSURE")
        ev = f"sendevent {self.path}"
        lines = []

        def raw(x, y):
            nx, ny = to_natural(x, y, natural, rotation)
            return (x0 + int(nx * (x1 - x0 + 1) / W), y0 + int(ny * (y1 - y0 + 1) / H))

        for k, frame in enumerate(frames):
            for slot, (x, y) in enumerate(frame):
                rx, ry = raw(x, y)
                lines.append(f"{ev} 3 47 {slot}")
                if k == 0:
                    lines.append(f"{ev} 3 57 {100 + slot}")
                    if pressure:
                        lines.append(f"{ev} 3 58 {max(1, (pressure[1] - pressure[0]) // 2)}")
                lines.append(f"{ev} 3 53 {rx}")
                lines.append(f"{ev} 3 54 {ry}")
            if k == 0 and self.has_btn:
                lines.append(f"{ev} 1 330 1")
            lines.append(f"{ev} 0 0 0")
            lines.append("sleep 0.016")
        for slot in range(len(frames[-1])):
            lines.append(f"{ev} 3 47 {slot}")
            lines.append(f"{ev} 3 57 -1")
        if self.has_btn:
            lines.append(f"{ev} 1 330 0")
        lines.append(f"{ev} 0 0 0")
        return "; ".join(lines)


def to_natural(x, y, natural, rotation):
    """A point on the screen as it is turned, in the touchscreen's own (natural, portrait) coordinates."""
    W, H = natural
    if rotation == 1:
        return W - 1 - y, x
    if rotation == 2:
        return W - 1 - x, H - 1 - y
    if rotation == 3:
        return y, H - 1 - x
    return x, y


def pinch_frames(c, start, end, steps=12):
    cx, cy = c
    return [[(cx - r, cy), (cx + r, cy)] for r in
            (start + (end - start) * k / steps for k in range(steps + 1))]


def twist_frames(c, radius, degrees, steps=12):
    cx, cy = c
    frames = []
    for k in range(steps + 1):
        a = math.radians(degrees * k / steps)
        dx, dy = radius * math.cos(a), radius * math.sin(a)
        frames.append([(cx - dx, cy - dy), (cx + dx, cy + dy)])
    return frames


# --- the run ---------------------------------------------------------------------------------------------------------

def utc_now():
    t = time.time()
    return time.strftime("%H:%M:%S", time.gmtime(t)) + f".{int(t * 1000) % 1000:03d}"


def slug(s, limit=28):
    s = re.sub(r"[^a-z0-9]+", "-", fold(s)).strip("-")
    return s[:limit].rstrip("-")


def cell(s):
    return " ".join(str(s).split())


def shot_name(step):
    """A step's screenshot's name: a shot's own, else its verb and what it is about (\"tap-search-or-ask\")."""
    if step.verb == "shot":
        return step.args["name"]
    a = step.args
    about = a["target"][1] if a.get("target", ("",))[0] == "text" else a.get("text") or a.get("dir") or a.get("mode") \
        or a.get("name") or str(a.get("code", "")).replace("KEYCODE_", "")
    return "-".join(p for p in [step.verb, slug(str(about))] if p)


class Runner:
    def __init__(self, out, apk, tag="", label="flow"):
        self.out = Path(out)
        self.shots = self.out / "shots"
        self.shots.mkdir(parents=True, exist_ok=True)
        self.apk = Path(apk) if apk else None
        self.tag, self.label = tag, label
        self.logfile = open(self.out / "run.log", "a", encoding="utf-8")
        self.adb = Adb(self.log)
        self.natural = (1080, 2400)
        self.rotation = 0
        self.touch = None       # None: not looked for yet; False: none found
        self.settle = DEFAULT_SETTLE
        self.crashes = self.anrs = 0
        self.rows = []
        self.last_ui = None     # the last dump of the screen a step read
        self.dump_error = ""
        self.getevent = None    # getevent -pl's list of input devices
        self.stream = self.stream_file = None
        self.failed = None      # why the harness failed

    def log(self, message):
        line = f"{utc_now()} {message}"
        print(line, flush=True)
        self.logfile.write(line + "\n"); self.logfile.flush()

    def pause(self, seconds):
        if seconds * SCALE > 0:
            time.sleep(seconds * SCALE)

    # -- what the screen shows

    def screen(self):
        W, H = self.natural
        return (H, W) if self.rotation % 2 else (W, H)

    def point(self, where):
        """(x, y) in pixels for a ("point", x, y) target."""
        W, H = self.screen()
        return px(where[1], W), px(where[2], H)

    def dump(self):
        """The screen's nodes, or None with the reason in self.dump_error."""
        f = "/sdcard/atlas-ui.xml"
        r = self.adb.shell(f"rm -f {f}; uiautomator dump {f} 2>&1; cat {f} 2>/dev/null; rm -f {f}", timeout=45)
        m = re.search(r"<\?xml.*?</hierarchy>|<hierarchy.*?</hierarchy>", r.out, re.S)
        if not m:
            self.dump_error = (r.out.strip().splitlines() or [r.err.strip() or f"exit {r.rc}"])[0]
            return None
        self.last_ui = m.group(0)
        try:
            return ui_nodes(m.group(0))
        except ET.ParseError as e:
            self.dump_error = f"the dump did not parse: {e}"
            return None

    def seen(self, nodes):
        labels = []
        for n in nodes:
            if n.label and n.label not in labels:
                labels.append(n.label)
        return "saw: " + (", ".join(f'"{cell(s)[:40]}"' for s in labels[:10]) or "no text at all") + \
            (f" and {len(labels) - 10} more" if len(labels) > 10 else "")

    def look(self, text, limit, gone=False):
        """Polls the screen until `text` shows (or, gone, no longer shows), up to `limit` seconds.
        Returns (ok, seconds it took, nodes found, the last nodes)."""
        t0 = time.monotonic()
        nodes, good = None, None
        while True:
            nodes = self.dump()
            if nodes is not None:
                good = nodes
                hits = find(nodes, text)
                if bool(hits) != gone:
                    return True, time.monotonic() - t0, hits, nodes
            if time.monotonic() - t0 >= limit:
                return False, time.monotonic() - t0, [], good
            time.sleep(min(POLL, max(0.0, limit - (time.monotonic() - t0))))

    def place(self, where, what):
        """(x, y, detail) for a target, looking for a text for up to LOOK seconds; (None, None, why) when not found."""
        if where[0] == "point":
            x, y = self.point(where)
            return x, y, f"at {x},{y}"
        _, text, nth = where
        ok, took, hits, nodes = self.look(text, LOOK)
        if not ok:
            why = self.dump_error if nodes is None else self.seen(nodes)
            return None, None, f'"{text}" not on the screen in {LOOK:g} s; {why}'
        if len(hits) < nth:
            return None, None, f'"{text}" shows {len(hits)} time(s), not {nth}; {self.seen(nodes)}'
        x, y = hits[nth - 1].centre
        return x, y, f'"{cell(hits[nth - 1].label)[:40]}" at {x},{y}'

    # -- the steps

    def do_launch(self, a):
        r = self.adb.shell(f"am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n {ACTIVITY}",
                           timeout=120)
        fields = dict(re.findall(r"^(Status|LaunchState|TotalTime|WaitTime): (.*)$", r.out, re.M))
        ok = r.rc == 0 and fields.get("Status") == "ok" and "Error" not in r.out
        detail = ", ".join(f"{k} {v}" for k, v in fields.items() if k != "Status")
        if not ok:
            detail = (detail + "; " if detail else "") + cell(r.out or r.err)[:200]
        return ok, detail

    def do_close(self, a):
        self.adb.shell(f"am force-stop {PKG}")
        pid = self.adb.shell(f"pidof {PKG}").out.strip()
        return not pid, "closed" if not pid else f"still running as {pid}"

    def do_fresh(self, a):
        r = self.adb.shell(f"pm clear {PKG}")
        return "Success" in r.out, cell(r.out or r.err)[:200]

    def do_background(self, a):
        r = self.adb.shell("input keyevent KEYCODE_HOME")
        return r.rc == 0, "Home pressed"

    def do_back(self, a):
        r = self.adb.shell("input keyevent " + " ".join(["KEYCODE_BACK"] * a["times"]))
        return r.rc == 0, f"Back pressed {a['times']} time(s)"

    def _perms(self, verb, what):
        failed = []
        for w in what:
            for p in PERMISSIONS[w]:
                r = self.adb.shell(f"pm {verb} {PKG} {p}")
                if r.rc != 0 or "Exception" in r.out or "Error" in r.out:
                    failed.append(f"{p}: {cell(r.out or r.err)[:120]}")
        return not failed, "; ".join(failed) or f"{verb}ed {', '.join(what)}"

    def do_grant(self, a):
        return self._perms("grant", a["what"])

    def do_revoke(self, a):
        return self._perms("revoke", a["what"])

    def do_tap(self, a):
        x, y, detail = self.place(a["target"], "tap")
        if x is None:
            return False, detail
        r = self.adb.shell(f"input tap {x} {y}")
        return r.rc == 0, "tapped " + detail

    def do_longpress(self, a):
        x, y, detail = self.place(a["target"], "longpress")
        if x is None:
            return False, detail
        r = self.adb.shell(f"input swipe {x} {y} {x} {y} {a['ms']}", timeout=60 + a["ms"] // 1000)
        return r.rc == 0, f"pressed {a['ms']} ms " + detail

    def do_type(self, a):
        r = self.adb.shell("input text " + sh_quote(a["text"].replace(" ", "%s")))
        return r.rc == 0 and "Exception" not in r.out, f'typed "{a["text"]}"' if r.rc == 0 else cell(r.out or r.err)[:200]

    def do_key(self, a):
        r = self.adb.shell(f"input keyevent {a['code']}")
        return r.rc == 0 and "Exception" not in r.out, f"pressed {a['code']}"

    def do_swipe(self, a):
        W, H = self.screen()
        if "points" in a:
            x1, y1 = self.point(("point", *a["points"][:2]))
            x2, y2 = self.point(("point", *a["points"][2:]))
        else:
            d = a["dir"]
            horizontal = d in ("left", "right")
            length = int(0.4 * (W if horizontal else H))
            sign = -1 if d in ("up", "left") else 1
            if "from" in a:
                x1, y1, detail = self.place(a["from"], "swipe")
                if x1 is None:
                    return False, detail
            else:
                x1, y1 = W // 2, H // 2
                x1, y1 = (x1 - sign * length // 2, y1) if horizontal else (x1, y1 - sign * length // 2)
            x2, y2 = (x1 + sign * length, y1) if horizontal else (x1, y1 + sign * length)
            x2, y2 = min(max(x2, W // 50), W - 1 - W // 50), min(max(y2, H // 50), H - 1 - H // 50)
        r = self.adb.shell(f"input swipe {x1} {y1} {x2} {y2} {a['ms']}")
        return r.rc == 0, f"swiped {x1},{y1} to {x2},{y2} in {a['ms']} ms"

    def two_fingers(self, frames, what):
        if self.touch is None:
            if self.getevent is None:
                self.getevent = self.adb.shell("getevent -pl", timeout=30).out
            self.touch = Touch.from_getevent(self.getevent) or False
            if self.touch:
                self.log(f"two fingers through {self.touch.path} ({self.touch.name})")
        if not self.touch:
            return False, "no multi-touch screen in getevent -pl (device.txt has its list)"
        r = self.adb.shell(self.touch.script(frames, self.natural, self.rotation), timeout=60)
        return r.rc == 0, what if r.rc == 0 else f"{what}: {cell(r.out or r.err)[:200]}"

    def _centre(self, a):
        if "at" not in a:
            W, H = self.screen()
            return W // 2, H // 2, "the middle"
        x, y, detail = self.place(a["at"], "at")
        return x, y, detail

    def do_zoom(self, a):
        x, y, detail = self._centre(a)
        if x is None:
            return False, detail
        short = min(self.screen())
        near, far = int(short * 0.08), int(short * 0.35)
        start, end = (near, far) if a["dir"] == "in" else (far, near)
        return self.two_fingers(pinch_frames((x, y), start, end), f"pinched {a['dir']} at {detail}")

    def do_turn(self, a):
        x, y, detail = self._centre(a)
        if x is None:
            return False, detail
        radius = int(min(self.screen()) * 0.2)
        return self.two_fingers(twist_frames((x, y), radius, a["degrees"]), f"turned {a['degrees']:g}° at {detail}")

    def do_rotate(self, a):
        want = a["rotation"]
        self.adb.shell(f"settings put system accelerometer_rotation 0; settings put system user_rotation {want}")
        self.rotation = want
        self.pause(2)
        size = png_size(self.adb.run("exec-out", "screencap", "-p", binary=True).out)
        if size is None:
            return False, "asked; the screen could not be read to check it"
        landscape = size[0] > size[1]
        ok = landscape == (want % 2 == 1)
        return ok, f"the screen is {size[0]}x{size[1]}"

    def do_wait(self, a):
        if "seconds" in a:
            time.sleep(a["seconds"])
            return True, f"waited {a['seconds']:g} s"
        gone = a.get("gone", False)
        ok, took, hits, nodes = self.look(a["text"], a["limit"], gone)
        what = f'"{a["text"]}"' + (" gone" if gone else "")
        if ok:
            return True, f"{what} after {took:.1f} s" + ("" if gone else f': "{cell(hits[0].label)[:40]}"')
        why = self.dump_error if nodes is None else self.seen(nodes)
        return False, f"{what} not within {a['limit']:g} s; {why}"

    def do_expect(self, a):
        nodes = None
        for _ in range(3):
            nodes = self.dump()
            if nodes is not None:
                break
            self.pause(1)
        if nodes is None:
            return False, f"the screen could not be read: {self.dump_error}"
        hits = find(nodes, a["text"])
        if a.get("gone"):
            return not hits, f'no "{a["text"]}"' if not hits else f'"{a["text"]}" shows: "{cell(hits[0].label)[:40]}"'
        return bool(hits), f'"{cell(hits[0].label)[:40]}"' if hits else f'no "{a["text"]}"; {self.seen(nodes)}'

    def do_location(self, a):
        if "enabled" in a:
            r = self.adb.shell(f"cmd location set-location-enabled {'true' if a['enabled'] else 'false'}")
            return r.rc == 0, f"location {'on' if a['enabled'] else 'off'}"
        self.adb.shell("cmd location set-location-enabled true")
        r = self.adb.run("emu", "geo", "fix", f"{a['lon']}", f"{a['lat']}")
        ok = r.rc == 0 and "KO" not in r.out
        where = a.get("name", f"{a['lat']}, {a['lon']}")
        return ok, f"at {where} ({a['lat']}, {a['lon']})" if ok else f"geo fix: {cell(r.out or r.err)[:200]}"

    def addresses(self):
        r = self.adb.shell("ip -o -4 addr show")
        return [m.group(1) + " " + m.group(2) for m in re.finditer(r"^\d+:\s+(\S+)\s+inet (\S+)", r.out, re.M)
                if m.group(1) != "lo" and not m.group(1).startswith("dummy")]

    def await_network(self, up, limit):
        t0 = time.monotonic()
        while True:
            have = self.addresses()
            if bool(have) == up or time.monotonic() - t0 >= limit:
                return bool(have) == up, have
            time.sleep(max(POLL, 0.05))

    def do_network(self, a):
        mode = a["mode"]
        if mode == "off":
            self.adb.shell("svc wifi disable; svc data disable")
            ok, have = self.await_network(False, 15)
            return ok, "off" if ok else f"still has {', '.join(have)} after 15 s"
        self.adb.shell("svc wifi enable; svc data enable")
        speed = a.get("speed", "full")
        delay = DELAYS.get(speed, "umts") if mode == "slow" else "none"
        answers = [self.adb.run("emu", "network", "speed", speed), self.adb.run("emu", "network", "delay", delay)]
        console = all(r.rc == 0 and "KO" not in r.out for r in answers)
        ok, have = self.await_network(True, 30)
        detail = f"{'slow: speed ' + speed + ', delay ' + delay if mode == 'slow' else 'on'}"
        if not console:
            detail += "; the emulator's console said " + "; ".join(cell(r.out or r.err)[:80] for r in answers)
        detail += f"; {', '.join(have)}" if ok else "; no address after 30 s"
        return ok and console, detail

    def do_shot(self, a):
        return True, a["name"]

    def do_settle(self, a):
        self.settle = a["seconds"]
        return True, f"each step settles {a['seconds']:g} s before its screenshot"

    # -- the run's frame

    def counts(self):
        """How many of Atlas's crashes and ANRs the device's log holds."""
        r = self.adb.shell(f"logcat -d -b crash | grep -c -e '>>> {PKG} <<<' -e 'Process: {PKG}'; "
                           f"logcat -d | grep -c 'ANR in {PKG}'", timeout=60)
        nums = [int(v) for v in re.findall(r"^\d+$", r.out, re.M)]
        return (nums[0], nums[1]) if len(nums) == 2 else (self.crashes, self.anrs)

    def screenshot(self, name):
        r = self.adb.run("exec-out", "screencap", "-p", binary=True, timeout=60)
        if r.rc == 0 and png_size(r.out):
            (self.shots / name).write_bytes(r.out)
            return name
        self.log(f"no screenshot for {name}: {r.err.strip() or 'not a PNG'}")
        return ""

    def record(self, n, line, start, ms, result, detail, shot):
        row = [str(n), cell(line), start, str(ms), result, cell(detail), shot]
        self.rows.append(row)
        path = self.out / "steps.tsv"
        new = not path.exists()
        with open(path, "a", encoding="utf-8") as f:
            if new:
                f.write("step\tline\tstart\tms\tresult\tdetail\tshot\n")
            f.write("\t".join(v.replace("\t", " ") for v in row) + "\n")
        self.log(f"step {n} {result} in {ms} ms: {cell(line)} ({cell(detail)})")

    def facts(self):
        lines = []
        for prop in ["ro.product.model", "ro.build.version.release", "ro.build.version.sdk", "ro.build.fingerprint"]:
            lines.append(f"{prop}: {self.adb.shell(f'getprop {prop}').out.strip()}")
        size = self.adb.shell("wm size").out
        m = re.search(r"Override size: (\d+)x(\d+)", size) or re.search(r"Physical size: (\d+)x(\d+)", size)
        if m:
            self.natural = (int(m.group(1)), int(m.group(2)))
        lines.append(f"screen: {self.natural[0]}x{self.natural[1]}, {self.adb.shell('wm density').out.strip()}")
        pkg = self.adb.shell(f"dumpsys package {PKG}").out
        for key in ["versionName", "versionCode", "pkgFlags"]:
            m = re.search(rf"{key}=(\S.*)", pkg)
            if m:
                lines.append(f"{key}: {m.group(1).strip()}")
        self.getevent = self.adb.shell("getevent -pl", timeout=30).out
        lines.append("--- getevent -pl ---")
        lines.append(self.getevent.rstrip())
        (self.out / "device.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
        for line in lines[:8]:
            self.log("device: " + line)

    def harness(self, why):
        self.log(f"HARNESS FAILED: {why}")
        print(f"::error::tester.py: {why}", flush=True)
        self.failed = why
        return 3

    def run(self, steps, flow_text=""):
        (self.out / "flow.txt").write_text(flow_text, encoding="utf-8")
        self.log(f"flow {self.label}: {len(steps)} steps, alpha {self.tag or '(not named)'}")
        try:
            state = self.adb.run("get-state", timeout=30)
            if state.out.strip() != "device":
                return self.harness(f"no device: adb get-state said {cell(state.out or state.err)[:200]!r}")
            if self.adb.shell("getprop sys.boot_completed").out.strip() != "1":
                return self.harness("the device has not finished booting")
            if not self.apk or not self.apk.is_file():
                return self.harness(f"no APK at {self.apk}")
            t0, start = time.monotonic(), utc_now()
            r = self.adb.run("install", "-r", str(self.apk), timeout=600)
            ms = int((time.monotonic() - t0) * 1000)
            ok = r.rc == 0 and "Success" in r.out
            self.record(0, f"install {self.apk.name}", start, ms, "ok" if ok else "failed",
                        cell(r.out or r.err)[-200:], "")
            if not ok:
                return self.harness(f"the install failed: {cell(r.out or r.err)[-300:]}")
            self.facts()
            self.adb.run("logcat", "-b", "all", "-c")
            self.stream_file = open(self.out / "logcat.txt", "wb")
            self.stream = subprocess.Popen([self.adb.cmd, "logcat", "-v", "threadtime"],
                                           stdout=self.stream_file, stderr=subprocess.DEVNULL)
            stopped = None
            for step in steps:
                if stopped or self.adb.gone:
                    self.record(step.n, step.text, "", 0, "skipped",
                                f"after step {stopped}'s stop" if stopped else "the device is gone", "")
                    continue
                self.step(step)
                if step.stop and self.rows[-1][4] != "ok":
                    stopped = step.n
                    self.log(f"step {step.n} failed and its line says stop: the steps after it are skipped")
            if self.adb.gone:
                return self.harness("the device left during the run and did not come back")
            return 0
        except HarnessError as e:
            return self.harness(str(e))
        finally:
            self.finish()

    def step(self, step):
        self.last_ui = None
        start, t0 = utc_now(), time.monotonic()
        try:
            ok, detail = getattr(self, f"do_{step.verb}")(step.args)
        except HarnessError:
            raise
        except Exception as e:  # the runner's own fault: recorded, and the run goes on
            ok, detail = False, f"the runner failed on this line: {e!r}"
        ms = int((time.monotonic() - t0) * 1000)
        if step.verb not in ("wait", "expect", "shot", "settle"):
            self.pause(self.settle)
        result = "ok" if ok else "failed"
        crashes, anrs = self.counts()
        if crashes > self.crashes:
            result, detail = "crash", f"Atlas crashed ({crashes - self.crashes}); " + detail
        if anrs > self.anrs:
            result, detail = "anr", f"Atlas did not answer (ANR); " + detail
        self.crashes, self.anrs = crashes, anrs
        base = f"{step.n:02d}-{shot_name(step)}"
        shot = self.screenshot(base + ".png")
        if result != "ok" and self.last_ui:
            (self.shots / (base + ".ui.xml")).write_text(self.last_ui, encoding="utf-8")
        self.record(step.n, step.text, start, ms, result, detail, shot)

    def finish(self):
        if self.stream:
            self.stream.terminate()
            try:
                self.stream.wait(10)
            except subprocess.TimeoutExpired:
                self.stream.kill()
            self.stream_file.close()
        if self.adb.gone:
            self.summary()
            return
        if self.crashes:
            (self.out / "crash.txt").write_text(self.adb.run("logcat", "-d", "-b", "crash").out, encoding="utf-8")
        if self.anrs:
            # The traces are root's (the Google APIs image has it, as smoke.sh's ANR check uses).
            anr = self.out / "anr"
            anr.mkdir(exist_ok=True)
            self.adb.run("root", timeout=30)
            self.adb.run("wait-for-device", timeout=60)
            names = self.adb.shell("ls -t /data/anr/ 2>/dev/null | head -3").out.split()
            for n in names:
                self.adb.run("pull", f"/data/anr/{n}", str(anr / n), timeout=60)
        self.summary()

    def summary(self):
        harness = self.failed
        counts = {}
        for row in self.rows[1:] if self.rows and self.rows[0][0] == "0" else self.rows:
            counts[row[4]] = counts.get(row[4], 0) + 1
        head = f"### Tester: {self.tag or 'an alpha'}, flow {self.label}\n\n"
        if harness:
            head += f"**The harness failed:** {harness}\n\n"
        head += (", ".join(f"{v} {k}" for k, v in sorted(counts.items())) or "no steps ran") + "\n\n"
        table = "| step | line | start | ms | result | detail |\n|---:|---|---|---:|---|---|\n"
        for n, line, start, ms, result, detail, shot in self.rows:
            mark = result if result == "ok" else f"**{result}**"
            table += f"| {n} | `{line.replace('|', '/').replace('`', chr(39))}` | {start} | {ms} | {mark} | " \
                     f"{detail.replace('|', '/')[:300]} |\n"
        (self.out / "summary.md").write_text(head + table, encoding="utf-8")


def main(argv=None):
    ap = argparse.ArgumentParser(description="Runs a tester's flow on the emulator (tester-flows.md).")
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("check", help="parse the flow and list its steps")
    c.add_argument("flow")
    r = sub.add_parser("run", help="install the APK and run the flow")
    r.add_argument("flow")
    r.add_argument("--apk", required=True)
    r.add_argument("--out", required=True)
    r.add_argument("--tag", default="")
    r.add_argument("--label", default="flow")
    a = ap.parse_args(argv)
    try:
        text = Path(a.flow).read_text(encoding="utf-8")
    except OSError as e:
        print(f"::error::tester.py: the flow could not be read: {e}")
        return 2
    try:
        steps = parse(text)
    except FlowError as e:
        print(f"::error::tester.py: the flow does not parse: {e}")
        return 2
    if a.cmd == "check":
        print(f"the flow parses: {len(steps)} steps")
        for s in steps:
            print(f"  {s.n:2d}  line {s.line:3d}  {s.text}")
        return 0
    return Runner(a.out, a.apk, a.tag, a.label).run(steps, text)


if __name__ == "__main__":
    sys.exit(main())
