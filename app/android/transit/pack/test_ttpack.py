#!/usr/bin/env python3
"""The packer's checks: python3 -I app/android/transit/pack/test_ttpack.py

- the hand-written feed (planner/tests/data/tiny) packs to the very bytes kept beside it (tiny.tt), twice over: the
  planner's tests read that file, so it can't drift from the packer, and the same feed gives the same bytes;
- the cosine table is the planner's (planner/src/geo.rs), number for number, so both measure alike.

Python 3's standard library only.
"""

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
    def pack(self, out):
        subprocess.run([sys.executable, "-I", os.path.join(HERE, "ttpack.py"), os.path.join(DATA, "tiny"), out], check=True, capture_output=True)
        with open(out, "rb") as f:
            return f.read()

    def test_the_tiny_feed_packs_to_the_kept_bytes(self):
        with open(os.path.join(DATA, "tiny.tt"), "rb") as f:
            kept = f.read()
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(self.pack(os.path.join(tmp, "a.tt")), kept, "tiny.tt is stale: pack planner/tests/data/tiny again")
            self.assertEqual(self.pack(os.path.join(tmp, "b.tt")), kept)

    def test_the_cosine_table_is_the_planners(self):
        with open(os.path.join(HERE, "..", "planner", "src", "geo.rs"), encoding="utf-8") as f:
            src = f.read()
        body = re.search(r"COS_TABLE: \[u32; 901\] = \[(.*?)\];", src, re.S).group(1)
        self.assertEqual([int(v) for v in re.findall(r"\d+", body)], ttpack.COS_TABLE)

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
