#!/bin/zsh
# writeback_proof.sh OLD.html [EXPORT]: the config this page writes after Words edits (tests/writeback_dump.js: words on and off again, held words
# on and off again, a word edited to itself, a held word renamed and back and dragged, a pair on hold and back, a colour), read by the old page,
# gives byte-identical prompts (the words From pool draws included) to the old page reading the export as it is, and the held words stay held
# (the written poolHold equals the export's poolHold plus its set entries left off).
# OLD.html: e.g. git show <rev>:studio/studio.html. variants.py makes exports whose mixes draw from the pool (proofs.sh runs them all).
here=${0:A:h}; atlas=${here:h:h:h}
OLD=$1; EXP=${2:-$atlas/qa/pagetest/export}; W=${TMPDIR:-/tmp}/atlas_studio_test/writeback; mkdir -p $W
[[ -f $OLD ]] || { echo "usage: writeback_proof.sh OLD.html [EXPORT]"; exit 2 }
python3 $here/mkstudio.py $atlas/studio/studio.html $EXP $W/new >/dev/null || exit 1
python3 $here/mkstudio.py $OLD $EXP $W/old >/dev/null || exit 1
$here/run.sh $W/new/edit.html 1280 900 light $here/tests/writeback_dump.js > $W/written.txt
$here/run.sh $W/old/edit.html 1280 900 light $here/tests/prompts_dump.js > $W/old_before.txt
python3 - $EXP $W <<'PY' || exit 1
import json, os, shutil, sys
exp, w = sys.argv[1], sys.argv[2]
r = json.loads(next(l for l in open(w + '/written.txt').read().splitlines() if l.startswith('{')))
if not r.get('cfg'): sys.exit('no config written: %s' % r)
dst = w + '/export'
if os.path.isdir(dst): shutil.rmtree(dst)
shutil.copytree(exp, dst)
p = os.path.join(dst, 'studio', 'studio', 'config.json') if os.path.isdir(os.path.join(dst, 'studio', 'studio')) else os.path.join(dst, 'studio', 'config.json')
o = json.load(open(p))
json.dump({'data': r['cfg'], 'version': o.get('version')} if 'data' in o else r['cfg'], open(p, 'w'), ensure_ascii=False)
print('edits: %s' % '; '.join(r.get('done') or []))
print('written: %d config write(s); held before %s, after %s; poolHold now %s; mixes drawing from the pool: %s' % (r['n'], r.get('held'), r.get('heldAfter'), r['cfg'].get('poolHold'),
      ', '.join('%s %s' % (m['name'], m.get('fromPool')) for m in r['cfg'].get('mixes', []) if (m.get('fromPool') or 0) > 0 and not isinstance(m.get('own'), str)) or 'none'))
if r.get('errors'): sys.exit('page errors: %s' % r['errors'])
if sorted(r.get('held') or []) != sorted(r.get('heldAfter') or []): sys.exit('the held words changed')
# from the export, not from the page's chips: the written poolHold is the export's poolHold plus the set entries left off (their pool spellings)
oc = o['data'] if 'data' in o else o; lc = lambda x: str(x).lower()
want = set(oc.get('poolHold') or [])
for x in oc.get('set', []):
    if not x.get('on'): want |= set([w for w in oc.get('pool', []) if lc(w) == lc(x['w'])] or [x['w']])
got = set(r['cfg'].get('poolHold') or [])
if got != want: sys.exit('poolHold written %s, the export holds %s' % (sorted(got), sorted(want)))
PY
python3 $here/mkstudio.py $OLD $W/export $W/oldwb >/dev/null || exit 1
$here/run.sh $W/oldwb/edit.html 1280 900 light $here/tests/prompts_dump.js > $W/old_after.txt
python3 - $W <<'PY'
import json, sys
w = sys.argv[1]
load = lambda p: json.loads(next(l for l in open(p).read().splitlines() if l.startswith('{')))
a, b = load(w + '/old_before.txt'), load(w + '/old_after.txt')
ok = [m['name'] for m in a['mixes']] == [m['name'] for m in b['mixes']] and not b['errors']
for x, y in zip(a['mixes'], b['mixes']):
    same = x['text'] == y['text'] and x['segments'] == y['segments'] and x['words'] == y['words']
    print('mix %s%s: %s (%d chars)' % (x['name'], ' (own)' if x['own'] else '', 'byte-identical' if same else 'DIFFERS', len(y['text'])))
    ok = ok and same
print('WRITE-BACK PROOF HOLDS' if ok else 'WRITE-BACK PROOF FAILS')
sys.exit(0 if ok else 1)
PY
