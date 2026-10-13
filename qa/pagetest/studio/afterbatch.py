"""The studio export as it will be after a batch plan: applies ArtifactData writes to a copy of the export's studio collections.

usage: python3 afterbatch.py EXPORT BATCH.json OUT [--expect collection/doc=FILE ...]

EXPORT holds studio/ (qa/pagetest/export) and versions.json beside it; BATCH is a list of {op, collection, doc_id, data, if_version}.
As ArtifactData does (plan/README.md): set makes a new doc (no if_version), update merges objects deeply ({"__delete__": true}
removes a key) and replaces arrays whole, delete removes; every update and delete must carry the export's version.
Writes OUT/studio/<collection>/<doc>.json. --expect checks that a doc comes out exactly equal to a file. Exit 1 on any mismatch.
"""
import argparse, copy, glob, json, os, shutil, sys

DEL = {'__delete__': True}


def merge(doc, upd):
    out = copy.deepcopy(doc)
    for k, v in upd.items():
        if v == DEL:
            out.pop(k, None)
        elif isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = merge(out[k], v)
        else:
            out[k] = copy.deepcopy(v)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('export'); ap.add_argument('batch'); ap.add_argument('out')
    ap.add_argument('--expect', action='append', default=[])
    a = ap.parse_args()
    ex, out = os.path.realpath(a.export), os.path.realpath(a.out)
    if out == ex or out.startswith(ex + os.sep) or ex.startswith(out + os.sep):   # OUT/studio is replaced: never the export itself
        sys.exit('OUT must be outside EXPORT (and not hold it): %s' % a.out)
    src = os.path.join(a.export, 'studio')
    vers = json.load(open(os.path.join(a.export, 'versions.json')))['studio']
    unwrap = lambda d: d['data'] if isinstance(d, dict) and 'data' in d and set(d) <= {'data', 'version'} else d
    docs = {c: {os.path.basename(f)[:-5]: unwrap(json.load(open(f))) for f in glob.glob(os.path.join(src, c, '*.json'))} for c in os.listdir(src) if os.path.isdir(os.path.join(src, c))}
    ok = True
    for w in json.load(open(a.batch)):
        c, d, op = w['collection'], w['doc_id'], w['op']
        have = d in docs.get(c, {})
        if op == 'set':
            pin_ok = ('if_version' in w and have and w['if_version'] == vers.get(c, {}).get(d)) or ('if_version' not in w and not have)
            docs.setdefault(c, {})[d] = copy.deepcopy(w['data'])
        else:
            pin_ok = have and w.get('if_version') == vers.get(c, {}).get(d)
            if have:
                if op == 'update':
                    docs[c][d] = merge(docs[c][d], w['data'])
                else:
                    del docs[c][d]
        print('%-6s %s/%s  if_version %s  %s' % (op, c, d, w.get('if_version', '-'), 'pinned to the export' if pin_ok and 'if_version' in w else 'new doc' if pin_ok else 'PIN WRONG'))
        ok = ok and pin_ok
    dst = os.path.join(a.out, 'studio')
    if os.path.isdir(dst):
        shutil.rmtree(dst)
    for c, ds in docs.items():
        os.makedirs(os.path.join(dst, c), exist_ok=True)
        for d, v in ds.items():
            json.dump(v, open(os.path.join(dst, c, d + '.json'), 'w'), ensure_ascii=False, indent=1)
    shutil.copyfile(os.path.join(a.export, 'versions.json'), os.path.join(a.out, 'versions.json'))
    for e in a.expect:
        key, f = e.split('=', 1); c, d = key.split('/', 1)
        same = docs.get(c, {}).get(d) == json.load(open(f))
        print('%s/%s after the batch %s %s' % (c, d, 'equals' if same else 'DIFFERS FROM', f))
        ok = ok and same
    print('AFTER-BATCH EXPORT OK' if ok else 'AFTER-BATCH EXPORT FAILS')
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
