/* nodb.html: no store; failwrite.html: every write fails. The page still draws, says so by its dot, and throws nothing */
for (let i = 0; i < 60 && !document.querySelector('#pages .shot'); i++) await sleep(50); await sleep(400);
const out = { status: Q('#status').className, statusText: Q('#status .st').textContent, statusShown: visible(Q('#status .st')), cards: QA('#pages .shot').length };
/* rate a screen: with no store the dot keeps saying read only; with failing writes it says not saved */
await tabTo('screens'); tapAt(Q('#pages .shot[data-screen="Lens"] .rate .strip'), 0.89); await sleep(900);
out.afterRate = Q('#status').className + ' | ' + Q('#status .st').textContent + ' | shown ' + visible(Q('#status .st'));
for (const t of ['words', 'prompt', 'goodpage', 'screens', 'sourcespage', 'inbox']) { await tabTo(t); }
out.bad = bad(); out.errors = window.__errors.filter(e => !/denied/.test(e));
return JSON.stringify(out);
