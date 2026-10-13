"""Reads what all.sh's runs printed (one folder of result files) and checks it.
usage: python3 check.py RESULTS_DIR   ->  one line per failed check, then "== Studio checks: N passed, M failed"."""
import glob, json, os, sys

R = sys.argv[1]
passed, failed = 0, []
# every screen the studio lists: the canvases' screens as studio/screens.json has them (mkstudio.py feeds the page the same file)
ALL = sum(len(p['screens']) for p in json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', 'studio', 'screens.json')))['pages'])


def ok(cond, what):
    global passed
    if cond: passed += 1
    else: failed.append(what)


def lines(f):
    out = []
    for l in open(f).read().splitlines():
        if l.startswith('{'):
            try: out.append(json.loads(l))
            except Exception: out.append({'_unparsed': l[:200]})
        elif l.startswith('JSERR') or l.startswith('TIMEOUT'): out.append({'_error': l[:300]})
    return out


for f in sorted(glob.glob(os.path.join(R, 'edit_*.txt'))):
    tag = os.path.basename(f)[5:-4]; t = lines(f)
    ok(len(t) == 6 and not any('_error' in x for x in t), tag + ': 6 tests ran (%d, %s)' % (len(t), [x.get('_error') for x in t if '_error' in x]))
    if len(t) < 6: continue
    sm, wd, rt, im, pa, su = t
    ok(sm['status'] == 'status saved' and not sm['errors'] and not sm['writes'], tag + ': loads saved, no errors, no writes on load')
    for k, v in sm['tabs'].items():
        ok(v['shown'] and v['over'] == 0 and not v['wide'] and not v['bad'], tag + ': tab %s draws, nothing sticks out, no null/NaN %s' % (k, v))
    ok(sm['nav'] == ['words', 'prompt', 'goodpage', 'screens', 'sourcespage', 'inbox'], tag + ': tabs (no Questions)')
    ok([x.split(' ')[0] for x in sm['links']] == ['Main', 'Engine', 'Studio'] and sm['links'][2] == 'Studio', tag + ': the three links, Studio as plain text')
    ok(wd['onIsSet'] and wd['afterOn']['isOn'] and wd['afterOff']['promptsIdentical'] and wd['afterOff']['setSameOrder'] and wd['afterOff']['poolSame'] and wd['afterOff']['poolHold'] == wd['afterOff']['poolHoldBefore'] == ['fun', 'real things'], tag + ': words on/off round trip, prompts byte-identical, holds kept %s' % wd.get('afterOff'))
    ok(wd.get('heldChips') == ['real things', 'fun'] and wd.get('countAfter') == '22 on · 27 off · 2 held' and wd.get('heldOn', {}).get('on') and wd['heldOn']['inPrompt'] and wd['heldOn']['stillInPoolHold'] and 'back on hold' in wd['heldOn']['tip']
       and wd.get('heldBack', {}).get('held') and wd['heldBack']['poolHold'] == ['fun', 'real things'] and wd['heldBack']['prompts'] and wd['heldBack']['draws5'] and wd.get('draws5Same') and wd['written']['poolHold'] == ['fun', 'real things'],
       tag + ': held words stay held (on, off: held again), From pool 5 draws unchanged %s' % {k: wd.get(k) for k in ('heldChips', 'countAfter', 'heldOn', 'heldBack', 'draws5Same')})
    ok(wd['written'] and wd['written']['set'] and wd['written']['goodKept'] and wd['written']['notesKept'] and wd['written']['qGoneKept'], tag + ': config written back whole %s' % wd.get('written'))
    ok(wd['drag']['first'] and wd['added']['on'] and wd['added']['chip'] and wd['removed'] and not wd['errors'], tag + ': drag, add, remove')
    ok(rt['gold'] == rt['goldExpected'] and rt['written'] == rt['after'] and rt['after'] != rt['before'] and rt['othersKept'] >= 110, tag + ': rating writes studio/ranks, gold dots = 4★+ %s' % rt)
    ok(rt['dot'] == (rt['after'] >= 4) and rt['dot2'] == (rt['after2'] >= 4), tag + ': the gold dot follows the stars')
    ok(rt['noStars']['n'] > 0 and not rt['noStars']['anyStarred'] and rt['all'] == ALL, tag + ': Show › No stars %s' % rt['noStars'])
    ok(im['gone'] == {'wellBadly': 0, 'questions': False, 'notesFold': False, 'noteBtn': 0, 'good': False}, tag + ': Well/Badly, Questions, notes, cfg.good textarea gone %s' % im['gone'])
    ok(im['cardmore'] and all(im['cardmore'].values()), tag + ': card ⋯ holds tier, Looks·Use, Improve, folder, Re-envision, Ask Claude %s' % im['cardmore'])
    ok(len(im['add']) == 1 and im['add'][0]['kind'] == 'improve' and im['add'][0]['screen'] == 'Main' and 'note' not in im['add'][0], tag + ': Improve makes a request %s' % im['add'])
    ok(im['update'] and im['update'][0][0] == 'requests/lky14d5hgwrq2vwp985j', tag + ': Improve edits the open request %s' % im['update'])
    e = im.get('emptied') or {}
    ok(im.get('nInboxBefore') == '2' and im.get('badgeBefore') and e.get('deletes') == 0 and e.get('last', [None])[0] == 'update' and e.get('kept') and e.get('text') == '' and e.get('reply') and not e.get('meta')
       and not e.get('badge') and e.get('nInbox') == '1' and e.get('inbox') == {'pills': ['Improve', 'Empty'], 'reply': True, 'note': True, 'text': False},
       tag + ': Improve emptied: the request stays with empty text (note, reply), no badge, no count, its thread in the Inbox %s' % e)
    a = im.get('again') or {}
    ok(a.get('last') == ['update', 'requests/lky14d5hgwrq2vwp985j', 'text,edited,status'] and a.get('status') == 'new' and a.get('text') == 'Use the brand colour.' and a.get('reply') and a.get('adds') == 0 and a.get('nInbox') == '2',
       tag + ': Improve written again: the same request, open again %s' % a)
    ok(pa['shown'] == ['opener', 'screen', 'good'] and pa['goodLine'] == pa['goodExpected'] and not pa['goodTextarea'], tag + ': parts shown, Good screens from the Good tab %s' % pa['shown'])
    ok(pa['afterApp']['shown'] == ['opener', 'app', 'screen', 'good'] and pa['afterApp']['open'], tag + ': + part brings a part in')
    ok(pa['ied'] and pa['ied']['fields'] == 0 and pa.get('wentTo') == 'goodpage' and pa['promptB'].startswith(pa['goodAfterEdit'][:60]) and ', always' in pa['promptB'], tag + ': Good screens read only, follows the Good tab')
    ok(su['toolbar'] == ['find', 'view', 'rated', 'legend'] and su['views'] == ['By category', 'By stars', 'Tiers', 'Looks vs use'] and su['show'] == ['All', 'No stars'] and not su['gone'], tag + ': toolbar Find, View, Show')
    ok(su['engine'] == ['eng_amsterdam:4', 'eng_regions:8', 'eng_traditions:3'] and su['engineLink'].startswith('https://claude.ai/artifact/UQAwzFPMbEhhLcvrpZsjZR#') and su['mainLink'].startswith('https://claude.ai/artifact/Dapy8WL82jeyUhqPKPmA5L#') and su['livingGone'], tag + ': Engine group, links, Living gone')
    ok(su['cardFace'] == ['img', 'name nm', 'good-ic', 'open', 'rate', 'more-btn'], tag + ': card face name ♥ ↗ stars ⋯ %s' % su['cardFace'])
    ok([x.split(':')[0] for x in su['head0']] == ['fold', 't tab-Map nm', 'count', 'more-btn'] and su['catmore'] and su['catmore']['stars'], tag + ': category heading name count ⋯ %s' % su['head0'])
    ok(all(su['view_' + v]['cards'] > 0 and not su['view_' + v]['bad'] for v in ('stars', 'rank', 'lookuse', 'pages')) and su['find']['n'] == 3 and not su['errors'], tag + ': views and Find')
for f in sorted(glob.glob(os.path.join(R, 'screen_*.txt'))):
    t = lines(f); x = t[0] if t else {}
    ok(x.get('open') and x.get('name') == 'MarketHall' and x.get('tab') == 'screens' and x.get('closed') and x.get('hashAfter') == '' and x.get('improve', '').startswith('Not bad') and not x.get('errors'), os.path.basename(f) + ': #screen=MarketHall %s' % x)
for f in sorted(glob.glob(os.path.join(R, 'engine_*.txt'))):
    t = lines(f); x = t[0] if t else {}
    ok(x.get('open') and x.get('name') == 'LivingLooks' and x.get('link', '').startswith('https://claude.ai/artifact/UQAwzFPMbEhhLcvrpZsjZR#LivingLooks') and not x.get('errors'), os.path.basename(f) + ': #screen=LivingLooks %s' % x)
for f in sorted(glob.glob(os.path.join(R, 'screen404_*.txt'))):
    t = lines(f); x = t[0] if t else {}
    ok(not x.get('open') and x.get('find') == 'NoSuchBoard' and not x.get('errors'), os.path.basename(f) + ': unknown #screen= goes to Find %s' % x)
for f in sorted(glob.glob(os.path.join(R, 'nodb_*.txt'))):
    t = lines(f); x = t[0] if t else {}
    ok(x.get('status') == 'status off' and x.get('statusShown') and x.get('cards', 0) == ALL and not x.get('bad') and not x.get('errors'), os.path.basename(f) + ': read only %s' % x)
    ok(x.get('afterRate', '').startswith('status off | Read only'), os.path.basename(f) + ': a tap on stars with no store: the dot still says read only %s' % x.get('afterRate'))
for f in sorted(glob.glob(os.path.join(R, 'failwrite_*.txt'))):
    t = lines(f); x = t[0] if t else {}
    ok('off' in x.get('afterRate', '') and 'shown true' in x.get('afterRate', '') and not x.get('bad') and not x.get('errors'), os.path.basename(f) + ': a failed write shows %s' % x)
for f in sorted(glob.glob(os.path.join(R, 'more_*.txt'))):
    tag = os.path.basename(f)[5:-4]; t = lines(f)
    ok(len(t) == 5 and not any('_error' in x for x in t), tag + ': 5 more tests ran (%d)' % len(t))
    if len(t) < 5: continue
    ib, wm, en, hm, gd = t
    ok(gd['by'] == ['Tiers', 'Stars', 'Looks', 'Use'] and all(gd['byTips']) and gd['stat'] == gd['expected'] == '83 rated 4★+ · 14 at 5★' and '4★ or more' in (gd['tip'] or '') and gd['over'] == 0 and not gd['wide'] and not gd['bad'] and not gd['errors'], tag + ': Good tab: fill by Tiers, the two numbers beside the intro %s' % {k: gd.get(k) for k in ('by', 'stat', 'beside', 'over')})
    ok(gd['lensBefore'] == 3 and gd['lensAfter'] == 5 and gd['statAfter'] == '84 rated 4★+ · 15 at 5★' and (gd['beside'] or tag.startswith('375')), tag + ': Good tab: the numbers follow the stars, beside the intro on a wide screen %s' % {k: gd.get(k) for k in ('lensAfter', 'statAfter', 'beside')})
    ok(ib['add'] and ib['add']['kind'] == 'envision' and ib['add']['screen'] == 'Lens' and ib['add']['text'] == 'Calmer' and len(ib['add']['mixes']) == 2 and ib['badge'] == '1', tag + ': Re-envision from a card\'s ⋯ sends a request %s' % ib.get('add'))
    ok(ib['nInbox'] == '2' and ib['afterDone']['n'] == '1' and ib['afterDone']['firstDone'] and ib['afterUndone'] == '2' and ib['afterDel']['undo'] and ib['afterUndo']['n'] == 3 and ib['focus'] == 'Lens' and not ib['errors'] and not ib['bad'] and ib['over'] == 0, tag + ': the request in the Inbox: note, done, undone, delete, undo, its picture %s' % {k: ib[k] for k in ('nInbox', 'afterDone', 'afterUndone', 'afterDel', 'afterUndo')})
    ok(wm['altRight']['set'][:2] == ['caring', 'love'] and wm['altRight']['focus'] == 'w:love' and all(wm['afterEdit'].values()) and wm['addExisting'] == {'calmOn': True, 'count': 1, 'chips': 1} and not wm['addOnAgain']['moved'], tag + ': words: Alt+arrows, edit, adding one that is there %s' % {k: wm[k] for k in ('altRight', 'addExisting', 'addOnAgain')})
    ok(wm['dropOnOff']['off'] and wm['lastPoolHold'] == ['fun', 'real things'] and wm.get('heldRename') == {'poolHold': ['funny', 'real things'], 'held': True, 'inPool': True, 'back': ['fun', 'real things']} and not wm['errors'] and wm['over'] == 0, tag + ': words: drop on an off word, a held word renamed stays held %s' % {k: wm.get(k) for k in ('heldRename', 'lastPoolHold')})
    ok(wm.get('renameOnto') == {'same': True, 'focus': 'w:calm', 'chips': ['calm', 'fun held'], 'onSame': True}, tag + ': words: a rename to another word\'s name is refused, that word gets the focus %s' % wm.get('renameOnto'))
    ok(en['around'][1:4] == ['eng_amsterdam', 'eng_regions', 'eng_traditions'] and en['ranked'] == 5 and en['dot'] and en['cardLink'].startswith('https://claude.ai/artifact/UQAwzFPMbEhhLcvrpZsjZR#Bergen.dc.html') and en['findEngine'] == 15 and len(en['pick']) == 3 and not en['errors'], tag + ': Engine group: rated like any screen, its own canvas %s' % en)
    ok(hm['a']['open'] and hm['a']['name'] == 'SanGimignano' and hm['a']['link'].startswith('https://claude.ai/artifact/UQAwzFPMbEhhLcvrpZsjZR#') and not hm['afterEsc']['open'] and hm['afterEsc']['hash'] == '' and hm['b']['open'] and hm['b']['name'] == hm['fm'] and 'Back' in hm['b']['move'] and hm['bClosed']['hash'] == '' and hm['c1'] and hm['c2'] and hm['hashEnd'] == '' and not hm['errors'], tag + ': #screen= after load, Escape, a folder screen, again %s' % hm)
for f in sorted(glob.glob(os.path.join(R, 'empty_*.txt'))):
    t = lines(f); x = t[0] if t else {}
    ok(x.get('status') == 'status saved|Empty' and all(v['over'] == 0 and not v['bad'] for v in x.get('tabs', {}).values()) and len(x.get('tabs', {})) == 6 and x.get('writesOnLoad') == [], os.path.basename(f) + ': an empty store: every tab draws, no writes %s' % x.get('tabs'))
    ok(x.get('afterAdd', {}).get('chips') == ['calm:on'] and x['afterAdd']['prompt'].startswith('calm') and x.get('parts') == ['part-opener', 'part-screen'] and x.get('dot') and x.get('nInbox') == '1' and len(x.get('writes', [])) == 3 and not x.get('errors') and not x.get('bad'), os.path.basename(f) + ': an empty store: a first word, star and Improve %s' % {k: x.get(k) for k in ('afterAdd', 'parts', 'nInbox', 'writes', 'errors')})
for f in sorted(glob.glob(os.path.join(R, 'claude_*.txt'))):
    t = lines(f); x = t[0] if t else {}
    ok(x.get('faceMain') == ['img', 'name nm', 'good-ic', 'open', 'rate', 'more-btn'] and x.get('cmMain', {}).get('who') == ['Mine:true', "Claude's:false"] and x['cmMain']['tier'] and x['cmMain']['dims'], os.path.basename(f) + ': Claude\'s behind the card\'s ⋯ %s' % x.get('cmMain'))
    ok("CLAUDE'S" in x.get('cmMainClaude', '') and 'Keep all' in x['cmMainClaude'] and x.get('afterKeepStars', {}).get('mine') == 3.5 and x.get('searchWho') == 0 and 'Claude' in (x.get('cat') or '') and x.get('showClaude'), os.path.basename(f) + ': Claude\'s: switch, Keep, category, Show Claude\'s %s' % {k: x.get(k) for k in ('cmMainClaude', 'afterKeepStars', 'cat')})
    ok(len(x.get('lookuse', [])) == 5 and x.get('pts') == 4 and x.get('rank', {}).get('tiers', 0) >= 5 and 'Keep mine' in (x.get('focus') or '') and not x.get('errors') and not x.get('bad'), os.path.basename(f) + ': Looks vs use, Tiers, the large view %s' % {k: x.get(k) for k in ('lookuse', 'pts', 'rank')})
for w in failed: print('FAIL', w)
print('== Studio checks: %d passed, %d failed' % (passed, len(failed)))
sys.exit(1 if failed else 0)
