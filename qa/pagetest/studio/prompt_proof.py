"""Proof that the studio's prompts did not change except where the page means them to.

usage: python3 prompt_proof.py BEFORE.json AFTER.json [GOOD.json] [--expect LINE]
BEFORE/AFTER are what tests/prompts_dump.js printed for the old and the new page (against the same export, or AFTER against the
export as it will be after a batch). Every part of every mix's prompt must be byte-identical, except the Good screens part (B6: built
from the Good tab's non-held share titles now); with GOOD.json (the studio/good doc) that part must be exactly those titles joined
by '. ', and with --expect every changed Good screens part must read exactly LINE.
Exit 0 when it holds, 1 when it does not."""
import json, sys

expect = None
if '--expect' in sys.argv:
    i = sys.argv.index('--expect'); expect = sys.argv[i + 1]; del sys.argv[i:i + 2]

def load(p):
    t = open(p).read().strip().splitlines()
    return json.loads(next(l for l in t if l.startswith('{')))

b, a = load(sys.argv[1]), load(sys.argv[2])
good = None
if len(sys.argv) > 3:
    g = json.load(open(sys.argv[3])); g = g.get('data', g) if set(g) <= {'data', 'version'} else g
    good = '. '.join(q['title'].strip().rstrip('. ') for q in g.get('share', []) if not q.get('hold') and q.get('title', '').strip())
ok = True
if [m['name'] for m in b['mixes']] != [m['name'] for m in a['mixes']]:
    print('FAIL: the mixes differ'); sys.exit(1)
for mb, ma in zip(b['mixes'], a['mixes']):
    rest = lambda m: [(s['part'], s['text']) for s in m['segments'] if s['part'] != 'good']
    text_wo = lambda m: ''.join(s['text'] for s in m['segments'] if s['part'] != 'good')
    same = rest(mb) == rest(ma) and text_wo(mb) == text_wo(ma) and mb['words'] == ma['words']
    gb = ''.join(s['text'] for s in mb['segments'] if s['part'] == 'good'); ga = ''.join(s['text'] for s in ma['segments'] if s['part'] == 'good')
    joined = ''.join(s['text'] for s in ma['segments']) == ma['text']
    gok = (good is None or not ga or ga.rstrip('\n') == good) and (expect is None or gb == ga or ga.rstrip('\n') == expect)
    print('mix %s%s: other parts %s (%d chars), words %s, parts join to the text: %s; good part %s' % (mb['name'], ' (own)' if mb['own'] else '', 'byte-identical' if same else 'DIFFER', len(text_wo(ma)), 'same' if mb['words'] == ma['words'] else 'DIFFER', joined,
          'unchanged' if gb == ga else 'changed%s' % ('' if gok else ' WRONG')))
    if gb != ga:
        print('   before: %r\n   after:  %r' % (gb, ga))
    ok = ok and same and joined and gok
for k in ('errors',):
    if a.get(k): print('page errors after:', a[k]); ok = False
changed = [mb['name'] for mb, ma in zip(b['mixes'], a['mixes']) if ''.join(x['text'] for x in mb['segments'] if x['part'] == 'good') != ''.join(x['text'] for x in ma['segments'] if x['part'] == 'good')]
print('Good screens changed in: %s%s' % (', '.join(changed) or 'none', '; expected line %r %s' % (expect, 'matches' if ok else 'checked') if expect is not None else ''))
print('PROOF HOLDS' if ok else 'PROOF FAILS')
sys.exit(0 if ok else 1)
