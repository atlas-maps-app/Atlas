#!/usr/bin/env python3
"""The core's ctest entries split into shards, balanced by their measured cost (core.yml's host jobs).

  shards.py plan --tests <ctest --show-only=json-v1> --job <job> --shards <n> --shard <k> --out <file>
                 [--slots <j>] [--costs <costs.tsv>] [--cost-data <CTestCostData.txt>] [--every <file>]
      The entries of shard k (1..n) of job <job> (a column of costs.tsv, host-debug-x86_64 say), one name a line, for
      ctest --tests-from-file. The split is the longest-processing-time rule over the measured seconds and the shards'
      ctest slots (n times j, ctest -j <j>): the costliest entry first, each to the slot with the least so far (the
      lowest on a tie), as ctest -j runs a shard's entries the costliest first, so a shard that holds one long entry
      takes less beside it. An entry ctest runs alone (RUN_SERIAL: its checks time the runner) holds every slot of its
      shard while it runs, so it is placed first, and the cost file starts it before the others, when no slot need
      wait for it. Every shard of the job, computing it from the same build and table, gets the same split. An entry
      the table lacks counts as the column's median.
      It checks that the shards take every entry once, prints each shard's share, and with --cost-data writes ctest's
      own cost file, so ctest -j starts the shard's longest entries first; with --every, every entry's name (the
      plan the costs command holds the shards' runs to).

  shards.py costs --junit <dir> --into <costs.tsv> [--costs <costs.tsv>] [--summary <file>]
      The table measured again: each job's seconds per entry from the JUnit files under <dir>, laid out as
      <dir>/<job>/<shard>/<run>.xml (a job's runs, Vulkan's and the one without, added up), its other jobs' columns
      kept from --costs. Fails when a job's shards did not run each entry of its plan (<dir>/<job>/<shard>/plan.txt,
      every entry) exactly once in each run. With --summary, Markdown tables of each shard's measured seconds and of
      the entries' peak memory, where their output has it (core.yml's test launcher, GNU time).

Python 3's standard library only; no network.
"""

import argparse
import json
import re
import statistics
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

DEFAULT_SECONDS = 60.0  # an entry of a job the table has no column for
# core.yml's test launcher (GNU time) ends each entry's output with this line
RESOURCES = re.compile(r"atlas-test-resources: ([0-9.]+) s, peak ([0-9]+) KB")


def read_costs(path):
    """costs.tsv as {job: {entry: seconds}} and the jobs' order; # lines are comments."""
    jobs, costs = [], {}
    if path is None or not Path(path).exists():
        return jobs, costs
    header = None
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split("\t")
        if header is None:
            header = fields
            jobs = header[1:]
            costs = {job: {} for job in jobs}
            continue
        for job, value in zip(jobs, fields[1:]):
            if value not in ("", "-"):
                costs[job][fields[0]] = float(value)
    return jobs, costs


def write_costs(path, jobs, costs):
    entries = sorted({entry for job in jobs for entry in costs.get(job, {})})
    lines = [
        "# Each ctest entry's measured seconds in each of core.yml's host jobs (Vulkan's run and the one without added",
        "# up), from the JUnit files of a push run's shards: tools/ctest/shards.py splits a job's entries into shards by",
        "# them. Refreshed from a run's ctest-costs artifact (ENGINE.md, Checks); an entry missing here counts as its",
        "# job's median.",
        "\t".join(["entry"] + jobs),
    ]
    for entry in entries:
        row = [entry]
        for job in jobs:
            value = costs.get(job, {}).get(entry)
            row.append("-" if value is None else f"{value:.1f}")
        lines.append("\t".join(row))
    Path(path).write_text("\n".join(lines) + "\n", encoding="utf-8")


def read_tests(path):
    """The entries' names from ctest --show-only=json-v1, in ctest's order, and those ctest runs alone (RUN_SERIAL)."""
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    names = [test["name"] for test in data.get("tests", [])]
    alone = {test["name"] for test in data.get("tests", [])
             if any(p.get("name") == "RUN_SERIAL" and p.get("value") for p in test.get("properties", []))}
    if len(set(names)) != len(names):
        sys.exit("shards.py: ctest lists an entry twice")
    if not names:
        sys.exit("shards.py: ctest lists no entry")
    return names, alone


def split(names, seconds, shards, slots=1, alone=frozenset()):
    """The longest-processing-time rule over shards * slots slots: [(load, wall, [names])] per shard, its wall the
    longest of its slots. An entry run alone first holds every slot of the shard it goes to."""
    loads = [0.0] * (shards * slots)
    parts = [[] for _ in range(shards)]
    for name in sorted((n for n in names if n in alone), key=lambda n: (-seconds[n], n)):
        k = min(range(shards), key=lambda j: (max(loads[j * slots:(j + 1) * slots]), j))
        parts[k].append(name)
        for i in range(k * slots, (k + 1) * slots):
            loads[i] += seconds[name]
    for name in sorted((n for n in names if n not in alone), key=lambda n: (-seconds[n], n)):
        i = min(range(len(loads)), key=lambda j: (loads[j], j))
        parts[i // slots].append(name)
        loads[i] += seconds[name]
    return [(sum(seconds[n] for n in part), max(loads[k * slots:(k + 1) * slots]), part)
            for k, part in enumerate(parts)]


def plan(args):
    names, alone = read_tests(args.tests)
    _, costs = read_costs(args.costs)
    measured = costs.get(args.job, {})
    default = statistics.median(measured.values()) if measured else DEFAULT_SECONDS
    seconds = {name: measured.get(name, default) for name in names}
    unknown = [name for name in names if name not in measured]
    if not 1 <= args.shard <= args.shards:
        sys.exit(f"shards.py: shard {args.shard} of {args.shards}?")
    parts = split(names, seconds, args.shards, args.slots, alone)
    taken = [name for _, _, part in parts for name in part]
    if sorted(taken) != sorted(names):
        sys.exit("shards.py: the shards do not take every entry exactly once")
    print(f"{args.job}: {len(names)} entries in {args.shards} shards of {args.slots} slots by the measured seconds"
          f" ({len(unknown)} not measured, counted as {default:.1f} s each)")
    for k, (load, wall, part) in enumerate(parts, start=1):
        longest = max((seconds[n] for n in part), default=0.0)
        mark = " <- this one" if k == args.shard else ""
        print(f"  shard {k}/{args.shards}: {len(part)} entries, {load:.0f} s in all, about {wall:.0f} s in its slots,"
              f" the longest {longest:.0f} s{mark}")
    if unknown and len(unknown) <= 20:
        print("  not measured yet: " + ", ".join(unknown))
    if alone:
        print("  run alone, first: " + ", ".join(sorted(alone)))
    mine = parts[args.shard - 1][2]
    Path(args.out).write_text("".join(f"{name}\n" for name in mine), encoding="utf-8")
    if args.every:
        Path(args.every).parent.mkdir(parents=True, exist_ok=True)
        Path(args.every).write_text("".join(f"{name}\n" for name in names), encoding="utf-8")
    if args.cost_data:
        # ctest's own file (Testing/Temporary/CTestCostData.txt): "<name> <runs> <seconds>" lines, then "---" and
        # the names that failed last time; ctest -j starts the costliest first, and so the entries run alone first.
        top = 2 * max(seconds.values()) + 1
        path = Path(args.cost_data)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("".join(f"{name} 1 {seconds[name] + (top if name in alone else 0):.2f}\n" for name in names)
                        + "---\n", encoding="utf-8")


def costs(args):
    jobs, table = read_costs(args.costs)
    root = Path(args.junit)
    summary = ["| job | shard | entries | the entries' seconds (the runs added up) | each run's wall | the longest entry |",
               "|---|---|---|---|---|---|"]
    peaks = {}  # job -> entry -> the largest peak in KB over its runs
    problems = []
    for job_dir in sorted(p for p in root.iterdir() if p.is_dir()):
        job = job_dir.name
        measured = {}
        planned = None
        ran = {}  # run -> entry -> times run
        runs_of = {}  # shard -> the runs it kept
        for shard_dir in sorted(p for p in job_dir.iterdir() if p.is_dir()):
            runs_of[shard_dir.name] = {junit.stem for junit in shard_dir.glob("*.xml")}
            plan_file = shard_dir / "plan.txt"
            if plan_file.exists():
                every = [line for line in plan_file.read_text(encoding="utf-8").split("\n") if line]
                if planned is None:
                    planned = every
                elif every != planned:
                    problems.append(f"{job}: shard {shard_dir.name}'s build lists other entries than another shard's")
            shard_seconds, longest = 0.0, ("", 0.0)
            count = 0
            walls = []
            for junit in sorted(shard_dir.glob("*.xml")):
                run = junit.stem
                root = ET.parse(junit).getroot()
                walls.append(f"{run} {float(root.get('time') or 0):.0f} s")
                for case in root.iter("testcase"):
                    name = case.get("name")
                    seconds = float(case.get("time") or 0.0)
                    for found in RESOURCES.finditer("".join(case.itertext())):
                        kb = int(found.group(2))
                        peaks.setdefault(job, {})[name] = max(kb, peaks.get(job, {}).get(name, 0))
                    ran.setdefault(run, {})
                    ran[run][name] = ran[run].get(name, 0) + 1
                    measured[name] = measured.get(name, 0.0) + seconds
                    shard_seconds += seconds
                    count += 1
                    if seconds > longest[1]:
                        longest = (name, seconds)
            summary.append(f"| {job} | {shard_dir.name} | {count} | {shard_seconds:.0f} | {', '.join(walls)} |"
                           f" {longest[0]} {longest[1]:.0f} s |")
        if planned is None:
            problems.append(f"{job}: no shard kept its plan (plan.txt)")
        else:
            every_run = set().union(*runs_of.values())
            for shard, runs in sorted(runs_of.items()):
                for run in sorted(every_run - runs):
                    problems.append(f"{job}: shard {shard} kept no {run} run (did its tests fail before it?)")
            for run, counts in sorted(ran.items()):
                if any(run not in runs for runs in runs_of.values()):
                    continue  # its shards' entries are not all there to hold to the plan
                missing = [name for name in planned if counts.get(name, 0) == 0]
                twice = [name for name, n in counts.items() if n > 1]
                extra = [name for name in counts if name not in planned]
                if missing:
                    problems.append(f"{job} ({run}): {len(missing)} entries ran in no shard: " + ", ".join(missing))
                if twice:
                    problems.append(f"{job} ({run}): entries ran in more than one shard: " + ", ".join(twice))
                if extra:
                    problems.append(f"{job} ({run}): entries ran that the plan lacks: " + ", ".join(extra))
        if job not in jobs:
            jobs.append(job)
        table[job] = measured
    write_costs(args.into, jobs, table)
    summary += ["", "| job | the largest peaks of memory, by entry |", "|---|---|"]
    for job, by_entry in sorted(peaks.items()):
        largest = sorted(by_entry.items(), key=lambda item: (-item[1], item[0]))[:8]
        summary.append(f"| {job} | " + ", ".join(f"{name} {kb / 1024:.0f} MB" for name, kb in largest) + " |")
    if args.summary:
        Path(args.summary).write_text("\n".join(summary) + "\n", encoding="utf-8")
    print("\n".join(summary))
    if problems:
        sys.exit("shards.py: " + "\n".join(problems))
    print(f"every job's shards ran each entry of its plan once; the table measured again: {args.into}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("plan")
    p.add_argument("--tests", required=True)
    p.add_argument("--costs")
    p.add_argument("--job", required=True)
    p.add_argument("--shards", type=int, required=True)
    p.add_argument("--shard", type=int, required=True)
    p.add_argument("--slots", type=int, default=1)
    p.add_argument("--out", required=True)
    p.add_argument("--cost-data")
    p.add_argument("--every")
    p.set_defaults(run=plan)
    c = sub.add_parser("costs")
    c.add_argument("--junit", required=True)
    c.add_argument("--into", required=True)
    c.add_argument("--costs")
    c.add_argument("--summary")
    c.set_defaults(run=costs)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
