/* the Engine group: where it sits, rating one, its links, Find, the large view's Engine ↗, the By stars view */
await ready(); await tabTo('screens'); await sleep(200);
const out = {};
const order = QA('#pages .page[data-page]').map(p => p.dataset.page); out.around = order.slice(order.indexOf('eng_amsterdam') - 1, order.indexOf('eng_traditions') + 2);
out.heads = QA('#pages .page[data-page^="eng_"] .page-h .t').map(t => t.textContent + '|' + getComputedStyle(t).color);
tapAt(Q('#pages .shot[data-screen="Bergen"] .rate .strip'), 0.99); await sleep(900);
const w = window.__writes.filter(x => x[1] === 'studio/ranks'); out.ranked = w.length ? w[w.length - 1][2].Bergen : null; out.dot = !!Q('#pages .shot[data-screen="Bergen"] .dot');
out.cardLink = Q('#pages .shot[data-screen="Bergen"] a.open').href + ' | ' + Q('#pages .shot[data-screen="Bergen"] a.open').title;
type(Q('#find'), 'engine'); await sleep(250); out.findEngine = QA('#pages .shot').length;
type(Q('#find'), ''); await sleep(250);
const v = Q('#view'); v.value = 'stars'; v.dispatchEvent(new Event('change')); await sleep(300);
out.starsTop = QA('#pages .page')[0].querySelector('.page-h').innerText.replace(/\n/g, ' ') + ' :: ' + QA('.shot', QA('#pages .page')[0]).map(s => s.dataset.screen).slice(0, 40).includes('Bergen');
v.value = 'pages'; v.dispatchEvent(new Event('change')); await sleep(300);
/* the prompt's screen picker lists them too */
out.pick = QA('#screen-pick optgroup').map(g => g.label).filter(l => /Engine/.test(l));
out.errors = window.__errors;
return JSON.stringify(out);
