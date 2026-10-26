#!/usr/bin/env python3
"""Writes tests/style/opening_hours.json: render/real.py's own open_at over every distinct opening_hours of the bundled
packs and the texts below (each rule of what real.py reads, the hand cases' texts among them), as the Python answers.

Each text is asked on every weekday at the renders' dates (amsterdam.py's THEMES and weather themes, day_frames.py),
at the brief's minutes and the renders' own, and at the minute of each hh:mm the text names and the minute before it
(mod 24 hours: the step and the one before it, past midnight too); a text that names dates is also asked on each of
them and the day after it. An answer is 1 (True), 0 (False) or - (None, and where the Python raises); "open" holds
one string per date, seven groups of the times' answers in it, Mo to Su.

The packs' list comes from tests/style/opening_hours_test.cpp (ATLAS_TILES_DIR=<the bundled tiles> atlas_tests
-tc='*bundled packs*' writes opening_hours_strings.json to ATLAS_TEST_OUT, else the build folder's scratch). Run this
from a folder of its own, isolated, render/ put on the path explicitly:

    python3 -I <this file> <opening_hours_strings.json> <this folder>/opening_hours.json
"""
import datetime
import json
import os
import re
import sys

RENDER = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), *[".."] * 6, "render"))
sys.path.insert(0, RENDER)
from real import DAYS, MONTHS, open_at  # noqa: E402

DATES = [(2026, 9, 30), (2026, 10, 1), (2026, 10, 2)]
TIMES = [(0, 0), (6, 59), (7, 0), (12, 0), (17, 59), (18, 0), (23, 59),  # the brief's
         (13, 30), (16, 0), (17, 20), (21, 30)]  # noon, sunny, golden, night and wet

# each rule of what real.py reads (the hand cases' texts of opening_hours_test.cpp among them)
TEXTS = [
    "", "   ", "by appointment", "Mo-Fr 9-18", "Mo-Fr 109:00-118:00", "mo-fr",
    "24/7", " 24/7\n", "24/7; PH off", "24/7 ",
    "Mo-Fr 09:00-18:00", "Mo-Fr 9:00-18:00", "Mo-Fr 10:00 - 18:00", "Mo-Fr10:00-18:00", "Mo-Fr 10:00-18:000",
    "10:00-18:00", "mo-fr 10:00-18:00", "Mo-Fr 123:00-18:00",
    "Fr-Mo 10:00-12:00", "Mo-Mo 10:00-12:00", "Sa,Su 10:00-12:00", "Mo-We , Fr 10:00-12:00", "Mo-Fr,10:00-12:00",
    "Mo, Xx 10:00-12:00", "Mo-Xx 10:00-12:00", "Monday 10:00-12:00", "Su-Sa 08:00-09:00",
    "Mo-Fr 09:00-12:00,13:00-17:30", "Mo-Fr 09:00 - 12:00 , 13:00 - 17:30", "Mo-Fr 09:00-12:00, Sa 13:00-17:30",
    "Mo-Fr 10:00-18:00 Sa 10:00-14:00", "open Mo-Fr 10:00-18:00 except holidays",
    "Mo-Sa 10:00-18:00; Su off", "Mo-Sa 10:00-18:00; Su closed", "off", "closed", "Mo-Fr 10:00-18:00 off", "Mooff",
    "Mo-Fr 09:00-18:00; We 12:00-13:00", "Mo-Su 09:00-18:00; We off", "We off; Mo-Fr 10:00-18:00",
    "Th 10:00-01:00", "Fr-Sa 18:00-02:00", "Su 20:00-03:00", "22:00-22:00", "00:00-00:00", "Mo 10:00-12:00",
    "Mo 20:00-26:00", "Fr 18:00-02:00; Fr 10:00-12:00", "Fr 18:00-02:00; Sa off", "Mo 25:99-26:00", "Mo 10:00-02:00",
    "Mo-Su 10:00-18:00; Dec 25 off", "Mo-Su 10:00-18:00; 2026 Dec 25 off", "Mo-Su 10:00-18:00; 2027 Dec 25 off",
    "Dec 24 10:00-14:00", "Sep 30 10:00-12:00", "Sep 30 Mo 10:00-12:00", "Sep  30: off",
    "Mo-Su 10:00-18:00; Oct 1st off", "Mo-Su 10:00-18:00; Oct 100 off", "Mo-Su 10:00-18:00; sep 30 off",
    "Mo-Su 10:00-18:00; Sep 30 off", "Sep 30 22:00-02:00", "Mo-Su 08:00-09:00; Sep 30 22:00-02:00",
    "Mo-Su 08:00-09:00; Dec 31 22:00-02:00", "Mo-Su 08:00-09:00; Feb 29 22:00-02:00", "Dec 25 off\nMo 10:00-12:00",
    "20261 Dec 25 off", "Oct 1 Su 10:00-11:00; Oct 2 10:00-11:00",
    "Mo-Fr,PH 10:00-18:00", "PH 10:00-12:00", "Mo-Su 10:00-18:00; PH off", "Sa,PH 10:00-12:00",
    "Sa,PHX 10:00-12:00", "Sa,PH2 10:00-12:00", "Sa,PHé 10:00-12:00", "Sa,PH 10:00-12:00",
    "PH,Sa 10:00-12:00", "Sa,PHPH 10:00-12:00",
    "Mo-Fr 09:00 - 18:00", "Mo-Fr ٠٩:٣٠-١٨:٠٠",
    "Mo-Fr \U0001d7ce\U0001d7d7:\U0001d7d1\U0001d7ce-\U0001d7cf\U0001d7d6:\U0001d7ce\U0001d7ce",
    "Mo-Su 10:00-18:00; Oct 1² off", "Mo-Su 10:00-18:00; Sep 3٠ off",
    "Mo-Su 10:00-18:00; ٢٠٢٦ Sep 30 off", "Mo-Fr 09:00-18:00\nSa 10:00-12:00",
    "Mo-Fr 09:00-18:00 Sa 10:00-12:00", "Mo-Fr 09:00-18:00​", "​Mo-Fr 09:00-18:00",
    "Mo－Fr 09:00-18:00", "Mo-Fr� 09:00-18:00",
]
CLOCK = re.compile(r"(\d{1,2}):(\d{2})")
DATED = re.compile(r"(?:(\d{4})\s+)?(%s)\s+(\d{1,2})" % "|".join(MONTHS))


def answer(oh, day, hhmm, date):
    try:
        open_ = open_at(oh, day, hhmm, date=date)
    except (ValueError, OverflowError):
        return "-"
    return {True: "1", False: "0", None: "-"}[open_]


def times_of(oh):
    """The minute of each hh:mm the text names and the minute before it, mod 24 hours, beyond TIMES."""
    out = set()
    for m in CLOCK.finditer(oh):
        t = int(m.group(1)) * 60 + int(m.group(2))
        out |= {t % 1440, (t - 1) % 1440}
    return sorted({divmod(t, 60) for t in out} - set(TIMES))


def dates_of(oh):
    """Each date the text names (in its year, else 2026, or 2028 for 29 February) and the day after it, beyond DATES."""
    out = set()
    for m in DATED.finditer(oh):
        month, day = MONTHS.index(m.group(2)) + 1, int(m.group(3))
        year = int(m.group(1)) if m.group(1) else 2028 if (month, day) == (2, 29) else 2026
        try:
            d = datetime.date(year, month, day)
        except ValueError:
            continue
        for e in (d, d + datetime.timedelta(days=1)):
            out.add((e.year, e.month, e.day))
    return sorted(out - set(DATES))


def case(oh):
    times, dates = times_of(oh), dates_of(oh)
    one = {"oh": oh}
    if times:
        one["times"] = [list(t) for t in times]
    if dates:
        one["dates"] = [list(d) for d in dates]
    one["open"] = [" ".join("".join(answer(oh, day, t, date) for t in TIMES + times) for day in DAYS)
                   for date in DATES + dates]
    return one


def main():
    packs = json.load(open(sys.argv[1], encoding="utf-8"))
    texts = sorted(set(packs) | set(TEXTS))
    cases = [case(oh) for oh in texts]
    head = {"generator": "tests/style/opening_hours.py", "python": sys.version.split()[0], "packs": len(packs),
            "dates": [list(d) for d in DATES], "times": [list(t) for t in TIMES]}
    with open(sys.argv[2], "w", encoding="utf-8") as f:
        f.write("{" + json.dumps(head)[1:-1] + ',\n "cases": [\n')
        f.write(",\n".join("  " + json.dumps(c) for c in cases))
        f.write("\n ]}\n")


if __name__ == "__main__":
    main()
