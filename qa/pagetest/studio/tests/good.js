/* the Good tab: the fill toolbar sorts by Tiers · Stars · Looks · Use; beside the intro, two numbers from studio/ranks, live (4★+ and 5★) */
await ready(); await tabTo('goodpage'); await sleep(300);
const out = {};
const stat = () => Q('#good-stat');
out.by = QA('#goodbox .goodbar .seg button').map(b => b.textContent);
out.byTips = QA('#goodbox .goodbar .seg button').map(b => b.title);
out.stat = stat() && stat().textContent; out.tip = stat() && stat().title;
const sv = Object.values(S().starMap()); out.expected = sv.filter(v => v >= 4).length + ' rated 4★+ · ' + sv.filter(v => v >= 5).length + ' at 5★';
const intro = Q('#goodbox textarea.gtext.big');
out.beside = intro && stat() ? Math.abs(intro.getBoundingClientRect().top - stat().getBoundingClientRect().top) < 30 : null;   /* the same line on a wide screen */
out.introText = intro && intro.value;
out.over = over(); out.wide = wideEls();
/* a 5★ on a screen without stars: the numbers follow */
await tabTo('screens'); await sleep(200);
const lens = Q('#pages .shot[data-screen="Lens"] .rate .strip'); out.lensBefore = S().starMap().Lens || 0;
if (lens) { tapAt(lens, 0.99); await sleep(900); }
out.lensAfter = S().starMap().Lens;
await tabTo('goodpage'); await sleep(300);
out.statAfter = stat() && stat().textContent;
out.bad = bad(); out.errors = window.__errors;
return JSON.stringify(out);
