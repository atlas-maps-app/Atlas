/* every tab draws without errors, nothing sticks out, no null/undefined/NaN in the text */
await ready();
const out = { status: document.getElementById('status').className, tabs: {} };
for (const t of ['words', 'prompt', 'goodpage', 'screens', 'sourcespage', 'inbox']) {
  await tabTo(t); await sleep(200);
  const sec = document.getElementById(t);
  out.tabs[t] = { shown: !sec.hidden, h: Math.round(sec.getBoundingClientRect().height), over: over(), wide: wideEls().slice(0, 3), bad: bad() };
}
out.nav = QA('nav button').map(b => b.dataset.tab);
out.links = QA('.links a, .links .here').map(a => a.textContent + (a.href ? ' ' + a.href : ''));
out.errors = window.__errors; out.writes = fmtW(window.__writes);
return JSON.stringify(out);
