"""Packs the eleven regions' OpenStreetMap extracts into one archive, byte for byte, with OpenStreetMap's notice.
The archive's bytes depend on the files alone: no owner, no machine's name, no time of packing.
Made packs-renders' regions-osm-extracts.tar.gz (23 Nov 2026, the user's yes), 23,241,657 bytes, SHA-256
06b28bfa071a21425216aa90968a752cf064aa8225ce50693b29e208f821323f, which core.yml fetches for ATLAS_REGIONS_DIR.
usage: pack.py <data/regions> <out.tar.gz>"""
import calendar, gzip, hashlib, io, re, sys, tarfile, time

REGIONS = ['artis', 'bergen', 'dunes', 'efteling', 'grachten', 'newyork', 'nottinghill', 'paris', 'port',
           'sangimignano', 'schiphol']
STAMP = re.compile(rb'"timestamp_osm_base": "(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ)"')
COPYRIGHT = b'"copyright": "The data included in this document is from www.openstreetmap.org. The data is made available under ODbL."'

root, out = sys.argv[1], sys.argv[2]
files = []
for r in REGIONS:
    data = open(f'{root}/{r}/raw.json', 'rb').read()
    head = data[:600]
    stamp = STAMP.search(head)
    if not stamp or COPYRIGHT not in head:
        raise SystemExit(f'{r}: not an Overpass answer with OpenStreetMap\'s own line')
    files.append((r, data, stamp.group(1).decode(), hashlib.sha256(data).hexdigest()))

rows = '\n'.join(f'  {r + "/raw.json":<24} {s[:10]}  {len(d):>10,} bytes  sha256 {h}' for r, d, s, h in files)
notice = f"""The eleven regions' OpenStreetMap extracts that Atlas's renders and their region packs were made from.

Map data (c) OpenStreetMap contributors, made available under the Open Database License (ODbL) 1.0.
  https://www.openstreetmap.org/copyright
  https://opendatacommons.org/licenses/odbl/1-0/

Each <region>/raw.json is an answer of the Overpass API for that region's box (nodes, ways with their
geometry, and relations, with their tags), byte for byte as Atlas keeps it. "osm3s.timestamp_osm_base"
in a file is the date of OpenStreetMap's data in it:

{rows}

Two of them are put together from more than one answer for the same place: the canal ring's (grachten)
is its ring and three boxes beside it, merged by element id, because the whole box timed out; Bergen's
has its coastline ways from a later query, as its "remark_atlas" says.

Atlas's tests read them where ATLAS_REGIONS_DIR names the folder they lie in, each beside its
region.json from the repository's data/regions: https://github.com/atlas-maps-app/Atlas
""".encode()

def epoch(stamp):
    return calendar.timegm(time.strptime(stamp, '%Y-%m-%dT%H:%M:%SZ'))

def member(name, data, mtime):
    info = tarfile.TarInfo(name)
    info.size, info.mtime, info.mode = len(data), mtime, 0o644
    info.uid = info.gid = 0
    info.uname = info.gname = ''
    return info, io.BytesIO(data)

with open(out, 'wb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, compresslevel=9, mtime=0) as gz, \
        tarfile.open(fileobj=gz, mode='w', format=tarfile.USTAR_FORMAT) as tar:
    tar.addfile(*member('NOTICE.txt', notice, max(epoch(s) for _, _, s, _ in files)))
    for r, data, stamp, _ in files:
        tar.addfile(*member(f'{r}/raw.json', data, epoch(stamp)))

packed = open(out, 'rb').read()
print(notice.decode())
print(f'{out}: {len(packed):,} bytes, sha256 {hashlib.sha256(packed).hexdigest()}')
print(f'inside: {sum(len(d) for _, d, _, _ in files):,} bytes of raw.json in {len(files)} files, {len(notice):,} of notice')
