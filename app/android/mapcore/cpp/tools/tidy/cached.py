#!/usr/bin/env python3
"""clang-tidy over a compile database's units, each checked only when its key is not among the clean ones kept.

  cached.py --database <dir> --clang-tidy <exe> --keep <dir> [--cache <dir>] [--jobs <n>]
            [--extra-arg-before <arg>]... <source>...

A unit's key is the SHA-256 of what decides clang-tidy's word on it: the clang-tidy's --version, every .clang-tidy from
the source's folder up (their text), the options given here, the unit's compile command and folder, and the unit as
the database's compiler preprocesses it with its comments (-E -C: every header it includes, with the line markers that
name them, and every NOLINT). A unit whose key is in --cache was clean with exactly that and is not checked again; the
others are checked (clang-tidy -p <database> --quiet, the options before), and each clean unit's key, whether from the
cache or checked now, goes into --keep, the cache for the next run (core.yml's tidy jobs keep it between runs). The
core's .clang-tidy makes every warning an error, so clean is clang-tidy's exit 0. Exits 1 when a unit is not clean,
with clang-tidy's report; a unit that does not preprocess is checked, so clang-tidy reports why.

Python 3's standard library only.
"""

import argparse
import concurrent.futures
import hashlib
import json
import os
import shlex
import subprocess
import sys
from pathlib import Path

# Flags of the compile command that name an output, a dependency file or an object to make, left out of the
# preprocessing (which prints to stdout); the ones that take their value as the next argument.
OUTPUT_FLAGS_WITH_VALUE = {"-o", "-MF", "-MT", "-MQ"}
OUTPUT_FLAGS = {"-c", "-MD", "-MMD", "-M", "-MM", "-MG", "-MP"}


def arguments(entry):
    if "arguments" in entry:
        return list(entry["arguments"])
    return shlex.split(entry["command"])


def preprocess_command(args, extra):
    """The compile command made to print the unit preprocessed with its comments."""
    out = [args[0]] + list(extra)
    skip = False
    for arg in args[1:]:
        if skip:
            skip = False
            continue
        if arg in OUTPUT_FLAGS_WITH_VALUE:
            skip = True
            continue
        if arg in OUTPUT_FLAGS or any(arg.startswith(f) and len(arg) > len(f) for f in ("-MF", "-MT", "-MQ")):
            continue
        out.append(arg)
    return out + ["-E", "-C", "-o", "-"]


def configs(source):
    """Every .clang-tidy from the source's folder up, as clang-tidy looks for them."""
    found = []
    folder = Path(source).resolve().parent
    while True:
        candidate = folder / ".clang-tidy"
        if candidate.is_file():
            found.append(f"{candidate}\n".encode() + candidate.read_bytes())
        if folder.parent == folder:
            return found
        folder = folder.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--database", required=True)
    parser.add_argument("--clang-tidy", required=True)
    parser.add_argument("--cache")
    parser.add_argument("--keep", required=True)
    parser.add_argument("--jobs", type=int, default=os.cpu_count() or 1)
    parser.add_argument("--extra-arg-before", action="append", default=[])
    parser.add_argument("sources", nargs="*")
    args = parser.parse_args()

    database = Path(args.database)
    entries = {}  # a source's every command (clang-tidy checks it with each)
    for entry in json.loads((database / "compile_commands.json").read_text(encoding="utf-8")):
        path = Path(entry["directory"], entry["file"]).resolve()
        entries.setdefault(str(path), []).append(entry)
    version = subprocess.run([args.clang_tidy, "--version"], capture_output=True, check=True).stdout
    cached = set()
    if args.cache and Path(args.cache).is_dir():
        cached = {p.name for p in Path(args.cache).iterdir()}
    keep = Path(args.keep)
    keep.mkdir(parents=True, exist_ok=True)
    options = [f"--extra-arg-before={a}" for a in args.extra_arg_before]

    def one(source):
        commands = entries.get(str(Path(source).resolve()))
        if not commands:
            return source, "failed", f"{source}: not in {database / 'compile_commands.json'}\n"
        digest = hashlib.sha256()
        for part in [version, b"\0".join(configs(source)), "\0".join(options).encode()]:
            digest.update(part)
            digest.update(b"\0\0")
        key = None
        for entry in commands:
            command = arguments(entry)
            for part in ["\0".join(command).encode(), entry["directory"].encode()]:
                digest.update(part)
                digest.update(b"\0\0")
            pre = subprocess.run(preprocess_command(command, args.extra_arg_before), cwd=entry["directory"],
                                 capture_output=True)
            if pre.returncode != 0:
                break
            digest.update(pre.stdout)
            digest.update(b"\0\0")
        else:
            key = digest.hexdigest()
            if key in cached:
                (keep / key).touch()
                return source, "cached", ""
        tidy = subprocess.run([args.clang_tidy, "-p", str(database), "--quiet"] + options + [source],
                              capture_output=True)
        report = (tidy.stdout + tidy.stderr).decode(errors="replace")
        if tidy.returncode != 0:
            return source, "failed", report
        if key is not None:
            (keep / key).touch()
        return source, "checked", ""

    counts = {"cached": 0, "checked": 0, "failed": 0}
    failed = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.jobs)) as pool:
        for source, outcome, report in pool.map(one, args.sources):
            counts[outcome] += 1
            if outcome == "failed":
                failed.append(source)
                sys.stdout.write(report)
                print(f"::error::clang-tidy: {source} is not clean (its report above)")
    print(f"clang-tidy: {len(args.sources)} units, {counts['cached']} clean before with the same key, "
          f"{counts['checked']} checked and clean, {counts['failed']} not clean")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
