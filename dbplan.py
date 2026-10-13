"""Shared by after_canvas.py and rename.py: read a database export, and write batch plans for ArtifactData.

An export folder holds one folder per database (studio/) or is one database's folder; inside, one folder per
collection and one JSON per doc. A file is the doc itself or {data, version}; an '@' in a file name stands for '~'.
Versions not in the files come from versions.json beside the export ({db: {collection: {doc: version}}}).
    python3 dbplan.py versions <export dir> <db> <collection> < <the ArtifactData list result, pasted>
writes those versions into <export dir>/versions.json: an export saved with out_dir carries no versions, and the list result
names every doc's version in two shapes ('- "i07"  320 bytes  version 2' and, in its tail, '"i38" 6, "i39" 1').

A batch plan is a list of ArtifactData batch writes ({op, collection, doc_id, data, if_version}), at most 45 per file,
every write to an existing doc pinned to the export's version. Nothing here writes to a live database."""
import json, os, glob

URL = {'studio': 'https://claude.ai/artifact/91R7EDZTwhvELNpH6nvfSB'}
PER_FILE = 45


def db_dir(root, db):
    """The folder of one database in an export (root/db, or root itself when it is that database's folder)."""
    if not root: return None
    if os.path.isdir(os.path.join(root, db)) and any(os.path.isdir(os.path.join(root, db, x)) for x in os.listdir(os.path.join(root, db))):
        return os.path.join(root, db)
    if os.path.basename(os.path.normpath(root)) == db: return root
    return None


def _versions(root, db):
    for d in (root, os.path.dirname(os.path.normpath(root)), os.path.dirname(os.path.dirname(os.path.normpath(root)))):
        p = os.path.join(d, 'versions.json')
        if d and os.path.exists(p):
            v = json.load(open(p))
            if db in v: return v[db]
    return {}


def load(root, db):
    """{collection: {doc_id: (doc, version)}} for one database of an export ({} when it is not there)."""
    d = db_dir(root, db)
    if not d: return {}
    vs = _versions(os.path.dirname(os.path.normpath(d)) if os.path.basename(os.path.normpath(d)) == db else d, db)
    out = {}
    for cdir in sorted(glob.glob(os.path.join(d, '*'))):
        if not os.path.isdir(cdir): continue
        coll = os.path.basename(cdir); docs = {}
        for f in sorted(glob.glob(os.path.join(cdir, '*.json'))):
            did = os.path.basename(f)[:-5].replace('@', '~')
            x = json.load(open(f))
            if isinstance(x, dict) and 'data' in x and isinstance(x['data'], dict) and set(x) <= {'data', 'version', 'id', 'path'}:
                docs[did] = (x['data'], x.get('version') or vs.get(coll, {}).get(did))
            else:
                docs[did] = (x, vs.get(coll, {}).get(did))
        out[coll] = docs
    return out


def doc_file(path):
    """A single exported doc file: (doc, version)."""
    x = json.load(open(path))
    if isinstance(x, dict) and 'data' in x and isinstance(x['data'], dict) and set(x) <= {'data', 'version', 'id', 'path'}:
        return x['data'], x.get('version')
    return x, None


class Batches:
    """Writes for one database, saved as batch files of at most 45 writes."""
    def __init__(self, db):
        self.db = db; self.writes = []; self.unpinned = []

    def _w(self, op, coll, did, data=None, version=None, exists=True):
        w = {'op': op, 'collection': coll, 'doc_id': did}
        if data is not None: w['data'] = data
        if version: w['if_version'] = int(version)
        elif exists: self.unpinned.append('%s/%s' % (coll, did))
        self.writes.append(w)

    def set(self, coll, did, data, version=None, exists=False): self._w('set', coll, did, data, version, exists or bool(version))
    def update(self, coll, did, data, version): self._w('update', coll, did, data, version, True)
    def delete(self, coll, did, version): self._w('delete', coll, did, None, version, True)

    def save(self, folder, prefix):
        """Writes the batch files; returns their paths."""
        if not self.writes: return []
        os.makedirs(folder, exist_ok=True); paths = []
        for k in range(0, len(self.writes), PER_FILE):
            p = os.path.join(folder, '%s_%s_%02d.json' % (prefix, self.db, k // PER_FILE + 1))
            json.dump(self.writes[k:k + PER_FILE], open(p, 'w'), ensure_ascii=False, indent=1); paths.append(p)
        return paths


def versions_from_listing(text):
    """{doc id: version} from an ArtifactData list result, both the lines and the tail."""
    import re
    out = {}
    for m in re.finditer(r'"([^"\s]+)"\s+\d+ bytes\s+version (\d+)', text): out[m.group(1)] = int(m.group(2))
    tail = text.split('each (id, version):', 1)
    if len(tail) == 2:
        for m in re.finditer(r'"([^"\s]+)" (\d+)', tail[1]): out[m.group(1)] = int(m.group(2))
    return {k.replace('@', '~'): v for k, v in out.items()}


if __name__ == '__main__':
    import sys
    if sys.argv[1:2] == ['versions'] and len(sys.argv) == 5:
        root, db, col = sys.argv[2:5]; p = os.path.join(root, 'versions.json')
        v = json.load(open(p)) if os.path.exists(p) else {}
        got = versions_from_listing(sys.stdin.read()); v.setdefault(db, {}).setdefault(col, {}).update(got)
        json.dump(v, open(p, 'w'), indent=1); print('%s/%s: %d versions -> %s' % (db, col, len(got), p))
    else: print(__doc__)
