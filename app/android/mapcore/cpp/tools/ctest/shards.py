#!/usr/bin/env python3
"""The core's ctest entries split into shards, balanced by their measured cost (core.yml's host jobs).

  shards.py plan --tests <ctest --show-only=json-v1> --job <job> --shards <n> --shard <k> --out <file>
                 [--slots <j>] [--costs <costs.tsv>] [--later-runs <run>,...] [--cost-data <CTestCostData.txt>]
                 [--later-cost-data <dir>] [--every <file>]
      The entries of shard k (1..n) of job <job> (a column of costs.tsv, host-debug-x86_64 say), one name a line, for
      ctest --tests-from-file. Each shard runs its entries in ctest -j <j>, and once more after that for each later
      run (--later-runs: host-debug's run without Vulkan, novk, whose seconds are the column <job>:novk), so a shard's
      wall is its runs' added up, each run's the longest of its j slots. The split is the longest-processing-time
      rule: the costliest entry first (its runs' seconds added up), each to the shard with the most room (its runs'
      least loaded slots) among those whose wall it keeps within the longest so far, else to the one whose wall it
      raises least, the lowest on a tie; within a shard, each run's seconds to that run's least loaded slot, as ctest
      -j runs the entries the costliest first, so a shard that holds one long entry takes less beside it. An entry ctest runs alone (RUN_SERIAL: its checks time the runner) holds every slot
      of its shard while it runs, so it is placed first, and the cost file starts it before the others, when no slot
      need wait for it. Every shard of the job, computing it from the same build and table, gets the same split. An
      entry a column lacks counts as the column's median; a later run without a column, as nothing.
      It checks that the shards take every entry once, prints each shard's share, and with --cost-data writes ctest's
      own cost file for the first run, so ctest -j starts the shard's longest entries first (with --later-cost-data,
      <dir>/<run>.txt for each later run, to put in its place before that run); with --every, every entry's name (the
      plan the costs command holds the shards' runs to).

  shards.py costs --junit <dir> --into <costs.tsv> [--costs <costs.tsv>] [--summary <file>]
      The table measured again: each job's seconds per entry and run from the JUnit files under <dir>, laid out as
      <dir>/<job>/<shard>/<run>.xml (the run tests in the column <job>, any other in <job>:<run>), each the mean of
      the measured seconds and those of --costs, as the runners' speeds vary from run to run (x86_64's most), with
      the other columns kept from --costs. A case that failed is not a measure: its seconds stay those of --costs.
      Fails when a job's shards did not run each entry of its plan (<dir>/<job>/<shard>/plan.txt, every entry)
      exactly once in each run. With --summary, Markdown tables of each shard's measured seconds and of the entries'
      peak memory, where their output has it (core.yml's test launcher, GNU time).

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
FIRST_RUN = "tests"  # core.yml's first run in each shard (tests.xml); a later run R's seconds are the column <job>:R
# core.yml's test launcher (GNU time) ends each entry's output with this line
RESOURCES = re.compile(r"atlas-test-resources: ([0-9.]+) s, peak ([0-9]+) KB")


def read_costs(path):
    """costs.tsv as {column: {entry: seconds}} and the columns' order; # lines are comments."""
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
        "# Each ctest entry's measured seconds in each of core.yml's host jobs (<job>), and in a job's later run (<job>:",
        "# <run>, host-debug's without Vulkan), from the JUnit files of push runs' shards, each refresh the mean of the",
        "# table's seconds and a run's: tools/ctest/shards.py splits a job's entries into shards by them. Refreshed from",
        "# a run's ctest-costs artifact (ENGINE.md, Checks); an entry missing here counts as its column's median.",
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


def split(names, runs, shards, slots=1, alone=frozenset()):
    """The longest-processing-time rule over the shards' runs (`runs`: each run's {entry: seconds}, one after another
    in each shard), each of `slots` slots: [(load, wall, [names])] per shard, its wall its runs' longest slots added
    up. An entry run alone first holds every slot of its shard's runs."""
    loads = [[[0.0] * slots for _ in runs] for _ in range(shards)]  # shard -> run -> slot -> seconds
    parts = [[] for _ in range(shards)]
    total = {name: sum(seconds[name] for seconds in runs) for name in names}

    def raised(k, name, whole):
        """Shard k's wall with the entry in it."""
        wall = 0.0
        for run, seconds in zip(loads[k], runs):
            wall += max(run) + seconds[name] if whole else max(max(run), min(run) + seconds[name])
        return wall

    for whole, group in ((True, [n for n in names if n in alone]), (False, [n for n in names if n not in alone])):
        for name in sorted(group, key=lambda n: (-total[n], n)):
            # within the longest wall so far, the shard with the most room; past it, the one it raises least
            bound = max(sum(max(run) for run in loads[j]) for j in range(shards))
            k = min(range(shards),
                    key=lambda j: (max(raised(j, name, whole), bound), sum(min(run) for run in loads[j]), j))
            parts[k].append(name)
            for run, seconds in zip(loads[k], runs):
                if whole:
                    for i in range(slots):
                        run[i] += seconds[name]
                else:
                    i = min(range(slots), key=lambda j: (run[j], j))
                    run[i] += seconds[name]
    return [(sum(total[n] for n in part), sum(max(run) for run in loads[k]), part) for k, part in enumerate(parts)]


def cost_file(path, names, seconds, alone):
    """ctest's own file (Testing/Temporary/CTestCostData.txt): "<name> <runs> <seconds>" lines, then "---" and the
    names that failed last time; ctest -j starts the costliest first, and so the entries run alone first."""
    top = 2 * max(seconds.values()) + 1
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(f"{name} 1 {seconds[name] + (top if name in alone else 0):.2f}\n" for name in names)
                    + "---\n", encoding="utf-8")


def plan(args):
    names, alone = read_tests(args.tests)
    _, costs = read_costs(args.costs)
    later = [run for run in (args.later_runs or "").split(",") if run]
    columns = [args.job] + [f"{args.job}:{run}" for run in later]
    runs = []
    unknown = []
    for n, column in enumerate(columns):
        measured = costs.get(column, {})
        if n > 0 and not measured:
            runs.append({name: 0.0 for name in names})  # a later run not measured yet: planned as the first alone
            continue
        default = statistics.median(measured.values()) if measured else DEFAULT_SECONDS
        runs.append({name: measured.get(name, default) for name in names})
        if n == 0:
            unknown = [name for name in names if name not in measured]
            first_default = default
    if not 1 <= args.shard <= args.shards:
        sys.exit(f"shards.py: shard {args.shard} of {args.shards}?")
    parts = split(names, runs, args.shards, args.slots, alone)
    taken = [name for _, _, part in parts for name in part]
    if sorted(taken) != sorted(names):
        sys.exit("shards.py: the shards do not take every entry exactly once")
    then = "" if not later else f", then its run{'s' if len(later) > 1 else ''} {', '.join(later)} (the walls added up)"
    print(f"{args.job}: {len(names)} entries in {args.shards} shards of {args.slots} slots by the measured seconds"
          f"{then} ({len(unknown)} not measured, counted as {first_default:.1f} s each)")
    for k, (load, wall, part) in enumerate(parts, start=1):
        longest = max((runs[0][n] for n in part), default=0.0)
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
        cost_file(args.cost_data, names, runs[0], alone)
    if args.later_cost_data:
        for run, seconds in zip(later, runs[1:]):
            cost_file(Path(args.later_cost_data) / f"{run}.txt", names, seconds, alone)


def costs(args):
    jobs, table = read_costs(args.costs)
    root = Path(args.junit)
    summary = ["| job | shard | entries | the entries' seconds (the runs added up) | each run's wall | the longest entry |",
               "|---|---|---|---|---|---|"]
    peaks = {}  # job -> entry -> the largest peak in KB over its runs
    problems = []
    for job_dir in sorted(p for p in root.iterdir() if p.is_dir()):
        job = job_dir.name
        measured = {}  # run -> entry -> seconds, of the cases that passed
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
                suite = ET.parse(junit).getroot()
                walls.append(f"{run} {float(suite.get('time') or 0):.0f} s")
                for case in suite.iter("testcase"):
                    name = case.get("name")
                    seconds = float(case.get("time") or 0.0)
                    for found in RESOURCES.finditer("".join(case.itertext())):
                        kb = int(found.group(2))
                        peaks.setdefault(job, {})[name] = max(kb, peaks.get(job, {}).get(name, 0))
                    ran.setdefault(run, {})
                    ran[run][name] = ran[run].get(name, 0) + 1
                    if case.get("status") != "fail" and case.find("failure") is None:
                        measured.setdefault(run, {})[name] = measured.get(run, {}).get(name, 0.0) + seconds
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
        for run, seconds in sorted(measured.items(), key=lambda item: (item[0] != FIRST_RUN, item[0])):
            column = job if run == FIRST_RUN else f"{job}:{run}"
            if column not in jobs:
                jobs.append(column)
            kept = table.get(column, {})
            # the mean of the table's seconds and this run's; an entry this run did not measure keeps the table's
            blended = dict(kept)
            for name, value in seconds.items():
                blended[name] = (kept[name] + value) / 2 if name in kept else value
            if planned is not None:
                blended = {name: value for name, value in blended.items() if name in planned}
            table[column] = blended
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
    p.add_argument("--later-runs")
    p.add_argument("--out", required=True)
    p.add_argument("--cost-data")
    p.add_argument("--later-cost-data")
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
