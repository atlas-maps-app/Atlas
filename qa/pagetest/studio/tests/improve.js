/* C11: one Improve field per screen, the screen's improve request edited in place (emptied, it stays with empty text and is not open); Well/Badly, notes and Questions are gone */
await ready(); await tabTo('screens'); await sleep(200);
const out = {};
out.gone = { wellBadly: QA('textarea.well, textarea.badly').length, questions: !!Q('nav [data-tab="questionspage"]') || !!Q('#questionspage'), notesFold: !!Q('#notes-fold, #prompt-notes'), noteBtn: QA('.note-btn').filter(b => /note/i.test(b.textContent)).length, good: !!Q('#good') };
/* Main has no improve request: typing makes one */
Q('#pages .shot[data-screen="Main"] .more-btn').click(); await sleep(200);
const cm = Q('#pages .shot[data-screen="Main"] .cardmore');
out.cardmore = cm ? { tier: !!cm.querySelector('.tier-sel'), looksUse: !!cm.querySelector('.dims-btn'), improve: cm.querySelectorAll('textarea.imp').length, move: !!cm.querySelector('[data-move]'), envision: QA('button', cm).some(b => b.textContent === 'Re-envision'), ask: !!cm.querySelector('[data-ask]') } : null;
const t = cm.querySelector('textarea.imp');
type(t, 'Does well: these icons and how they show up on the map.'); await sleep(1300);
const adds = window.__writes.filter(x => x[0] === 'add' && x[1].startsWith('requests/'));
out.add = adds.map(x => ({ kind: x[2].kind, screen: x[2].screen, text: x[2].text, status: x[2].status, page: x[2].page, pageId: x[2].pageId, note: x[2].note, askCat: x[2].askCat }));
/* MarketHall has one: typing edits it */
Q('#find').value = 'MarketHall'; Q('#find').dispatchEvent(new Event('input')); await sleep(300);
Q('#pages .shot[data-screen="MarketHall"] .img').click(); await sleep(400);
const ft = Q('#focus textarea.imp'); out.mhBefore = ft.value.slice(0, 40);
type(ft, ft.value + ' Sorted by nutri score per category at a glance is useful.'); await sleep(1300);
out.update = window.__writes.filter(x => x[0] === 'update' && x[1].startsWith('requests/')).map(x => [x[1], Object.keys(x[2]).join(',')]);
/* emptied: the request stays (the owner's note, Claude's reply), only its text goes; it is not open any more (no badge, no count), its thread stays in the Inbox */
const MH = 'lky14d5hgwrq2vwp985j', doc = () => window.__DATA.requests[MH];
out.nInboxBefore = Q('#n-inbox').textContent; out.badgeBefore = !!Q('#pages .shot[data-screen="MarketHall"] .badge');
type(Q('#focus textarea.imp'), ''); await sleep(1300);
out.emptied = { deletes: window.__writes.filter(x => x[0] === 'delete').length, last: (w => w && [w[0], w[1], JSON.stringify(w[2]).slice(0, 80)])(window.__writes.filter(x => x[1].startsWith('requests/')).pop()),
  kept: !!doc(), text: doc() && doc().text, reply: !!(doc() && doc().reply), status: doc() && doc().status, meta: !!Q('#focus .imeta') };
Q('#focus').dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); await sleep(300);
out.emptied.badge = !!Q('#pages .shot[data-screen="MarketHall"] .badge'); out.emptied.nInbox = Q('#n-inbox').textContent;
await tabTo('inbox'); await sleep(300);
const art = QA('#reqs .req').find(r => r.innerText.includes('MarketHall'));
out.emptied.inbox = art ? { pills: QA('.pill', art).map(x => x.textContent), reply: !!art.querySelector('.reply'), note: !!art.querySelector('textarea.note'), text: !!art.querySelector('p.text') } : null;
/* written again: open again, new to Claude */
await tabTo('screens'); await sleep(200);
Q('#pages .shot[data-screen="MarketHall"] .img').click(); await sleep(400);
type(Q('#focus textarea.imp'), 'Use the brand colour.'); await sleep(1300);
out.again = { last: (w => w && [w[0], w[1], Object.keys(w[2]).join(',')])(window.__writes.filter(x => x[1].startsWith('requests/')).pop()), status: doc().status, text: doc().text, reply: !!doc().reply, adds: window.__writes.filter(x => x[0] === 'add' && x[2].screen === 'MarketHall').length, nInbox: Q('#n-inbox').textContent };
out.errors = window.__errors;
return JSON.stringify(out);
