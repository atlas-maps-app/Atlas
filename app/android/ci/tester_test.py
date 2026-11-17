#!/usr/bin/env python3
"""tester.py's tests, against a stand-in adb that records what it is asked (tester_fakeadb.py): the flow's parsing,
each line's adb calls, a wait that fails, a stop, a crash, the timings table and the harness's own failures.

  python3 -I app/android/ci/tester_test.py

Python 3's standard library only; no device.
"""

import importlib.util
import json
import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
os.environ["ATLAS_TESTER_SCALE"] = "0"
os.environ["ATLAS_TESTER_POLL"] = "0.01"
os.environ["ATLAS_TESTER_LOOK"] = "0.3"
_spec = importlib.util.spec_from_file_location("tester", HERE / "tester.py")
tester = importlib.util.module_from_spec(_spec)
sys.modules["tester"] = tester
_spec.loader.exec_module(tester)


def ui(*nodes):
    """A uiautomator dump: (text, desc, bounds) per node."""
    body = "".join(f'<node index="{i}" text="{t}" resource-id="" class="android.view.View" package="app.atlas" '
                   f'content-desc="{d}" clickable="true" enabled="true" bounds="{b}" />'
                   for i, (t, d, b) in enumerate(nodes))
    return ("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"
            f'<node index="0" text="" class="android.widget.FrameLayout" package="app.atlas" content-desc="" '
            f'bounds="[0,0][1080,2400]">{body}</node></hierarchy>')


HOME = ui(("Search or ask", "", "[40,2100][1040,2220]"), ("", "Zoom in", "[960,1000][1040,1080]"),
          ("Café de Jaren", "", "[40,400][1040,520]"), ("Café Hoppe", "", "[40,560][1040,680]"))

GETEVENT = """add device 1: /dev/input/event1
  name:     "Power Button"
  events:
    KEY (0001): KEY_POWER
add device 2: /dev/input/event2
  name:     "virtio_input_multi_touch_0"
  events:
    KEY (0001): BTN_TOUCH
    ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_X     : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_Y     : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
                ABS_MT_TRACKING_ID    : value 0, min 0, max 10, fuzz 0, flat 0, resolution 0
                ABS_MT_PRESSURE       : value 0, min 0, max 4095, fuzz 0, flat 0, resolution 0
  input props:
    INPUT_PROP_DIRECT
"""


class Parsing(unittest.TestCase):
    def one(self, line):
        steps = tester.parse(line)
        self.assertEqual(len(steps), 1)
        return steps[0].verb, steps[0].args

    def bad(self, text, words):
        with self.assertRaises(tester.FlowError) as e:
            tester.parse(text)
        self.assertIn(words, str(e.exception))
        return e.exception

    def test_each_line(self):
        cases = {
            "launch": ("launch", {}), "open": ("launch", {}), "close": ("close", {}), "fresh": ("fresh", {}),
            "background": ("background", {}), "back": ("back", {"times": 1}), "back 3": ("back", {"times": 3}),
            "grant location notifications": ("grant", {"what": ["location", "notifications"]}),
            "revoke microphone": ("revoke", {"what": ["microphone"]}),
            'tap "Search or ask"': ("tap", {"target": ("text", "Search or ask", 1)}),
            'tap "Café" 2': ("tap", {"target": ("text", "Café", 2)}),
            "tap 540 1200": ("tap", {"target": ("point", "540", "1200")}),
            "tap 50% 80%": ("tap", {"target": ("point", "50%", "80%")}),
            "longpress center": ("longpress", {"target": ("point", "50%", "50%"), "ms": 1000}),
            'longpress "Westerkerk" 2s': ("longpress", {"target": ("text", "Westerkerk", 1), "ms": 2000}),
            'type "cafe de jaren"': ("type", {"text": "cafe de jaren"}),
            "key enter": ("key", {"code": "KEYCODE_ENTER"}), "key KEYCODE_F1": ("key", {"code": "KEYCODE_F1"}),
            "swipe up": ("swipe", {"dir": "up", "ms": 300}),
            "swipe left from 90% 50% 800ms": ("swipe", {"dir": "left", "from": ("point", "90%", "50%"), "ms": 800}),
            "swipe 10% 50% 90% 50%": ("swipe", {"points": ["10%", "50%", "90%", "50%"], "ms": 300}),
            "zoom in": ("zoom", {"dir": "in"}), "zoom out at 30% 40%": ("zoom", {"dir": "out", "at": ("point", "30%", "40%")}),
            "turn -90": ("turn", {"degrees": -90.0}),
            "rotate landscape": ("rotate", {"rotation": 1}), "rotate portrait": ("rotate", {"rotation": 0}),
            "wait 5s": ("wait", {"seconds": 5.0}), "wait 500ms": ("wait", {"seconds": 0.5}),
            'wait "Café"': ("wait", {"text": "Café", "limit": 30.0}),
            'wait for "Café" up to 2m': ("wait", {"text": "Café", "limit": 120.0}),
            'wait gone "Loading" 45s': ("wait", {"gone": True, "text": "Loading", "limit": 45.0}),
            'expect "Nearest"': ("expect", {"text": "Nearest"}),
            'expect no "unreadable"': ("expect", {"gone": True, "text": "unreadable"}),
            "location amsterdam": ("location", {"lat": 52.37452, "lon": 4.88377, "name": "amsterdam"}),
            "location 52.3745, 4.8838": ("location", {"lat": 52.3745, "lon": 4.8838}),
            "location off": ("location", {"enabled": False}),
            "network off": ("network", {"mode": "off"}), "network slow": ("network", {"mode": "slow", "speed": "edge"}),
            "network slow gsm": ("network", {"mode": "slow", "speed": "gsm"}),
            "shot search-results": ("shot", {"name": "search-results"}),
            "settle 2s": ("settle", {"seconds": 2.0}),
            "TAP CENTER": ("tap", {"target": ("point", "50%", "50%")}),
        }
        for line, want in cases.items():
            with self.subTest(line=line):
                self.assertEqual(self.one(line), want)

    def test_flow_shape(self):
        steps = tester.parse('# a comment\n\nlaunch or stop\n  tap "A; B" ; back   # Back, twice\n'
                             'wait "What\u2019s here" 10s\ntap \u201cLater\u201d\n')
        self.assertEqual([(s.n, s.line, s.verb, s.stop) for s in steps],
                         [(1, 3, "launch", True), (2, 4, "tap", False), (3, 4, "back", False),
                          (4, 5, "wait", False), (5, 6, "tap", False)])
        self.assertEqual(steps[1].args["target"], ("text", "A; B", 1))
        self.assertEqual(steps[1].text, 'tap "A; B"')
        self.assertEqual(steps[4].args["target"], ("text", "Later", 1))

    def test_errors_name_the_line(self):
        self.assertEqual(self.bad("launch\n\nfly away\n", "no such line").line, 3)
        self.bad('tap "Search', "closing quote")
        self.bad('type "café"', "plain ASCII")
        self.bad("type cafe", "one text in quotes")
        self.bad('wait "x" soon', "the time limit")
        self.bad("tap", "needs a place")
        self.bad("tap 50%", "needs a place")
        self.bad("swipe sideways", "swipe takes")
        self.bad("grant camera", "grant knows")
        self.bad("network fast", "network takes")
        self.bad("network slow 5g", "network slow takes")
        self.bad("location 95 4", "location takes")
        self.bad("rotate upside", "rotate takes")
        self.bad("shot my shot", "shot takes")
        self.bad("launch now", "nothing more")
        self.bad("or stop", "after nothing")
        self.bad("# nothing but a comment\n", "no steps")

    def test_example_flow_parses(self):
        steps = tester.parse((HERE / "tester-example.flow").read_text(encoding="utf-8"))
        verbs = {s.verb for s in steps}
        self.assertTrue({"location", "launch", "wait", "tap", "type", "key", "longpress", "shot", "back"} <= verbs)
        self.assertTrue(steps[[s.verb for s in steps].index("launch")].stop)


class Device(unittest.TestCase):
    def test_matching(self):
        nodes = tester.ui_nodes(HOME)
        self.assertEqual([n.label for n in tester.find(nodes, "cafe")], ["Café de Jaren", "Café Hoppe"])
        self.assertEqual([n.label for n in tester.find(nodes, "café hoppe")], ["Café Hoppe"])
        self.assertEqual([n.label for n in tester.find(nodes, "ZOOM IN")], ["Zoom in"])
        self.assertEqual(tester.fold("What\u2019s  here"), "what's here")

    def test_touchscreen_and_turned_screen(self):
        t = tester.Touch.from_getevent(GETEVENT)
        self.assertEqual(t.path, "/dev/input/event2")
        self.assertIsNone(tester.Touch.from_getevent(GETEVENT.split("add device 2")[0]))
        self.assertEqual(tester.to_natural(0, 0, (1080, 2400), 0), (0, 0))
        # landscape (ROTATION_90): the screen's top left is the natural top right
        self.assertEqual(tester.to_natural(0, 0, (1080, 2400), 1), (1079, 0))
        self.assertEqual(tester.to_natural(2399, 1079, (1080, 2400), 1), (0, 2399))
        script = t.script([[(540, 1200), (540, 1200)]], (1080, 2400), 0)
        self.assertIn("sendevent /dev/input/event2 3 53 16384", script)
        self.assertIn("sendevent /dev/input/event2 3 58 2047", script)
        self.assertTrue(script.endswith("sendevent /dev/input/event2 0 0 0"))


class Runs(unittest.TestCase):
    """Whole runs against the stand-in adb."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        fake = HERE / "tester_fakeadb.py"
        adb = self.dir / "adb"
        adb.write_text(f'#!/bin/sh\nexec "{sys.executable}" -I "{fake}" "$@"\n')
        adb.chmod(adb.stat().st_mode | stat.S_IEXEC)
        self.apk = self.dir / "atlas-v0.1.0-alpha.33.apk"
        self.apk.write_bytes(b"PK")
        os.environ["ATLAS_ADB"] = str(adb)
        os.environ["FAKE_ADB_DIR"] = str(self.dir)
        self.config({})

    def tearDown(self):
        self.tmp.cleanup()

    def config(self, c):
        c.setdefault("ui", [HOME])
        c.setdefault("getevent", GETEVENT)
        (self.dir / "config.json").write_text(json.dumps(c))
        (self.dir / "state.json").unlink(missing_ok=True)

    def run_flow(self, text):
        out = self.dir / "out"
        code = tester.Runner(out, self.apk, "v0.1.0-alpha.33", "test").run(tester.parse(text), text)
        calls = [json.loads(l) for l in (self.dir / "calls.jsonl").read_text().splitlines()]
        rows = [l.split("\t") for l in (out / "steps.tsv").read_text().splitlines()]
        return code, calls, rows, out

    def shells(self, calls):
        return [" ".join(c[1:]) for c in calls if c and c[0] == "shell"]

    def test_each_line_asks_adb(self):
        flow = "\n".join([
            "fresh", "grant location", "launch", 'tap "Search or ask"', 'type "cafe de jaren"', "key enter",
            'tap "Café" 2', "tap 50% 0%", "longpress center", "swipe up", "zoom in", "turn 90", "back 2", "background",
            "close", "location amsterdam", "network off", "network slow", "network on", "shot after",
        ])
        self.config({"ip": ["", "1: lo    inet 127.0.0.1/8 scope host lo\n17: wlan0    inet 10.0.2.16/24 scope global wlan0\n"]})
        code, calls, rows, out = self.run_flow(flow)
        self.assertEqual(code, 0)
        self.assertEqual(calls[0], ["get-state"])
        self.assertIn(["install", "-r", str(self.apk)], calls)
        shells = self.shells(calls)
        for want in [
            "pm clear app.atlas",
            "pm grant app.atlas android.permission.ACCESS_FINE_LOCATION",
            "pm grant app.atlas android.permission.ACCESS_COARSE_LOCATION",
            "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n app.atlas/.MainActivity",
            "input tap 540 2160",                       # "Search or ask": its middle
            "input text 'cafe%sde%sjaren'",
            "input keyevent KEYCODE_ENTER",
            "input tap 540 620",                        # the second "Café"
            "input tap 540 0",
            "input swipe 540 1200 540 1200 1000",
            "input swipe 540 1680 540 720 300",         # up: the finger goes from 70% to 30% of the screen
            "input keyevent KEYCODE_BACK KEYCODE_BACK",
            "input keyevent KEYCODE_HOME",
            "am force-stop app.atlas",
            "cmd location set-location-enabled true",
            "svc wifi disable; svc data disable",
            "svc wifi enable; svc data enable",
        ]:
            self.assertIn(want, shells, want)
        sendevents = [s for s in shells if s.startswith("sendevent")]
        self.assertEqual(len(sendevents), 2)            # the pinch and the turn, one script each
        self.assertIn(["emu", "geo", "fix", "4.88377", "52.37452"], calls)
        self.assertIn(["emu", "network", "speed", "edge"], calls)
        self.assertIn(["emu", "network", "delay", "edge"], calls)
        self.assertIn(["emu", "network", "speed", "full"], calls)
        self.assertIn(["emu", "network", "delay", "none"], calls)
        self.assertEqual([r[4] for r in rows[1:]], ["ok"] * 21, rows)
        self.assertEqual(rows[2][5], "Success")
        self.assertIn("LaunchState COLD, TotalTime 812", rows[4][5])
        self.assertTrue((out / "shots" / "20-after.png").is_file())
        self.assertTrue((out / "shots" / "04-tap-search-or-ask.png").is_file())
        self.assertTrue((out / "device.txt").read_text().count("virtio_input_multi_touch_0"))

    def test_a_wait_that_fails_and_the_run_goes_on(self):
        code, calls, rows, out = self.run_flow('wait "Nearest" 0.3s\nwait gone "Café" 0.2s\nwait "café hoppe" 1s\n')
        self.assertEqual(code, 0)
        self.assertEqual([r[4] for r in rows[2:]], ["failed", "failed", "ok"])
        self.assertIn('"Nearest" not within 0.3 s; saw: "Search or ask", "Zoom in", "Café de Jaren"', rows[2][5])
        self.assertTrue((out / "shots" / "01-wait-nearest.ui.xml").is_file())
        self.assertGreaterEqual(int(rows[2][3]), 300)
        self.assertIn('"café hoppe" after', rows[4][5])
        self.assertIn("**failed**", (out / "summary.md").read_text())

    def test_a_wait_that_sees_its_text_later(self):
        self.config({"ui": [ui(), None, ui(("Nearest", "", "[0,0][10,10]"))]})
        code, calls, rows, out = self.run_flow('wait "Nearest" 5s')
        self.assertEqual(rows[2][4], "ok")
        self.assertEqual(sum("uiautomator dump" in s for s in self.shells(calls)), 3)

    def test_a_screen_that_cannot_be_read(self):
        self.config({"ui": [None]})
        code, calls, rows, out = self.run_flow('expect "Nearest"\ntap "Later"')
        self.assertEqual([r[4] for r in rows[2:]], ["failed", "failed"])
        self.assertIn("could not get idle state", rows[2][5])
        self.assertIn("could not get idle state", rows[3][5])

    def test_stop(self):
        code, calls, rows, out = self.run_flow('launch\ntap "Nowhere" or stop\nback\nshot end\n')
        self.assertEqual(code, 0)
        self.assertEqual([r[4] for r in rows[2:]], ["ok", "failed", "skipped", "skipped"])
        self.assertIn("after step 2's stop", rows[4][5])
        self.assertNotIn("input keyevent KEYCODE_BACK", self.shells(calls))

    def test_a_crash_marks_its_step(self):
        self.config({"counts": ["0\n0", "1\n0", "1\n1"], "crashlog": "FATAL EXCEPTION: main\nProcess: app.atlas\n"})
        code, calls, rows, out = self.run_flow("launch\ntap center\nback\n")
        self.assertEqual([r[4] for r in rows[2:]], ["ok", "crash", "anr"])
        self.assertIn("Atlas crashed (1)", rows[3][5])
        self.assertIn("FATAL EXCEPTION", (out / "crash.txt").read_text())
        self.assertTrue((out / "anr" / "anr_2026-11-17-06-50-01-000").is_file())

    def test_rotate_checks_the_screen(self):
        self.config({"screen": [[2400, 1080], [2400, 1080], [2400, 1080]]})
        code, calls, rows, out = self.run_flow("rotate landscape\ntap 10% 50%\n")
        self.assertEqual(rows[2][4], "ok", rows[2])
        self.assertEqual(rows[2][5], "the screen is 2400x1080")
        self.assertIn("settings put system accelerometer_rotation 0; settings put system user_rotation 1", self.shells(calls))
        self.assertIn("input tap 240 540", self.shells(calls))   # 10% of 2400 wide, 50% of 1080 high

    def test_the_timings_table(self):
        code, calls, rows, out = self.run_flow("launch\nwait 0.2s\nshot home\n")
        self.assertEqual(rows[0], ["step", "line", "start", "ms", "result", "detail", "shot"])
        self.assertEqual([r[0] for r in rows[1:]], ["0", "1", "2", "3"])
        self.assertEqual(rows[1][1], "install atlas-v0.1.0-alpha.33.apk")
        for r in rows[1:]:
            self.assertEqual(len(r), 7)
            self.assertRegex(r[2], r"^\d\d:\d\d:\d\d\.\d{3}$")
            self.assertTrue(r[3].isdigit())
        self.assertGreaterEqual(int(rows[3][3]), 200)
        self.assertEqual(rows[4][6], "03-home.png")
        summary = (out / "summary.md").read_text()
        self.assertIn("### Tester: v0.1.0-alpha.33, flow test", summary)
        self.assertIn("3 ok", summary)
        self.assertIn("| 2 | `wait 0.2s` |", summary)
        self.assertTrue((out / "logcat.txt").read_text().startswith("11-17"))
        self.assertEqual((out / "flow.txt").read_text(), "launch\nwait 0.2s\nshot home\n")

    def test_the_harness_fails(self):
        self.config({"state": "offline"})
        self.assertEqual(tester.Runner(self.dir / "o1", self.apk).run(tester.parse("launch")), 3)
        self.config({"install": "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]"})
        out = self.dir / "o2"
        self.assertEqual(tester.Runner(out, self.apk).run(tester.parse("launch")), 3)
        self.assertIn("The harness failed:** the install failed", (out / "summary.md").read_text())
        self.config({})
        self.assertEqual(tester.Runner(self.dir / "o3", self.dir / "none.apk").run(tester.parse("launch")), 3)

    def test_the_command_line(self):
        bad = self.dir / "bad.flow"
        bad.write_text("launch\nfly\n")
        self.assertEqual(tester.main(["check", str(bad)]), 2)
        self.assertEqual(tester.main(["run", str(bad), "--apk", str(self.apk), "--out", str(self.dir / "o")]), 2)
        self.assertFalse((self.dir / "calls.jsonl").exists())        # a flow that does not parse asks the device nothing
        self.assertEqual(tester.main(["check", str(HERE / "tester-example.flow")]), 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
