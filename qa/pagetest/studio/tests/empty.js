/* an empty store: every tab draws; then the first word, a star, an Improve, a share title */
await ready();
const out = { status: Q('#status').className + '|' + Q('#status .st').textContent, tabs: {} };
for (const t of ['words', 'prompt', 'goodpage', 'screens', 'sourcespage', 'inbox']) { await tabTo(t); await sleep(200); out.tabs[t] = { over: over(), wide: wideEls().slice(0, 2), bad: bad(), text: Q('#' + t).innerText.slice(0, 120) }; }
out.writesOnLoad = fmtW(takeW());
await tabTo('words'); type(Q('#add-word-w'), 'calm'); Q('#add-word').requestSubmit(); await sleep(1000);
out.afterAdd = { chips: QA('#wordlist .chip').map(c => c.dataset.w + (c.classList.contains('off') ? ':off' : ':on')), cfgSet: S().cfg().set, pool: S().cfg().pool, prompt: S().promptFor(S().cfg().mixes[0]).text.slice(0, 200) };
await tabTo('prompt'); await sleep(200);
out.parts = QA('#prompt details.part').filter(d => !d.hidden).map(d => d.id); out.partmore = QA('#partmore button').map(b => b.textContent); out.goodLine = Q('#good-line').textContent;
await tabTo('screens'); await sleep(200);
tapAt(Q('#pages .shot[data-screen="Lens"] .rate .strip'), 0.89); await sleep(900);
out.dot = !!Q('#pages .shot[data-screen="Lens"] .dot');
Q('#pages .shot[data-screen="Lens"] .more-btn').click(); await sleep(200);
type(Q('#pages .shot[data-screen="Lens"] textarea.imp'), 'Bigger'); await sleep(1200);
await tabTo('inbox'); await sleep(300);
out.inbox = Q('#reqs').innerText.slice(0, 200); out.nInbox = Q('#n-inbox').textContent;
await tabTo('goodpage'); await sleep(300); out.good = Q('#goodbox').innerText.slice(0, 200);
out.writes = fmtW(takeW());
out.bad = bad(); out.errors = window.__errors;
return JSON.stringify(out);
