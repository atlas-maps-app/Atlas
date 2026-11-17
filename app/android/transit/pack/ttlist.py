#!/usr/bin/env python3
"""The list of published timetable files: what .github/workflows/timetable.yml writes beside each file it publishes.

    python3 -I ttlist.py since <timetable.json or a path that is not there> <id>
        prints the moment the feed of the published file was last changed, as the feed's server said it, for an
        If-Modified-Since: or nothing, when no file of this id is published or it was made by another packer or in
        another format than this tree's (then the feed is wanted whole, changed or not)

    python3 -I ttlist.py entry --id nl --name Netherlands --file nl.tt --gz nl.tt.gz --url <where the .gz is served>
            --release <tag> --feed-url <url> [--feed <the feed's zip>] [--last-modified <as the server said>]
            [--fetched <ISO time>] [--commit <sha>] [--previous <timetable.json>] --out timetable.json
        writes the list with this file's entry: the feed (publisher, version, licence, where and when it was fetched),
        the days the file covers, its format, its size and SHA-256 as it travels and unpacked, and what it holds.
        Entries of other ids in --previous are kept; this id's is replaced.

The list is JSON: {"list": 1, "timetables": [entry, ...]}, entries by id. Nothing in the app reads it yet.
Python 3's standard library only.
"""

import argparse
import hashlib
import json
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
LIST_VERSION = 1


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def packer_sum():
    """What made the file: the packer's own bytes. A file is made anew when they change, whatever the feed."""
    return sha256(os.path.join(HERE, "ttpack.py"))


def read_file(path):
    """A timetable file's format version, its META as a dict, and its sections' record counts by tag."""
    with open(path, "rb") as f:
        head = f.read(16)
        if len(head) < 16 or head[:8] != b"ATLASTT\0":
            sys.exit("%s: not a timetable file" % path)
        version, n = struct.unpack("<II", head[8:16])
        if n > 4096:
            sys.exit("%s: not a timetable file" % path)
        table = f.read(16 * n)
        counts, meta = {}, {}
        for i in range(n):
            tag, off, length, count = struct.unpack_from("<4sIII", table, 16 * i)
            counts[tag.decode("ascii", "replace")] = count
            if tag == b"META":
                f.seek(off)
                for line in f.read(length).decode("utf-8", "replace").splitlines():
                    k, eq, v = line.partition("=")
                    if eq:
                        meta[k] = v
    return version, meta, counts


def last_day(first, days):
    import datetime

    d = datetime.date(int(first[0:4]), int(first[4:6]), int(first[6:8])) + datetime.timedelta(days=days - 1)
    return d.strftime("%Y%m%d")


def previous_entries(path):
    if not path or not os.path.exists(path):
        return []
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return []
    if not isinstance(data, dict) or data.get("list") != LIST_VERSION or not isinstance(data.get("timetables"), list):
        return []
    return [e for e in data["timetables"] if isinstance(e, dict) and isinstance(e.get("id"), str)]


def since(args):
    for e in previous_entries(args.previous):
        if e["id"] != args.id:
            continue
        packer = e.get("packer") if isinstance(e.get("packer"), dict) else {}
        same = packer.get("sha256") == packer_sum() and e.get("format") == current_format()
        stamp = e["feed"].get("last_modified", "") if isinstance(e.get("feed"), dict) else ""
        # only what a server could have said: an HTTP date is letters, digits, spaces, a comma and colons
        if same and isinstance(stamp, str) and 0 < len(stamp) <= 40 and all(c.isalnum() or c in " ,:+-" for c in stamp):
            print(stamp)
        return


def current_format():
    sys.path.insert(0, HERE)
    import ttpack

    return ttpack.VERSION


def entry(args):
    version, meta, counts = read_file(args.file)
    if version != current_format():
        sys.exit("%s is format %d; this tree's packer writes %d" % (args.file, version, current_format()))
    try:
        days = int(meta["days"])
        first = meta["first_day"]
        int(first)
    except (KeyError, ValueError):
        sys.exit("%s: META has no first_day and days" % args.file)
    feed = {
        "publisher": meta.get("feed_publisher_name", ""),
        "version": meta.get("feed_version", ""),
        "licence": meta.get("licence:*", ""),
        "url": args.feed_url,
    }
    if args.last_modified:
        feed["last_modified"] = args.last_modified
    if args.fetched:
        feed["fetched"] = args.fetched
    if args.feed:
        feed["bytes"] = os.path.getsize(args.feed)
        feed["sha256"] = sha256(args.feed)
    mine = {
        "id": args.id,
        "name": args.name,
        "release": args.release,
        "url": args.url,
        "format": version,
        "feed": feed,
        "first_day": first,
        "days": days,
        "last_day": last_day(first, days),
        "timezone": meta.get("timezone", ""),
        "file": {
            "name": os.path.basename(args.gz),
            "bytes": os.path.getsize(args.gz),
            "sha256": sha256(args.gz),
            "unpacked": {"bytes": os.path.getsize(args.file), "sha256": sha256(args.file)},
        },
        "holds": {
            "stops": counts.get("STOP", 0),
            "operators": counts.get("AGCY", 0),
            "lines": counts.get("LINE", 0),
            "patterns": counts.get("PATN", 0),
            "trips": counts.get("TRIP", 0),
            "walks": counts.get("XFER", 0),
            "transfer_rules": counts.get("TRUL", 0),
        },
        "packer": {"sha256": packer_sum()},
    }
    if args.commit:
        mine["packer"]["commit"] = args.commit
    entries = [e for e in previous_entries(args.previous) if e["id"] != args.id] + [mine]
    entries.sort(key=lambda e: e["id"])
    tmp = args.out + ".part"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump({"list": LIST_VERSION, "timetables": entries}, f, indent=2, sort_keys=True, ensure_ascii=False)
        f.write("\n")
    os.replace(tmp, args.out)
    print("%s: %s of %s, feed version %s, %s to %s (%d days), format %d, %d bytes as it travels (%d unpacked), sha256 %s" % (
        args.id, mine["file"]["name"], feed["publisher"] or "?", feed["version"] or "?", first, mine["last_day"], days, version,
        mine["file"]["bytes"], mine["file"]["unpacked"]["bytes"], mine["file"]["sha256"]))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("since")
    s.add_argument("previous")
    s.add_argument("id")
    s.set_defaults(run=since)
    e = sub.add_parser("entry")
    for name in ("--id", "--name", "--file", "--gz", "--url", "--release", "--feed-url", "--out"):
        e.add_argument(name, required=True)
    for name in ("--feed", "--last-modified", "--fetched", "--commit", "--previous"):
        e.add_argument(name, default="")
    e.set_defaults(run=entry)
    args = ap.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
