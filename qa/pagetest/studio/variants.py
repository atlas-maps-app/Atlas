"""Config variants of a studio export, for the Words write-back proof (writeback_proof.sh) at From pool > 0.

usage: python3 variants.py [EXPORT] OUT [--good GOOD.json]

Writes OUT/<name>/studio/ (the export's studio collections, its config changed as below) and prints the names, one per line:
  live          the config as it is (on 8 Oct: mix B draws nothing from the pool, C is its own prompt)
  pool3         B draws 3 from the pool
  pool9         B draws 9, shuffled, keeps 15; a new mix D (seed 5) draws 4, no forms, no Good or Loved lines
  setoff        B draws 6; two set entries left off (on: false): 'calm', and 'Honest' (the pool has 'honest'), which From pool skips
  formsheld     B draws 4; the pair care⇄caring on hold; 'quiet' held too
After an edit on the Words tab, the old page must read the written config into byte-identical prompts (writeback_proof.sh).
With --good, every variant's studio/good is that file (e.g. the Good doc as it will be after the batch); the config is unchanged by it.
"""
import argparse, copy, json, os, shutil, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ATLAS = os.path.abspath(os.path.join(HERE, '..', '..', '..'))


def mix(cfg, name):
    return next(m for m in cfg['mixes'] if m['name'] == name)


def make(cfg):
    out = {'live': copy.deepcopy(cfg)}
    c = copy.deepcopy(cfg); mix(c, 'B')['fromPool'] = 3; out['pool3'] = c
    c = copy.deepcopy(cfg); b = mix(c, 'B'); b.update(fromPool=9, shuffle=True, keep=15)
    d = copy.deepcopy(b); d.update(name='D', seed=5, fromPool=4, vary=False, shuffle=False, keep=0, good=False, ours=False, place='none'); c['mixes'].append(d); out['pool9'] = c
    c = copy.deepcopy(cfg); mix(c, 'B')['fromPool'] = 6
    c['set'].insert(3, {'w': 'calm', 'on': False}); c['set'].insert(8, {'w': 'Honest', 'on': False}); out['setoff'] = c
    c = copy.deepcopy(cfg); mix(c, 'B')['fromPool'] = 4; c['formsHold'] = ['care⇄caring']; c['poolHold'] = list(c.get('poolHold') or []) + ['quiet']; out['formsheld'] = c
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('export', nargs='?', default=os.path.join(ATLAS, 'qa', 'pagetest', 'export'))
    ap.add_argument('out')
    ap.add_argument('--good')
    a = ap.parse_args()
    ex, out = os.path.realpath(a.export), os.path.realpath(a.out)
    if out == ex or out.startswith(ex + os.sep) or ex.startswith(out + os.sep):   # OUT/<name>/studio is replaced: never inside the export
        sys.exit('OUT must be outside EXPORT (and not hold it): %s' % a.out)
    src = os.path.join(a.export, 'studio') if os.path.isdir(os.path.join(a.export, 'studio', 'studio')) else a.export
    cp = os.path.join(src, 'studio', 'config.json')
    raw = json.load(open(cp)); wrapped = isinstance(raw, dict) and 'data' in raw and set(raw) <= {'data', 'version'}
    cfg = raw['data'] if wrapped else raw
    for k in ('set', 'pool', 'mixes'):
        if k not in cfg:
            sys.exit('no %s in %s' % (k, cp))
    for name, c in make(cfg).items():
        dst = os.path.join(a.out, name, 'studio')
        if os.path.isdir(dst):
            shutil.rmtree(dst)
        shutil.copytree(src, dst)
        json.dump({'data': c, 'version': raw.get('version')} if wrapped else c, open(os.path.join(dst, 'studio', 'config.json'), 'w'), ensure_ascii=False, indent=1)
        if a.good:
            shutil.copyfile(a.good, os.path.join(dst, 'studio', 'good.json'))
        print(name)


if __name__ == '__main__':
    main()
