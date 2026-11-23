#!/usr/bin/env python3
"""The packer's checks: python3 -I app/android/transit/pack/test_ttpack.py

- the hand-written feeds (planner/tests/data/tiny, edges, autumn, waits and keten) pack to the very bytes kept
  beside them (tiny.tt, edges.tt, autumn.tt, waits.tt, keten.tt), twice over: the planner's tests read those files,
  so they can't drift from the packer, and the same feed gives the same bytes;
- the days the clock changes are found from the feed's timezone and written into the file (edges has the night it
  goes forward, autumn the night it goes back);
- the cosine table is the planner's (planner/src/geo.rs), number for number, so both measure alike;
- a table or a column the packer does not read is said, and with --strict refused with nothing written;
- the rules that name a trip or a line and give a least time under what holds without them are counted in the summary;
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
            # (its one least time under the walk, 120 s where the walk takes 271: planner/tests/edges.rs, Tijd)
            self.assertIn(ttpack.UNDER + ": 1\n", self.said)
            self.assertIn(ttpack.NOT_OFFERED + ": 0\n", self.said)
        self.assertIn(b"odd_days=20270327:82800", kept.replace(b"\0", b"="))

    def test_the_autumn_feed_packs_to_the_kept_bytes(self):
        # (planner/tests/autumn.rs: the night the clock goes back, Saturday 30 October 2027 lasts 25 hours)
        with open(os.path.join(DATA, "autumn.tt"), "rb") as f:
            kept = f.read()
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(self.pack(os.path.join(tmp, "a.tt"), "autumn"), kept, "autumn.tt is stale: pack planner/tests/data/autumn again")
            self.assertEqual(self.pack(os.path.join(tmp, "b.tt"), "autumn"), kept)
            self.assertIn("the clock changes: the day after 2027-10-30 begins 90000 s after it", self.said)
        self.assertIn(b"odd_days=20271030:90000", kept.replace(b"\0", b"="))

    def test_the_waits_feed_packs_to_the_kept_bytes(self):
        # (planner/tests/many.rs: 45 buses in a row before one that waits for them, by a rule that names its line)
        with open(os.path.join(DATA, "waits.tt"), "rb") as f:
            kept = f.read()
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(self.pack(os.path.join(tmp, "a.tt"), "waits"), kept, "waits.tt is stale: pack planner/tests/data/waits again")
            self.assertEqual(self.pack(os.path.join(tmp, "b.tt"), "waits"), kept)
            self.assertIn("47 trips, 0 walks, 1 transfer rules", self.said)

    def test_the_keten_feed_packs_to_the_kept_bytes(self):
        # (planner/tests/chains.rs: five islands on which one rule of the feed leads to the next)
        with open(os.path.join(DATA, "keten.tt"), "rb") as f:
            kept = f.read()
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(self.pack(os.path.join(tmp, "a.tt"), "keten"), kept, "keten.tt is stale: pack planner/tests/data/keten again")
            self.assertEqual(self.pack(os.path.join(tmp, "b.tt"), "keten"), kept)
            self.assertIn("30 trips, 18 walks, 11 transfer rules", self.said)

    def test_the_summary_says_what_a_planners_index_takes(self):
        # (the planner says the same five numbers of the same five files, and holds what it asks for to them:
        # planner/tests/memory.rs, an_index_takes_what_was_said_before_it_was_made)
        with tempfile.TemporaryDirectory() as tmp:
            for feed, takes in (("tiny", 352), ("edges", 1704), ("autumn", 152), ("waits", 160), ("keten", 1432)):
                self.pack(os.path.join(tmp, feed + ".tt"), feed)
                self.assertIn("a planner's index: %d bytes\n" % takes, self.said, feed)
        # by hand, of a file with 100 stops, 7 walks, 30 stops along patterns, 5 profiles and a day the clock changes:
        # the stops by latitude and where each one's walks back begin, the walks back, a bit a stop for the rules
        # from and to, a number a stop along a pattern, a number a profile, and the day
        bare = 4 * 100 + 4 * 101 + 8 * 7 + 2 * 8 * 2 + 4 * 30 + 4 * 5 + 8
        index = lambda *rules: ttpack.index_bytes(100, 7, 30, 5, rules, 1) - bare
        N = ttpack.NONE
        self.assertEqual(index(), 0)
        # rules for everyone: nothing, but where one gives a stop its own time to change, a number a stop
        self.assertEqual(index((1, 2, N, N, N, N, 2, 90), (3, 3, N, N, N, N, 0, N), (3, 3, N, N, N, N, 2, N), (4, 5, N, N, N, N, 3, N)), 0)
        self.assertEqual(index((3, 3, N, N, N, N, 2, 90)), 400)
        self.assertEqual(index((3, 3, N, N, N, N, 3, N), (4, 4, N, N, N, N, 2, 0)), 400)
        # a rule that names a line or a trip: its number; one that binds (types 1 to 4), each side by what it names
        self.assertEqual(index((1, 2, 7, N, N, N, 0, N)), 4)
        self.assertEqual(index((1, 2, 7, N, N, N, 5, N)), 4)
        self.assertEqual(index((1, 2, 7, N, N, N, 3, N)), 4 + 8 + 4)
        self.assertEqual(index((1, 2, N, 7, N, 9, 2, 60)), 4 + 4 + 8)
        self.assertEqual(index((1, 2, 7, 7, 9, 9, 2, 60)), 4 + 8 + 8)
        # one by which a vehicle waits (types 1 and 4): that again, what the change takes at each end, and between
        # two stops the pair each way and, once, a bit a stop each way
        self.assertEqual(index((2, 2, 7, 8, N, N, 1, N)), 4 + 2 * 16 + 24)
        self.assertEqual(index((1, 2, 7, N, N, N, 4, N)), 4 + 2 * 12 + 24 + 16 + 32)
        self.assertEqual(index((1, 2, 7, N, N, N, 4, N), (5, 6, N, N, 9, 9, 1, N)), 4 + 2 * 12 + 24 + 16 + 4 + 2 * 16 + 24 + 16 + 32)
        # no more than 64 days the clock changes are kept
        self.assertEqual(ttpack.index_bytes(100, 7, 30, 5, (), 400) - bare, 8 * 63)

    def test_a_file_whose_index_would_pass_the_planners_bound_is_not_written(self):
        self.assertEqual(ttpack.INDEX_MOST, 256 << 20)
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "out.tt")
            run = lambda *more: subprocess.run([sys.executable, "-I", os.path.join(HERE, "ttpack.py"), os.path.join(DATA, "tiny"), out, *more], capture_output=True, text=True)
            r = run("--index-most", "351")
            self.assertNotEqual(r.returncode, 0)
            self.assertIn("a planner's index for this file would take 352 bytes of memory, and 351 are allowed", r.stderr)
            self.assertIn("Nothing was written.", r.stderr)
            self.assertEqual(os.listdir(tmp), [])
            r = run("--index-most", "352")
            self.assertEqual(r.returncode, 0, r.stderr)
            with open(out, "rb") as f, open(os.path.join(DATA, "tiny.tt"), "rb") as kept:
                self.assertEqual(f.read(), kept.read())
            # and without a word it is the planner's own bound, which the usage says
            usage = subprocess.run([sys.executable, "-I", os.path.join(HERE, "ttpack.py"), "--help"], capture_output=True, text=True).stdout
            self.assertIn(str(ttpack.INDEX_MOST), usage)

    def test_a_least_time_under_what_holds_without_it_is_counted(self):
        # A rule that names a trip or a line and gives a least time is held to the longer of that and what holds
        # without it (the stop's own time to change, the walk between the two stops), and where nobody changes or no
        # walk leads the change is not offered: GTFS has the more specific rule count. The summary says how many of
        # each a feed has, 0 too. The edges feed has one; here it gets seven more, of which five count.
        import shutil

        more = [
            "SS,SS,S1,S2,,,2,120",  # the stop's own time is 300 s: under
            "SS,SS,S1,S2,,,2,300",  # the same: not under
            "MS,MS,M1,M2,,,2,30",  # no time of its own, so the file's 60 s: under
            "MS,MS,M1,M2,,,2,60",  # the same: not under
            "TB,TC,T1,T2,,,2,270",  # the walk takes 271 s: under
            "TB,TC,T1,T2,,,2,271",  # the same: not under
            "ZB,ZC,Z1,Z2,,,2,90",  # no walk leads from ZB to ZC: not offered
            "HS,HS,,,,,3,",  # nobody changes at HS,
            "HS,HS,H1,H2,,,2,600",  # so this change is not offered
        ]
        with tempfile.TemporaryDirectory() as tmp:
            feed = os.path.join(tmp, "edges")
            shutil.copytree(os.path.join(DATA, "edges"), feed)
            with open(os.path.join(feed, "transfers.txt"), "a") as f:
                f.write("\n".join(more) + "\n")
            r = subprocess.run([sys.executable, "-I", os.path.join(HERE, "ttpack.py"), feed, os.path.join(tmp, "out.tt")], check=True, capture_output=True, text=True)
            self.assertIn("8 walks, 20 transfer rules", r.stdout)
            self.assertIn(ttpack.UNDER + ": 4\n", r.stdout)
            self.assertIn(ttpack.NOT_OFFERED + ": 2\n", r.stdout)
        # the feeds without such a rule say 0 of each
        for feed in ("tiny", "autumn", "waits"):
            with tempfile.TemporaryDirectory() as tmp:
                self.pack(os.path.join(tmp, "a.tt"), feed)
                self.assertIn(ttpack.UNDER + ": 0\n", self.said)
                self.assertIn(ttpack.NOT_OFFERED + ": 0\n", self.said)

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
