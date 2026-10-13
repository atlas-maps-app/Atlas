"""inbox.py — one list of everything the owner left for Claude, run at the start of every session.

1. Export the studio's database into one folder (ArtifactData `list`, `query: {limit: 1000}`, `out_dir` = <dir>/<store>):
     studio https://claude.ai/artifact/91R7EDZTwhvELNpH6nvfSB   studio requests questions
   e.g. out_dir = cache/state/inbox/2026-10-09/studio  (each doc lands at <out_dir>/<collection>/<id>.json).
   Then, per collection, paste the list result into `python3 dbplan.py versions <dir> <store> <collection>`: the files carry
   no versions, and after_canvas.py and rename.py pin every write to them.
2. Read the comment threads of all three artifacts (ArtifactComments `read`, the two canvases too:
     Main https://claude.ai/artifact/Dapy8WL82jeyUhqPKPmA5L  Engine https://claude.ai/artifact/UQAwzFPMbEhhLcvrpZsjZR).
   The header says "N open". With 0 open, stop there: resolved threads are history and a page of ten costs
   about 9,000 tokens. With N open, page with `cursor` only until the N are found, read each by `thread_id`, and
   note them in <dir>/comments.json as [{artifact, thread, said, at}] so they show below.
2b. Notes typed straight onto boards: list the Main canvas's files (Artifact `list`, scope "files"), read every board
   whose size differs from canvas/project/ into <dir>/canvas (Artifact `read`, `paths`), then
   python3 canvas/board_notes.py <dir>/canvas/project   (writes <dir>/board_notes.json, shown below).
3. python3 inbox.py <dir>          prints the list; --mark records this run in cache/state/inbox_state.json
   (last run time and every doc's version), so the next run shows what changed since.

Open means: a studio request not done (an idea of yours is one, kind 'idea'); an answered question; a studio doc
changed since the last run; an open comment thread; a note typed onto a board.
"""
import json, os, sys, glob, datetime

A = os.path.dirname(os.path.abspath(__file__))
STATE = os.path.join(A, 'cache', 'state', 'inbox_state.json')


def load(path):
    d = json.load(open(path))
    if isinstance(d, dict) and 'data' in d and isinstance(d['data'], dict):
        return d['data'], d.get('version')
    return d, None


def docs(root, store, col):
    out = {}
    for f in sorted(glob.glob(os.path.join(root, store, col, '*.json'))):
        did = os.path.basename(f)[:-5].replace('@', '~')
        out[did] = load(f)
    return out


def last_by(thread):
    return thread[-1].get('by') if isinstance(thread, list) and thread else None


def short(s, n=110):
    s = ' '.join(str(s or '').split())
    return s if len(s) <= n else s[:n - 1] + '…'


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    if not args:
        sys.exit(__doc__)
    root = args[0]
    state = json.load(open(STATE)) if os.path.exists(STATE) else {'last_run': None, 'versions': {}}
    seen = state.get('versions', {})
    since = state.get('last_run')
    rows, versions = [], {}

    def add(where, what, text, at=None):
        rows.append((str(at or ''), where, what, short(text)))

    # ---------- Studio ----------
    for did, (d, v) in docs(root, 'studio', 'requests').items():
        versions['studio/requests/' + did] = v
        if isinstance(d, dict) and d.get('status') != 'done':
            add('Studio request ' + did, '%s · %s' % (d.get('kind'), d.get('status')),
                '%s: %s' % (d.get('screen') or d.get('page') or '', d.get('text') or d.get('note') or ''), d.get('edited') or d.get('at'))
    for did, (d, v) in docs(root, 'studio', 'questions').items():
        versions['studio/questions/' + did] = v
        if isinstance(d, dict) and d.get('status') == 'answered' and seen.get('studio/questions/' + did) != v:
            add('Studio question ' + did, 'answered', '%s → %s' % (d.get('q'), d.get('answer') or d.get('choice')), d.get('at'))
    for did, (d, v) in docs(root, 'studio', 'studio').items():
        key = 'studio/studio/' + did
        versions[key] = v
        if since and v is not None and seen.get(key) not in (None, v):
            add('Studio studio/' + did, 'changed', 'v%s → v%s (the owner edited it; check what changed)' % (seen.get(key), v))

    # ---------- Comments ----------
    cpath = os.path.join(root, 'comments.json')
    if os.path.exists(cpath):
        for c in json.load(open(cpath)):
            add('Comment on ' + c.get('artifact', '?'), 'open thread ' + str(c.get('thread', ''))[:8], c.get('said'), c.get('at'))
    else:
        rows.append(('', 'Comments', 'not read yet', 'step 2 in the docstring: read all three artifacts\' threads'))

    # ---------- Notes typed onto boards (canvas/board_notes.py) ----------
    npath = os.path.join(root, 'board_notes.json')
    if os.path.exists(npath):
        for r in json.load(open(npath)):
            for t in r.get('added', []):
                add('Board ' + r.get('board', '?'), 'text added', t)
            for t in r.get('removed', []):
                add('Board ' + r.get('board', '?'), 'text removed', t)
    else:
        rows.append(('', 'Board notes', 'not checked yet', 'step 2b in the docstring: changed boards vs canvas/project'))

    print('Inbox · %s · last run %s' % (datetime.datetime.utcnow().strftime('%Y-%m-%d %H:%MZ'), since or 'never'))
    print('\nOpen for Claude (%d):' % len(rows))
    for at, where, what, text in sorted(rows, key=lambda r: r[0]):
        print('  - %-34s %-18s %s%s' % (where, what, text, ('  [%s]' % at[:16]) if at else ''))

    if '--mark' in sys.argv:
        os.makedirs(os.path.dirname(STATE), exist_ok=True)
        json.dump({'last_run': datetime.datetime.utcnow().strftime('%Y-%m-%dT%H:%M:%SZ'), 'versions': versions},
                  open(STATE, 'w'), indent=1, sort_keys=True)
        print('\nRecorded this run in ' + os.path.relpath(STATE, A))


if __name__ == '__main__':
    main()
