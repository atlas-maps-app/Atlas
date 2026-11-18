#!/usr/bin/env python3
"""The test cases that skipped for data CI does not hand them, from the host jobs' JUnit files (core.yml's core-tests).

  skips.py --junit <dir> [--summary <file>]

A case whose data folder is not set (ATLAS_TILES_DIR, ATLAS_REGIONS_DIR, ATLAS_WORLD_DIR, ...: any ATLAS_*_DIR) says so
in a MESSAGE, "<the folders> is not set: <what is not done here>", returns, and counts as passed. This reads each
shard's first run (<dir>/<job>/<shard>/tests.xml, the run with the job's data; host-debug's later run without Vulkan
has none, by design) and lists each such case once, by the place of its MESSAGE in the source: its entry, its name (as
the line of its seconds prints it, since another thread's log line can break into its TEST CASE line), its message and
the jobs it skipped in. A
case that names any other variable among its folders (ATLAS_LOOK_OUT, ATLAS_PINCH_OUT, the seeds' and the diff's) is
a tool that draws or writes only when asked, not data CI lacks, and is left out. With --summary, the list as Markdown.

Python 3's standard library only; no network.
"""

import argparse
import collections
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

FIRST_RUN = "tests.xml"  # core.yml's first run in each shard, with the job's data
CASE = re.compile(r"^TEST CASE:\s+(.*\S)\s*$")
# the line a case's output ends with (the tests' main): its seconds and its name, whole even where another thread's log
# line broke into the TEST CASE line above
DONE = re.compile(r"^[0-9]+\.[0-9]+ s: (.*\S)\s*$")
MESSAGE = re.compile(r"^(.*?:[0-9]+): MESSAGE: (.*\S)\s*$")
# "<the folders> is not set: ...", "... are not set: ..."
UNSET = re.compile(r"^(.*?) (?:is|are) not set\b")
VARIABLE = re.compile(r"\bATLAS_[A-Z0-9_]+\b")


def data_skip(message):
    """The folders a MESSAGE says are not set, when every variable it names before "not set" is a data folder."""
    unset = UNSET.match(message)
    if unset is None:
        return None
    names = VARIABLE.findall(unset.group(1))
    if not names or not all(name.endswith("_DIR") for name in names):
        return None
    return names


def read(root):
    """(entry, the MESSAGE's file:line) -> {"names", "message", "folders", "jobs"}, and every job read."""
    skips = {}
    jobs = set()
    for junit in sorted(root.rglob(FIRST_RUN)):
        job = junit.parent.parent.name
        jobs.add(job)
        for entry in ET.parse(junit).getroot().iter("testcase"):
            name = ""
            pending = []  # the case's data skips, named once its seconds' line says its name

            def named(case_name):
                for skip in pending:
                    skip["names"][case_name] += 1
                pending.clear()

            for line in "".join(entry.itertext()).splitlines():
                case = CASE.match(line)
                if case is not None:
                    named(name)
                    name = case.group(1)
                    continue
                done = DONE.match(line)
                if done is not None:
                    named(done.group(1))
                    name = ""
                    continue
                said = MESSAGE.match(line)
                if said is None:
                    continue
                folders = data_skip(said.group(2))
                if folders is None:
                    continue
                key = (entry.get("name") or "", said.group(1))
                skip = skips.setdefault(key, {"names": collections.Counter(), "message": said.group(2),
                                              "folders": folders, "jobs": set()})
                skip["jobs"].add(job)
                pending.append(skip)
            named(name)
    return skips, jobs


def cell(text):
    return text.replace("|", "\\|")


def summary(skips, jobs):
    if not jobs:
        return ["No host job's test results were found."]
    if not skips:
        return [f"No test case skipped for a data folder CI does not set, in {len(jobs)} host jobs."]
    folders = sorted({f for s in skips.values() for f in s["folders"]})
    lines = [f"**{len(skips)} test cases skipped for a data folder CI does not set** ({', '.join(folders)}), and count "
             "as passed:", "", "| entry | test case | its message | jobs |", "|---|---|---|---|"]
    rows = sorted((entry, s["names"].most_common(1)[0][0], s) for (entry, _), s in skips.items())
    for entry, case, s in rows:
        where = f"all {len(jobs)}" if s["jobs"] == jobs else ", ".join(sorted(s["jobs"]))
        lines.append(f"| {cell(entry)} | {cell(case)} | {cell(s['message'])} | {where} |")
    return lines


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--junit", required=True)
    parser.add_argument("--summary")
    args = parser.parse_args()
    root = Path(args.junit)
    if not root.is_dir():
        sys.exit(f"skips.py: {root} is no folder")
    skips, jobs = read(root)
    lines = summary(skips, jobs)
    if args.summary:
        Path(args.summary).write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print(f"skips.py: {len(skips)} test cases skipped for data, in {len(jobs)} jobs", file=sys.stderr)


if __name__ == "__main__":
    main()
