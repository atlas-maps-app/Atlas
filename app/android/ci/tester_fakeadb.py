#!/usr/bin/env python3
"""A stand-in adb for tester_test.py: it records each call (calls.jsonl) and answers as an emulator would, from
config.json, both in the folder FAKE_ADB_DIR names. A list in the config is answered one item per call, the last
item again once the list is used up (the screen the app shows from one dump to the next, say).

Python 3's standard library only.
"""

import json
import os
import struct
import sys
import zlib
from pathlib import Path

DIR = Path(os.environ["FAKE_ADB_DIR"])


def png(w, h):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"\0" * (w * 3 + 1))) + chunk(b"IEND", b""))


def answer(key, default):
    """The config's answer for key, one list item per call."""
    config = json.loads((DIR / "config.json").read_text())
    if key not in config:
        return default
    value = config[key]
    if not isinstance(value, list):
        return value
    state_path = DIR / "state.json"
    state = json.loads(state_path.read_text()) if state_path.exists() else {}
    i = state.get(key, 0)
    state[key] = i + 1
    state_path.write_text(json.dumps(state))
    return value[min(i, len(value) - 1)]


def out(text, rc=0):
    sys.stdout.write(text)
    sys.exit(rc)


def main(args):
    with open(DIR / "calls.jsonl", "a") as f:
        f.write(json.dumps(args) + "\n")
    if not args:
        out("", 1)
    if args[0] == "get-state":
        out(answer("state", "device") + "\n")
    if args[0] in ("wait-for-device", "reconnect", "root"):
        out("")
    if args[0] == "install":
        result = answer("install", "Success")
        out("Performing Streamed Install\n" + result + "\n", 0 if result == "Success" else 1)
    if args[0] == "exec-out" and args[1:3] == ["screencap", "-p"]:
        w, h = answer("screen", [1080, 2400])
        sys.stdout.buffer.write(png(w, h))
        sys.exit(0)
    if args[0] == "emu":
        out(answer("emu", "OK") + "\n")
    if args[0] == "logcat":
        if "-c" in args:
            out("")
        if "-d" in args:
            out(answer("crashlog", ""))
        # the run's stream: a line, then the stream ends as a device's would when the run stops it
        out("11-17 06:50:00.000  1000  1000 I ActivityManager: the stand-in's log\n")
    if args[0] == "pull":
        Path(args[2]).write_text("a stand-in trace\n")
        out("")
    if args[0] != "shell":
        out("", 0)
    cmd = " ".join(args[1:])
    if cmd == "getprop sys.boot_completed":
        out(answer("boot", "1") + "\n")
    if cmd.startswith("getprop "):
        out("stand-in\n")
    if cmd == "wm size":
        out("Physical size: 1080x2400\n")
    if cmd == "wm density":
        out("Physical density: 420\n")
    if cmd.startswith("dumpsys package"):
        out("    versionCode=33 minSdk=26 targetSdk=35\n    versionName=0.1.0-alpha.33\n    pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]\n")
    if cmd.startswith("getevent"):
        out(answer("getevent", ""))
    if "uiautomator dump" in cmd:
        ui = answer("ui", None)
        if ui is None:
            out("ERROR: could not get idle state.\n")
        out("UI hierchary dumped to: /sdcard/atlas-ui.xml\n" + ui)
    if "logcat -d -b crash" in cmd:
        out(answer("counts", "0\n0") + "\n")
    if cmd.startswith("am start"):
        out(answer("start", "Starting: Intent { act=android.intent.action.MAIN }\nStatus: ok\nLaunchState: COLD\n"
                            "Activity: app.atlas/.MainActivity\nTotalTime: 812\nWaitTime: 815\nComplete\n"))
    if cmd.startswith("pidof"):
        out(answer("pidof", ""))
    if cmd.startswith("pm clear"):
        out("Success\n")
    if cmd.startswith("ip -o -4 addr"):
        out(answer("ip", "1: lo    inet 127.0.0.1/8 scope host lo\n"
                         "17: wlan0    inet 10.0.2.16/24 brd 10.0.2.255 scope global wlan0\n"))
    if cmd.startswith("ls -t /data/anr"):
        out("anr_2026-11-17-06-50-01-000\n")
    out("")


if __name__ == "__main__":
    main(sys.argv[1:])
