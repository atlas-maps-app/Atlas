/* the prompts of many configs (r29's e2e set): each is put in the store, the page takes it up, every mix's prompt is read */
await ready();
const CFGS = __CFGS__;
const out = [];
for (const c of CFGS) {
  await window.__db.doc('studio/config').set(c);
  for (let i = 0; i < 40 && JSON.stringify(S().cfg().mixes) !== JSON.stringify(c.mixes || []); i++) await sleep(25);
  await sleep(30);
  const cfg = S().cfg();
  out.push(cfg.mixes.map(m => { const p = S().promptFor(m); return { name: m.name, own: typeof m.own === 'string', text: p.text, segments: p.segments, words: p.words }; }));
}
return JSON.stringify({ n: out.length, runs: out, errors: window.__errors });
