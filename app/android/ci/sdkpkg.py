#!/usr/bin/env python3
"""Android SDK packages installed from archives checked against the SHA-1 Google's SDK repository publishes for them,
where sdkmanager unpacks a download as it comes: on 4 November two emulator jobs failed on a system image that came
broken ("Error on ZipFile unknown archive", runs 41744358971 and 41757855839), a few seconds into a 1.7 GB download.

  python3 -I app/android/ci/sdkpkg.py describe <repository xml url> <package path> <listing> [--channel 0] [--prefix p]
      the repository's listing saved at <listing>, and the package's newest revision on the channel and those before
      it (what `sdkmanager --install <path> --channel=<n>` installs) with its archive for Linux x86_64, as revision=,
      url=, file=, size= and sha1= lines, each name after the prefix p
  python3 -I app/android/ci/sdkpkg.py fetch <url> <size|-> <sha1> <file>
      the archive at <file>, checked: one there already that checks out is kept (a cache's), one that does not is said
      and replaced; a download is whole and is made again, up to four times, while what came does not check out
  python3 -I app/android/ci/sdkpkg.py install <file> <listing> <package path> [--channel 0] [--revision r]
      a checked archive unpacked where the package goes in $ANDROID_HOME, in place of what is there, with the
      package.xml sdkmanager writes when it installs the listing's entry for the package (the one describe names, at
      revision r when given), and held to sdkmanager listing the package at that revision: an `sdkmanager --install`
      of the package then finds it there and downloads nothing

Python 3's standard library only, with curl, unzip and the SDK's sdkmanager.
"""

import argparse
import copy
import hashlib
import os
import shutil
import subprocess
import sys
import time
import urllib.parse
import xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import quoteattr

TRIES = 4
# The namespace of the repository element sdkmanager's package.xml files are written in (cmdline-tools 22's).
COMMON = "http://schemas.android.com/repository/android/common/02"


def fail(message):
    print(f"::error::{message}", flush=True)
    sys.exit(1)


def local(tag):
    return tag.rsplit("}", 1)[-1]


def child(element, name):
    return next((c for c in element if local(c.tag) == name), None)


def text(element, name):
    found = child(element, name)
    return (found.text or "").strip() if found is not None else None


def curl(url, out):
    """One whole download of url to out (curl's own retries for a failed connection or an HTTP error); its exit code."""
    return subprocess.run(["curl", "-fsSL", "--retry", "5", "--retry-all-errors", "--retry-delay", "10",
                           "--connect-timeout", "30", "-o", str(out), url]).returncode


def parts(revision):
    """A revision as numbers without its trailing zeros, so that 37.2.0 and 37.2 compare alike."""
    numbers = [int(p) for p in revision.split(".")] if isinstance(revision, str) else \
        [int(text(revision, p)) for p in ("major", "minor", "micro") if text(revision, p) is not None]
    while len(numbers) > 1 and numbers[-1] == 0:
        numbers.pop()
    return tuple(numbers)


def dotted(revision):
    return ".".join(text(revision, p) for p in ("major", "minor", "micro") if text(revision, p) is not None)


def listing(file):
    """A saved repository listing's root and the namespace prefixes it declares."""
    prefixes, root = {}, None
    for event, item in ET.iterparse(file, events=("start-ns", "start")):
        if event == "start-ns":
            prefixes.setdefault(*item)
        elif root is None:
            root = item
    return root, prefixes


def newest(root, path, channel):
    """The listing's entry for path at its newest revision on channel and those before it that has an archive for
    Linux x86_64, with that archive's complete element."""
    best = None
    for package in root.iter():
        if local(package.tag) != "remotePackage" or package.get("path") != path:
            continue
        ref = child(package, "channelRef")
        on = int(ref.get("ref").rsplit("-", 1)[-1]) if ref is not None else 0
        revision = child(package, "revision")
        if on > channel or revision is None or child(revision, "preview") is not None:
            continue
        archives = child(package, "archives")
        for archive in archives if archives is not None else []:
            if text(archive, "host-os") not in (None, "linux") or text(archive, "host-arch") not in (None, "x64"):
                continue
            complete = child(archive, "complete")
            checksum = child(complete, "checksum") if complete is not None else None
            if checksum is None or checksum.get("type", "sha1") != "sha1":
                continue
            if best is None or parts(revision) > parts(child(best[0], "revision")):
                best = (package, complete)
    if best is None:
        fail(f"the listing lists no {path} for Linux x86_64 on channel {channel} or before it")
    return best


def describe(xml_url, path, out, channel, prefix):
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(1, TRIES + 1):
        if curl(xml_url, out) == 0:
            try:
                root, _ = listing(out)
                break
            except ET.ParseError as e:
                print(f"::warning::{xml_url} did not parse ({e}), try {attempt} of {TRIES}", flush=True)
        if attempt == TRIES:
            fail(f"the SDK repository's {xml_url} could not be read")
        time.sleep(30 * attempt)
    package, complete = newest(root, path, channel)
    url = urllib.parse.urljoin(xml_url, text(complete, "url"))
    print(f"{prefix}revision={dotted(child(package, 'revision'))}")
    print(f"{prefix}url={url}")
    print(f"{prefix}file={Path(urllib.parse.urlparse(url).path).name}")
    print(f"{prefix}size={text(complete, 'size')}")
    print(f"{prefix}sha1={text(complete, 'checksum')}")


def checked(file, size, sha1):
    """Why file does not check out against its size and SHA-1, or None when it does."""
    if size != "-" and file.stat().st_size != int(size):
        return f"{file.stat().st_size} bytes of {size}"
    digest = hashlib.sha1()
    with open(file, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return None if digest.hexdigest() == sha1 else f"SHA-1 {digest.hexdigest()}, not {sha1}"


def fetch(url, size, sha1, file):
    file = Path(file)
    file.parent.mkdir(parents=True, exist_ok=True)
    if file.exists():
        why = checked(file, size, sha1)
        if why is None:
            print(f"{file.name}: kept, it checks out (SHA-1 {sha1})", flush=True)
            return
        print(f"::warning::{file.name} was there but does not check out ({why}): it is downloaded again", flush=True)
        file.unlink()
    part = file.with_name(file.name + ".part")
    for attempt in range(1, TRIES + 1):
        part.unlink(missing_ok=True)
        start = time.monotonic()
        code = curl(url, part)
        why = f"curl exited {code}" if code != 0 else checked(part, size, sha1)
        if why is None:
            part.rename(file)
            print(f"::notice::{file.name}: fetched from {url} in {time.monotonic() - start:.0f} s, it checks out (SHA-1 {sha1})",
                  flush=True)
            return
        if attempt == TRIES:
            part.unlink(missing_ok=True)
            fail(f"{url} did not come whole in {TRIES} downloads (the last: {why})")
        print(f"::warning::{file.name}: download {attempt} of {TRIES} did not check out ({why}), again in {30 * attempt} s",
              flush=True)
        time.sleep(30 * attempt)


def package_xml(root, prefixes, package, revision):
    """The package.xml sdkmanager writes when it installs the listing's entry package: the entry as a localPackage
    (at revision when given), without its channel and archives, after the licence it uses, in a repository element
    declaring the listing's prefixes, which the type its type-details' xsi:type names is written in."""
    prefixes = {p: u for p, u in prefixes.items() if p}
    name = next((p for p, u in prefixes.items() if u == COMMON), None)
    if name is None:
        name = next(n for n in ("common", "common02", "sdkpkg-common") if n not in prefixes)
        prefixes[name] = COMMON
    uses = child(package, "uses-license")
    licence = next((c for c in root if local(c.tag) == "license" and c.get("id") == uses.get("ref")), None) \
        if uses is not None else None
    if uses is not None and licence is None:
        fail(f"the listing has no licence {uses.get('ref')} for {package.get('path')}")
    kept = [licence] if licence is not None else []
    for c in package:
        if local(c.tag) in ("channelRef", "archives"):
            continue
        if local(c.tag) == "revision" and revision is not None:
            c = ET.Element("revision")
            for p, n in zip(("major", "minor", "micro"), revision.split(".")):
                ET.SubElement(c, p).text = n
        kept.append(c)
    body = []
    for c in kept:
        c = copy.copy(c)
        c.tail = None
        body.append(ET.tostring(c, encoding="unicode"))
    body.insert(1 if licence is not None else 0,
                f'<localPackage path={quoteattr(package.get("path"))} obsolete={quoteattr(package.get("obsolete", "false"))}>')
    declarations = " ".join(f"xmlns:{p}={quoteattr(u)}" for p, u in sorted(prefixes.items()))
    return (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<{name}:repository {declarations}>'
            + "".join(body) + f"</localPackage></{name}:repository>\n")


def installed(sdk, path):
    """The revision sdkmanager lists path at in the SDK, or None."""
    sdkmanager = sdk / "cmdline-tools" / "latest" / "bin" / "sdkmanager"
    listed = subprocess.run([str(sdkmanager), f"--sdk_root={sdk}", "--list_installed"], capture_output=True, text=True)
    found = None
    for line in listed.stdout.splitlines():
        cells = [c.strip() for c in line.split("|")]
        if len(cells) >= 2 and cells[0] == path:
            found = cells[1]
    return found, listed.stdout + listed.stderr


def install(file, listing_file, path, channel, revision):
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not home:
        fail("neither ANDROID_HOME nor ANDROID_SDK_ROOT is set")
    sdk, file = Path(home), Path(file)
    root, prefixes = listing(listing_file)
    package, _ = newest(root, path, channel)
    want = revision or dotted(child(package, "revision"))
    document = package_xml(root, prefixes, package, revision)
    dest = sdk.joinpath(*path.split(";"))
    stage, old = dest.with_name(f".sdkpkg-{dest.name}"), dest.with_name(f".sdkpkg-old-{dest.name}")
    for leftover in (stage, old):
        shutil.rmtree(leftover, ignore_errors=True)
    stage.mkdir(parents=True)
    try:
        # unzip checks each entry's CRC-32 as it unpacks; the archive's SHA-1 was checked when it was fetched
        if subprocess.run(["unzip", "-q", str(file), "-d", str(stage)]).returncode != 0:
            fail(f"{file} did not unpack")
        tops = list(stage.iterdir())
        if len(tops) != 1 or not tops[0].is_dir():
            fail(f"{file} holds {len(tops)} entries at its top, not one folder")
        (tops[0] / "package.xml").write_text(document, encoding="utf-8")
        if dest.exists():
            dest.rename(old)
        tops[0].rename(dest)
    finally:
        shutil.rmtree(stage, ignore_errors=True)
        shutil.rmtree(old, ignore_errors=True)
    found, said = installed(sdk, path)
    if found is None or parts(found) != parts(want):
        print(said, flush=True)
        fail(f"sdkmanager lists {path} at {found or 'no revision'} after its install from {file.name}, not {want}")
    print(f"{path} {want}: installed from {file.name} in {dest}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    d = commands.add_parser("describe")
    for name in ("xml_url", "path", "listing"):
        d.add_argument(name)
    d.add_argument("--channel", type=int, default=0)
    d.add_argument("--prefix", default="")
    f = commands.add_parser("fetch")
    for name in ("url", "size", "sha1", "file"):
        f.add_argument(name)
    i = commands.add_parser("install")
    for name in ("file", "listing", "path"):
        i.add_argument(name)
    i.add_argument("--channel", type=int, default=0)
    i.add_argument("--revision")
    args = parser.parse_args()
    if args.command == "describe":
        describe(args.xml_url, args.path, args.listing, args.channel, args.prefix)
    elif args.command == "fetch":
        fetch(args.url, args.size, args.sha1, args.file)
    else:
        install(args.file, args.listing, args.path, args.channel, args.revision)


if __name__ == "__main__":
    main()
