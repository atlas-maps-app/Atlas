#!/usr/bin/env python3
"""The packer's checks: python3 -I app/android/transit/pack/test_ttpack.py

- the hand-written feeds (planner/tests/data/tiny and edges) pack to the very bytes kept beside them (tiny.tt,
  edges.tt), twice over: the planner's tests read those files, so they can't drift from the packer, and the same
  feed gives the same bytes;
- the days the clock changes are found from the feed's timezone and written into the file (edges has one);
- the cosine table is the planner's (planner/src/geo.rs), number for number, so both measure alike;
- a table or a column the packer does not read is said, and with --strict refused with nothing written;
- ttlist.py writes the list of published files, and says when only a changed feed is wanted.

Python 3's standard library only.
"""

import datetime
import os
import re
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "..", "planner", "tests", "data")
sys.path.insert(0, HERE)
import ttpack  # noqa: E402


class Packer(unittest.TestCase):
    def pack(self, out, feed="tiny"):
        r = subprocess.run([sys.executable, "-I", os.path.join(HERE, "ttpack.py"), os.path.join(DATA, feed), out], check=True, capture_output=True, text=True)
        self.said = r.stdout
        with open(out, "rb") as f:
            return f.read()

    def test_the_tiny_feed_packs_to_the_kept_bytes(self):
        with open(os.path.join(DATA, "tiny.tt"), "rb") as f:
            kept = f.read()
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(self.pack(os.path.join(tmp, "a.tt")), kept, "tiny.tt is stale: pack planner/tests/data/tiny again")
            self.assertEqual(self.pack(os.path.join(tmp, "b.tt")), kept)
            self.assertNotIn("the clock changes", self.said)
        self.assertNotIn(b"odd_days", kept)

    def test_the_edges_feed_packs_to_the_kept_bytes(self):
        # (planner/tests/edges.rs: its days span the night the clock goes forward, which the file must say)
        with open(os.path.join(DATA, "edges.tt"), "rb") as f:
            kept = f.read()
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(self.pack(os.path.join(tmp, "a.tt"), "edges"), kept, "edges.tt is stale: pack planner/tests/data/edges again")
            self.assertEqual(self.pack(os.path.join(tmp, "b.tt"), "edges"), kept)
            self.assertIn("the clock changes: the day after 2027-03-27 begins 82800 s after it", self.said)
        self.assertIn(b"odd_days=20270327:82800", kept.replace(b"\0", b"="))

    def test_the_days_the_clock_changes(self):
        day = datetime.date
        self.assertEqual(ttpack.odd_days("Europe/Amsterdam", day(2027, 3, 26), 4), [(day(2027, 3, 27), 82800)])
        self.assertEqual(ttpack.odd_days("Europe/Amsterdam", day(2027, 10, 29), 4), [(day(2027, 10, 30), 90000)])
        self.assertEqual(ttpack.odd_days("Europe/Amsterdam", day(2026, 11, 16), 400), [(day(2027, 3, 27), 82800), (day(2027, 10, 30), 90000)])
        self.assertEqual(ttpack.odd_days("UTC", day(2027, 3, 26), 4), [])
        self.assertIsNone(ttpack.odd_days("No/Such_Zone", day(2027, 3, 26), 4))

    def test_the_cosine_table_is_the_planners(self):
        with open(os.path.join(HERE, "..", "planner", "src", "geo.rs"), encoding="utf-8") as f:
            src = f.read()
        body = re.search(r"COS_TABLE: \[u32; 901\] = \[(.*?)\];", src, re.S).group(1)
        self.assertEqual([int(v) for v in re.findall(r"\d+", body)], ttpack.COS_TABLE)

    def test_what_is_not_read_is_said_and_strictly_refused(self):
        import shutil

        with tempfile.TemporaryDirectory() as tmp:
            feed = os.path.join(tmp, "tiny")  # (the file names its feed by this)
            shutil.copytree(os.path.join(DATA, "tiny"), feed)
            out = os.path.join(tmp, "out.tt")
            run = lambda *more: subprocess.run([sys.executable, "-I", os.path.join(HERE, "ttpack.py"), feed, out, *more], capture_output=True, text=True)
            # an empty table and an empty column lose nothing: said, and no refusal
            with open(os.path.join(feed, "pathways.txt"), "w") as f:
                f.write("pathway_id,from_stop_id,to_stop_id\n")
            with open(os.path.join(feed, "routes.txt")) as f:
                rows = f.read().splitlines()
            with open(os.path.join(feed, "routes.txt"), "w") as f:
                f.write("\n".join([rows[0] + ",network_id"] + [r + "," for r in rows[1:]]) + "\n")
            r = run("--strict")
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertIn("empty, so nothing is lost: pathways.txt", r.stdout)
            self.assertIn("empty, so nothing is lost: routes.txt column network_id", r.stdout)
            with open(os.path.join(DATA, "tiny.tt"), "rb") as f, open(out, "rb") as g:
                self.assertEqual(f.read(), g.read())
            os.remove(out)
            # a table with rows, a column with values, a column of stop_times with values: said; strictly, refused
            with open(os.path.join(feed, "pathways.txt"), "a") as f:
                f.write("P1,C1,C2\n")
            with open(os.path.join(feed, "routes.txt"), "w") as f:
                f.write("\n".join([rows[0] + ",network_id"] + [r + ",N" for r in rows[1:]]) + "\n")
            with open(os.path.join(feed, "stop_times.txt")) as f:
                rows = f.read().splitlines()
            with open(os.path.join(feed, "stop_times.txt"), "w") as f:
                f.write("\n".join([rows[0] + ",continuous_pickup"] + [r + ",1" for r in rows[1:3]] + [r + "," for r in rows[3:]]) + "\n")
            r = run()
            self.assertEqual(r.returncode, 0, r.stderr)
            for said in ("NOT READ: pathways.txt (1 rows)", "NOT READ: routes.txt column network_id (3 values)", "NOT READ: stop_times.txt column continuous_pickup (2 values)"):
                self.assertIn(said, r.stdout)
            os.remove(out)
            r = run("--strict")
            self.assertNotEqual(r.returncode, 0)
            self.assertIn("refused (--strict)", r.stderr)
            self.assertIn("pathways.txt; routes.txt column network_id; stop_times.txt column continuous_pickup", r.stderr)
            self.assertFalse(os.path.exists(out))
            # trips by headway are refused whatever the mode
            os.remove(os.path.join(feed, "pathways.txt"))
            with open(os.path.join(feed, "frequencies.txt"), "w") as f:
                f.write("trip_id,start_time,end_time,headway_secs\nT1,06:00:00,07:00:00,600\n")
            r = run()
            self.assertNotEqual(r.returncode, 0)
            self.assertIn("frequencies.txt has rows", r.stderr)

    def test_a_feed_of_more_days_than_a_file_holds(self):
        import shutil

        with tempfile.TemporaryDirectory() as tmp:
            feed = os.path.join(tmp, "tiny")
            shutil.copytree(os.path.join(DATA, "tiny"), feed)
            out = os.path.join(tmp, "out.tt")
            run = lambda *more: subprocess.run([sys.executable, "-I", os.path.join(HERE, "ttpack.py"), feed, out, *more], capture_output=True, text=True)
            # the weekdays' service runs on to 1 March 2027: 421 days from the feed's first
            with open(os.path.join(feed, "calendar.txt")) as f:
                text = f.read()
            with open(os.path.join(feed, "calendar.txt"), "w") as f:
                f.write(text.replace("WK,1,1,1,1,1,0,0,20260105,20260111", "WK,1,1,1,1,1,0,0,20260105,20270301"))
            r = run("--strict")
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertIn("left out: the feed's days after 2027-02-08 (it has 421 from 2026-01-05; a file holds 400)", r.stdout)
            self.assertIn("kept: 400 days from 2026-01-05", r.stdout)
            # asked for by hand, more days than a file holds is a mistake
            r = run("--days", "401")
            self.assertNotEqual(r.returncode, 0)
            self.assertIn("1 to 400 are allowed", r.stderr)

    def test_the_list_of_published_files(self):
        import gzip
        import json

        with tempfile.TemporaryDirectory() as tmp:
            tt, gz, out = os.path.join(DATA, "tiny.tt"), os.path.join(tmp, "tiny.tt.gz"), os.path.join(tmp, "timetable.json")
            with open(tt, "rb") as f, open(gz, "wb") as g:
                g.write(gzip.compress(f.read(), 9, mtime=0))
            lister = [sys.executable, "-I", os.path.join(HERE, "ttlist.py")]
            # nothing published yet: the feed is wanted whole
            self.assertEqual(subprocess.run(lister + ["since", out, "tiny"], capture_output=True, text=True, check=True).stdout, "")
            with open(os.path.join(tmp, "old.json"), "w") as f:
                json.dump({"list": 1, "timetables": [{"id": "zz", "name": "Elsewhere"}, {"id": "tiny", "name": "to be replaced"}]}, f)
            subprocess.run(lister + ["entry", "--id", "tiny", "--name", "Tiny", "--file", tt, "--gz", gz, "--url", "https://example.org/tiny.tt.gz",
                                     "--release", "timetable-20260105", "--feed-url", "https://example.org/gtfs.zip", "--last-modified",
                                     "Mon, 05 Jan 2026 03:00:00 GMT", "--fetched", "2026-01-05T04:00:00Z", "--commit", "abc", "--previous",
                                     os.path.join(tmp, "old.json"), "--out", out], check=True, capture_output=True)
            with open(out) as f:
                data = json.load(f)
            self.assertEqual([e["id"] for e in data["timetables"]], ["tiny", "zz"])
            e = data["timetables"][0]
            self.assertEqual((e["format"], e["first_day"], e["days"], e["last_day"], e["timezone"]), (ttpack.VERSION, "20260105", 7, "20260111", "Europe/Amsterdam"))
            self.assertEqual((e["feed"]["publisher"], e["feed"]["version"], e["feed"]["last_modified"]), ("Atlas tests", "1", "Mon, 05 Jan 2026 03:00:00 GMT"))
            self.assertEqual((e["file"]["name"], e["file"]["bytes"], e["file"]["unpacked"]["bytes"]), ("tiny.tt.gz", os.path.getsize(gz), os.path.getsize(tt)))
            self.assertEqual(len(e["file"]["sha256"]), 64)
            self.assertEqual((e["holds"]["stops"], e["holds"]["trips"], e["holds"]["transfer_rules"]), (10, 10, 2))
            # published by this packer in this format: only a changed feed is wanted
            ask = lambda: subprocess.run(lister + ["since", out, "tiny"], capture_output=True, text=True, check=True).stdout.strip()
            self.assertEqual(ask(), "Mon, 05 Jan 2026 03:00:00 GMT")
            self.assertEqual(subprocess.run(lister + ["since", out, "nl"], capture_output=True, text=True, check=True).stdout, "")
            # by another packer, or with a moment no server would say: whole again
            for change in (lambda e: e["packer"].update(sha256="0" * 64), lambda e: e.update(format=e["format"] + 1),
                           lambda e: e["feed"].update(last_modified='x"; rm -rf /'), lambda e: e.update(packer="none")):
                with open(out) as f:
                    data = json.load(f)
                change(data["timetables"][0])
                with open(os.path.join(tmp, "changed.json"), "w") as f:
                    json.dump(data, f)
                got = subprocess.run(lister + ["since", os.path.join(tmp, "changed.json"), "tiny"], capture_output=True, text=True, check=True).stdout
                self.assertEqual(got, "")
            # a list that is no list: nothing, and no error
            with open(os.path.join(tmp, "bad.json"), "w") as f:
                f.write("<html>not found</html>")
            self.assertEqual(subprocess.run(lister + ["since", os.path.join(tmp, "bad.json"), "tiny"], capture_output=True, text=True, check=True).stdout, "")

    def test_distances_and_walks(self):
        # a tenth of a degree of latitude; the same along the equator; nothing at all
        self.assertEqual(ttpack.metres_mm(520000000, 0, 521000000, 0), 11119500)
        self.assertEqual(ttpack.metres_mm(0, 0, 0, 1000000), 11119500)
        self.assertEqual(ttpack.metres_mm(1, 2, 1, 2), 0)
        # 100 m on foot: 60 s and 104 s (130 m at 1.25 m/s)
        self.assertEqual(ttpack.walk_s(100000), 164)
        self.assertEqual(ttpack.e7("52.3791283"), 523791283)
        self.assertEqual(ttpack.e7("-0.00000005"), -1)
        self.assertEqual(ttpack.secs("25:10:05"), 90605)
        self.assertIsNone(ttpack.secs(""))


if __name__ == "__main__":
    unittest.main()
