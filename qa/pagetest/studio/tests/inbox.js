/* a request in the Inbox: Re-envision from a card's ⋯ sends one; the Inbox shows it; note, Mark done, Done again, Delete, Undo */
await ready(); await tabTo('screens'); await sleep(200);
const out = {};
const card = n => Q('#pages .shot[data-screen="' + n + '"]');
card('Lens').querySelector('.more-btn').click(); await sleep(200);
QA('button', card('Lens')).find(b => b.textContent === 'Re-envision').click(); await sleep(300);
const form = Q('#pages form.form'); out.form = form && form.innerText.replace(/\n+/g, ' | ').slice(0, 200);
type(Q('#req-text'), 'Calmer'); form.requestSubmit(); await sleep(500);
const add = window.__writes.find(x => x[0] === 'add'); out.add = add && { kind: add[2].kind, screen: add[2].screen, text: add[2].text, mixes: (add[2].mixes || []).map(m => m.name + ':' + (m.prompt || '').length), pageId: add[2].pageId, note: add[2].note };
out.badge = (card('Lens').querySelector('.badge') || {}).textContent;
await tabTo('inbox'); await sleep(300);
out.nInbox = Q('#n-inbox').textContent;
const first = Q('#reqs .req'); out.first = first && first.innerText.replace(/\n+/g, ' | ').slice(0, 250);
type(first.querySelector('textarea.note'), 'Keep the lens'); await sleep(900);
QA('button', first).find(b => b.textContent === 'Mark done').click(); await sleep(300);
out.afterDone = { n: Q('#n-inbox').textContent, firstDone: Q('#reqs .req').classList.contains('done'), btn: QA('button', Q('#reqs .req')).map(b => b.textContent) };
QA('button', Q('#reqs .req')).find(b => b.textContent === 'Done').click(); await sleep(300);
out.afterUndone = Q('#n-inbox').textContent;
QA('button', Q('#reqs .req')).find(b => b.textContent === 'Delete').click(); await sleep(300);
out.afterDel = { n: QA('#reqs .req').length, undo: !!Q('#reqs [data-undo]') };
Q('#reqs [data-undo]').click(); await sleep(300);
out.afterUndo = { n: QA('#reqs .req').length, first: Q('#reqs .req').innerText.slice(0, 60) };
out.writes = fmtW(window.__writes).map(x => x.slice(0, 140));
/* the thumbnail in the Inbox opens the large view */
const th = Q('#reqs .rthumb'); if (th) { th.click(); await sleep(400); out.focus = (Q('#focus .fname') || {}).textContent; }
out.bad = bad(); out.errors = window.__errors; out.over = over();
return JSON.stringify(out);
