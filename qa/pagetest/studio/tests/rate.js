/* rating a screen: a tap on its stars writes studio/ranks; the gold dot follows 4★ or more; the Show › No stars filter */
await ready(); await tabTo('screens'); await sleep(200);
const out = {};
const dots = () => QA('#pages .shot .dot').length;
out.gold = dots(); out.goldExpected = Object.entries(S().starMap()).filter(([k, v]) => v >= 4 && Q('#pages .shot[data-screen="' + k + '"]')).length;   // a rated screen with no card any more draws no dot
const card = Q('#pages .shot[data-screen="Lens"]'), row = card.querySelector('.rate');
out.before = S().starMap().Lens;
tapAt(row.querySelector('.strip'), 0.89); await sleep(900);
const w = window.__writes.filter(x => x[1] === 'studio/ranks');
out.after = S().starMap().Lens; out.written = w.length ? w[w.length - 1][2].Lens : null; out.othersKept = w.length ? Object.keys(w[w.length - 1][2]).length : 0;
out.dot = !!Q('#pages .shot[data-screen="Lens"] .dot'); out.goldNow = dots();
tapAt(Q('#pages .shot[data-screen="Lens"] .rate .strip'), 0.5); await sleep(900);
out.after2 = S().starMap().Lens; out.dot2 = !!Q('#pages .shot[data-screen="Lens"] .dot');
/* Show › No stars */
Q('#rated [data-v="unrated"]').click(); await sleep(300);
const shown = QA('#pages .shot[data-screen]').map(e => e.dataset.screen);
out.noStars = { n: shown.length, anyStarred: shown.some(n => S().starMap()[n]), expected: S().cfg() && QA('#pages .shot').length };
Q('#rated [data-v="all"]').click(); await sleep(300);
out.all = QA('#pages .shot[data-screen]').length;
out.errors = window.__errors;
return JSON.stringify(out);
